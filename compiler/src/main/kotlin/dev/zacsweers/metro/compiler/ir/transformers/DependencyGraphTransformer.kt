// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ExitProcessingException
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.ir.IrBindingContainerResolver
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrContributionMerger
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MetroDeclarations
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.ParentContext
import dev.zacsweers.metro.compiler.ir.ParentContextReader
import dev.zacsweers.metro.compiler.ir.UsedKeyCollector
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.graph.BindingGraphGenerator
import dev.zacsweers.metro.compiler.ir.graph.BindingLookupCache
import dev.zacsweers.metro.compiler.ir.graph.BindingPropertyContext
import dev.zacsweers.metro.compiler.ir.graph.GraphNode
import dev.zacsweers.metro.compiler.ir.graph.GraphNodes
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.graph.IrBindingStack
import dev.zacsweers.metro.compiler.ir.graph.IrGraphExtensionGenerator
import dev.zacsweers.metro.compiler.ir.graph.IrGraphGenerator
import dev.zacsweers.metro.compiler.ir.graph.generatedGraphExtensionData
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.irCallConstructorWithSameParameters
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireNestedClass
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.resolveOverriddenTypeIfAny
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.isGraphImpl
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.parallelMap
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.diagnosticTag
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.concurrent.ForkJoinPool
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.getAllSuperclasses
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.ClassId

/**
 * Result of validating a dependency graph. Contains all information needed to generate the graph
 * implementation in a subsequent phase.
 */
internal data class ValidationResult(
  val graphClassId: ClassId,
  val node: GraphNode.Local,
  val bindingGraph: IrBindingGraph,
  val sealResult: IrBindingGraph.BindingGraphResult,
  val graphExtensionGenerator: IrGraphExtensionGenerator,
  /** Child graph validation results to generate after this graph. */
  val childValidationResults: List<ValidationResult>,
  /** Context keys this graph uses from parent (reported back for extraKeeps). */
  val usedParentContextKeys: Set<IrContextualTypeKey>,
  val hasErrors: Boolean,
)

/** Result of validating a single graph extension (used during parallel validation). */
private data class ExtensionValidationTask(
  val contributedGraphKey: IrTypeKey,
  val contributedGraph: IrClass,
  val accessor: MetroSimpleFunction,
  val isDirectExtension: Boolean,
  val validation: ValidationResult,
  val usedContextKeys: Set<IrContextualTypeKey>,
)

internal class DependencyGraphTransformer(
  context: IrMetroContext,
  private val contributionData: IrContributionData,
  traceScope: TraceScope,
  private val forkJoinPool: ForkJoinPool?,
  private val metroDeclarations: MetroDeclarations,
  private val bindingContainerResolver: IrBindingContainerResolver,
) : IrMetroContext by context, TraceScope by traceScope {

  private val bindingLookupCache = BindingLookupCache()

  private val contributionMerger: IrContributionMerger =
    IrContributionMerger(this, contributionData)

  private val graphNodes =
    GraphNodes(this, metroDeclarations, bindingContainerResolver, contributionMerger)

  internal fun processGraph(
    dependencyGraphDeclaration: IrClass,
    dependencyGraphAnno: IrConstructorCall,
    graphImpl: IrClass,
  ) {
    try {
      @Suppress("RETURN_VALUE_NOT_USED")
      processGraphInner(
        dependencyGraphDeclaration,
        dependencyGraphAnno,
        graphImpl,
        parentContext = null,
      )
    } catch (_: ExitProcessingException) {
      // End processing, don't fail up because this would've been warned before
    }
  }

  /**
   * Processes a dependency graph through validation and generation phases.
   *
   * For root graphs (parentContext == null): validates all graphs in the tree, then generates
   * parent-first so children can resolve property tokens against finalized parent properties.
   *
   * For child graphs (parentContext != null): only validates, returning the result. Generation is
   * handled by the parent after it generates its own properties.
   */
  private fun processGraphInner(
    dependencyGraphDeclaration: IrClass,
    dependencyGraphAnno: IrConstructorCall,
    metroGraph: IrClass,
    parentContext: ParentContext?,
  ): IrBindingGraph.BindingGraphResult? {
    val graphClassId = dependencyGraphDeclaration.classIdOrFail

    if (dependencyGraphDeclaration.isExternalParent) {
      // Externally compiled, just use its generated class
      return null
    }

    val tag = dependencyGraphDeclaration.kotlinFqName.shortName().asString()
    val diagnosticTag = dependencyGraphDeclaration.diagnosticTag

    // Phase 1: Validate the entire graph tree (recursively validates children)
    return trace("[$tag] Transform dependency graph") {
      val validationResult =
        trace("Prepare and validate") {
          validateDependencyGraph(
            graphClassId,
            dependencyGraphDeclaration,
            dependencyGraphAnno,
            metroGraph,
            parentContext,
            diagnosticTag,
          )
        }

      if (validationResult.hasErrors) {
        validationResult.sealResult
      } else {
        // Phase 2: Generate (only for root graphs - children are generated by parent)
        if (parentContext == null) {
          generateDependencyGraph(validationResult, parentBindingContext = null)
        }
        validationResult.sealResult
      }
    }
  }

  /**
   * Validates a dependency graph and all its children, returning a [ValidationResult] that can be
   * used to generate the graph implementation.
   *
   * This phase:
   * 1. Builds the binding graph
   * 2. Recursively validates child graphs (collecting their ValidationResults)
   * 3. Seals/validates the binding graph
   * 4. Marks bindings used from parent context
   *
   * Actual code generation (property creation, accessor implementation) is deferred to
   * [generateDependencyGraph] so that parent properties are finalized before children generate.
   */
  context(traceScope: TraceScope)
  private fun validateDependencyGraph(
    graphClassId: ClassId,
    dependencyGraphDeclaration: IrClass,
    dependencyGraphAnno: IrConstructorCall,
    metroGraph: IrClass,
    parentContextReader: ParentContextReader?,
    diagnosticTag: String,
  ): ValidationResult {
    val node =
      graphNodes.getOrComputeNode(
        dependencyGraphDeclaration,
        IrBindingStack(
          dependencyGraphDeclaration,
          metroContext.loggerFor(MetroLogger.Type.GraphNodeConstruction),
        ),
        diagnosticTag,
        metroGraph,
        dependencyGraphAnno,
      ) as GraphNode.Local

    // Generate creator functions
    trace("Implement creator functions") {
      implementCreatorFunctions(node.sourceGraph, node.creator, metroGraph)
    }

    val bindingGraph =
      trace("Build binding graph") {
        BindingGraphGenerator(
            metroContext,
            this,
            node,
            metroDeclarations,
            contributionData,
            parentContextReader,
            bindingLookupCache,
          )
          .generate()
      }

    val graphExtensionGenerator =
      IrGraphExtensionGenerator(
        metroContext,
        contributionMerger,
        bindingContainerResolver,
        node.sourceGraph.metroGraphOrFail,
      )

    // Collect child validation results for deferred generation (populated if there are extensions)
    val childValidationResults = mutableListOf<ValidationResult>()
    var usedParentContextKeys: Set<IrContextualTypeKey> = emptySet()
    var hasErrors = false

    // Before validating/sealing the parent graph, analyze contributed child graphs to
    // determine any parent-scoped static bindings that are required by children and
    // add synthetic roots for them so they are materialized in the parent.
    if (node.graphExtensions.isNotEmpty()) {
      // Collect parent-available scoped binding keys to match against
      // @Binds not checked because they cannot be scoped!
      // If parent is a real ParentContext, reuse it (sequential mode - shares context stack).
      // If parent is a snapshot-backed reader (parallel mode), create fresh with the snapshot
      // reader as ancestorReader so scope checks and mark() calls can delegate to ancestor
      // levels not represented in the local level stack.
      val localParentContext =
        (parentContextReader as? ParentContext)
          ?: ParentContext(metroContext, parent = parentContextReader)

      // This instance
      localParentContext.add(node.typeKey)

      // @Provides
      node.providerFactories.values.flatten().forEach { providerFactory ->
        if (providerFactory.annotations.isScoped) {
          // skip @GraphPrivate bindings
          if (providerFactory.typeKey !in node.graphPrivateKeys) {
            localParentContext.add(providerFactory.typeKey)
          }
        }
      }

      // Instance bindings
      node.creator?.parameters?.regularParameters?.forEach { parameter ->
        // skip @GraphPrivate bindings
        if (parameter.typeKey !in node.graphPrivateKeys) {
          // Make both provides and includes available
          localParentContext.add(parameter.typeKey)
        }
      }

      // Published @Binds keys: non-private @Binds whose source is @GraphPrivate are exposed to
      // child graphs as parent-resolved dependencies (the child can't inherit the @Binds alias
      // since it can't resolve the private source type).
      localParentContext.addAll(node.publishedBindsKeys)

      // Included graph dependencies. Only include the current level, transitively included ones
      // will already be in the parent context
      for (included in node.includedGraphNodes.values) {
        localParentContext.addAll(included.publicAccessors)
      }

      // Two passes on graph extensions
      // Shallow first pass to create any keys for non-factory-returning types
      val directExtensions = mutableSetOf<IrTypeKey>()
      for ((typeKey, accessors) in node.graphExtensions) {
        if (typeKey in bindingGraph) continue // Skip if already in graph

        for (extensionAccessor in accessors) {
          if (extensionAccessor.isFactory) {
            // It's a factory returner instead
            localParentContext.add(extensionAccessor.key.typeKey)
            continue
          }

          if (!extensionAccessor.isFactorySAM) {
            localParentContext.add(typeKey)
            directExtensions.add(typeKey)
          }
        }
      }

      // Transform the contributed graphs
      // Push the parent graph for all contributed graph processing
      localParentContext.pushParentGraph(node)

      // Pre-build all extension graph impl classes sequentially before parallel validation.
      // This adds nested classes to the parent graph's declarations list, which must not
      // happen concurrently with iteration over that list during graph node construction.
      val prebuiltExtensionGraphs =
        node.graphExtensions.entries.associate { (contributedGraphKey, accessors) ->
          contributedGraphKey to
            graphExtensionGenerator.getOrBuildGraphExtensionImpl(
              contributedGraphKey,
              node.sourceGraph,
              accessors.first().accessor,
            )
        }

      // Second pass on graph extensions to actually process them and create GraphExtension bindings
      // Can run in parallel if executor is available
      fun validateExtension(
        contributedGraphKey: IrTypeKey,
        contributedGraph: IrClass,
        accessor: MetroSimpleFunction,
        reader: ParentContextReader,
        usedKeysProvider: () -> Set<IrContextualTypeKey>,
      ): ExtensionValidationTask {

        // Validate the child graph
        val childTag = contributedGraph.kotlinFqName.shortName().asString()
        val childValidation =
          trace("[$childTag] Validate child graph") {
            validateDependencyGraph(
              contributedGraph.classIdOrFail,
              contributedGraph,
              contributedGraph.annotationsIn(metroSymbols.dependencyGraphAnnotations).single(),
              contributedGraph,
              reader,
              diagnosticTag,
            )
          }

        return ExtensionValidationTask(
          contributedGraphKey = contributedGraphKey,
          contributedGraph = contributedGraph,
          accessor = accessor,
          isDirectExtension = contributedGraphKey in directExtensions,
          validation = childValidation,
          usedContextKeys = usedKeysProvider(),
        )
      }

      val extensionTasks: List<ExtensionValidationTask> =
        if (forkJoinPool != null && node.graphExtensions.size > 1) {
          // Parallel mode: create snapshot and validate children concurrently
          val snapshot = localParentContext.snapshot()
          node.graphExtensions.entries.toList().parallelMap(forkJoinPool) {
            (contributedGraphKey, accessors) ->
            val collector = UsedKeyCollector()
            validateExtension(
              contributedGraphKey,
              prebuiltExtensionGraphs.getValue(contributedGraphKey),
              accessors.first().accessor,
              snapshot.asReader(collector),
              collector::keys,
            )
          }
        } else {
          // Sequential mode: use shared parent context directly
          node.graphExtensions.map { (contributedGraphKey, accessors) ->
            validateExtension(
              contributedGraphKey,
              prebuiltExtensionGraphs.getValue(contributedGraphKey),
              accessors.first().accessor,
              localParentContext,
              localParentContext::usedContextKeys,
            )
          }
        }

      // Merge results sequentially
      for (task in extensionTasks) {
        childValidationResults.add(task.validation)

        if (task.validation.hasErrors) {
          hasErrors = true
          continue
        }

        if (task.isDirectExtension) {
          val binding =
            IrBinding.GraphExtension(
              typeKey = task.contributedGraphKey,
              parent = node.sourceGraph,
              accessor = task.accessor.ir,
              parentGraphKey = node.typeKey,
            )

          bindingGraph.addBinding(task.contributedGraphKey, binding, IrBindingStack.empty())

          bindingGraph.keep(
            binding.contextualTypeKey,
            IrBindingStack.Entry.generatedExtensionAt(
              binding.contextualTypeKey,
              node.sourceGraph.kotlinFqName.asString(),
              task.accessor.ir,
            ),
          )
        }

        writeDiagnostic(
          "parent-keys-used",
          { "${node.sourceGraph.name}-by-${task.contributedGraph.name}.txt" },
        ) {
          task.usedContextKeys.sortedBy { it.typeKey }.joinToString(separator = "\n")
        }

        for (contextKey in task.usedContextKeys) {
          bindingGraph.keep(contextKey, IrBindingStack.Entry.simpleTypeRef(contextKey))
          bindingGraph.reserveContextKey(contextKey)
        }
      }

      // Pop the parent graph after all contributed graphs are processed
      usedParentContextKeys = localParentContext.popParentGraph()

      // Write diagnostic for parent keys used in child graphs
      if (usedParentContextKeys.isNotEmpty()) {
        writeDiagnostic("parent-keys-used-all", { "${node.sourceGraph.name}.txt" }) {
          usedParentContextKeys.sortedBy { it.typeKey }.joinToString(separator = "\n")
        }
      }
    }

    // Validate that no accessor exposes a @GraphPrivate binding
    if (node.graphPrivateKeys.isNotEmpty()) {
      for (accessor in node.accessors) {
        if (accessor.contextKey.typeKey in node.graphPrivateKeys) {
          // Resolve to the source declaration (the user-authored property/function in the
          // interface, not the fake override in the generated impl class)
          val accessorIr = accessor.metroFunction.ir
          val sourceDeclaration: IrDeclaration =
            accessorIr.correspondingPropertySymbol?.owner?.resolveOverriddenTypeIfAny()
              ?: accessorIr.resolveOverriddenTypeIfAny()
          reportCompat(
            irDeclarations = sequenceOf(sourceDeclaration, dependencyGraphDeclaration),
            factory = MetroDiagnostics.PRIVATE_BINDING_ERROR,
            a =
              "Cannot expose @GraphPrivate binding '${accessor.contextKey.typeKey.render(short = true)}' as a graph accessor. @GraphPrivate bindings are confined to the graph they are provided in.",
          )
          hasErrors = true
        }
      }
    }

    val sealResult =
      bindingGraph.seal { errors ->
        for ((declaration, message) in errors) {
          reportCompat(
            irDeclarations = sequenceOf(declaration, dependencyGraphDeclaration),
            factory = MetroDiagnostics.METRO_ERROR,
            a = message,
          )
        }
      }

    sealResult.reportUnusedInputs(dependencyGraphDeclaration)

    if (sealResult.hasErrors) {
      hasErrors = true
    }

    // Build validation result (may have errors - caller will check)
    val validationResult =
      ValidationResult(
        graphClassId = graphClassId,
        node = node,
        bindingGraph = bindingGraph,
        sealResult = sealResult,
        graphExtensionGenerator = graphExtensionGenerator,
        childValidationResults = childValidationResults,
        usedParentContextKeys = usedParentContextKeys,
        hasErrors = hasErrors,
      )

    if (hasErrors) {
      // Return early with errors - caller will handle
      return validationResult
    }

    // Mark bindings from enclosing parents to ensure they're generated there
    // Only applicable in graph extensions
    if (parentContextReader != null) {
      for (key in sealResult.reachableKeys) {
        val isSelfKey =
          key == node.typeKey || key == node.metroGraph?.generatedGraphExtensionData?.typeKey
        if (!isSelfKey && key in parentContextReader) {
          @Suppress("RETURN_VALUE_NOT_USED") parentContextReader.mark(key)
        }
      }
    }

    writeDiagnostic(
      "graph-dump",
      { "${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.txt" },
    ) {
      bindingGraph.dumpGraph(node.sourceGraph.kotlinFqName.asString(), short = false)
    }

    return validationResult
  }

  private fun IrBindingGraph.BindingGraphResult.reportUnusedInputs(graphDeclaration: IrClass) {
    val severity = options.unusedGraphInputsSeverity
    if (!severity.isEnabled) return

    if (unusedKeys.isEmpty()) return

    val diagnosticFactory =
      when (severity) {
        WARN -> MetroDiagnostics.UNUSED_GRAPH_INPUT_WARNING
        ERROR -> MetroDiagnostics.UNUSED_GRAPH_INPUT_ERROR
        // Already checked above, but for exhaustive when
        NONE -> return
      }

    val unusedGraphInputs = unusedKeys.values.filterNotNull().sortedBy { it.typeKey }

    for (unusedBinding in unusedGraphInputs) {
      val message = buildString {
        appendLine("Graph input '${unusedBinding.typeKey}' is unused and can be removed.")

        // Show a hint of what direct node is including this, if any
        unusedBinding.typeKey.type.rawTypeOrNull()?.let { containerClass ->
          // Efficient to call here as it should be already cached
          val transitivelyIncluded =
            bindingContainerResolver.getCached(containerClass)?.mapToSet { it.typeKey }.orEmpty()
          val transitivelyUsed =
            sortedKeys.intersect(transitivelyIncluded).minus(unusedBinding.typeKey)
          if (transitivelyUsed.isNotEmpty()) {
            appendLine()
            appendLine("(Hint)")
            appendLine(
              "The following binding containers *are* used and transitively included by '${unusedBinding.typeKey}'. Consider including them directly instead"
            )
            transitivelyUsed.sorted().joinTo(this, separator = "\n", postfix = "\n") { "- $it" }
          }
        }
      }
      unusedBinding.irElement?.let { irElement ->
        diagnosticReporter.at(irElement, graphDeclaration.file).report(diagnosticFactory, message)
        continue
      }

      val graphDeclarationSource =
        if (graphDeclaration.origin.isGraphImpl) {
          graphDeclaration.getAllSuperclasses().find {
            it.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionAnnotations)
          }
        } else {
          graphDeclaration
        }

      reportCompat(
        irDeclarations = sequenceOf(unusedBinding.reportableDeclaration, graphDeclarationSource),
        factory = diagnosticFactory,
        a = message,
      )
    }
  }

  /**
   * Generates a dependency graph implementation from a validated [ValidationResult].
   *
   * This phase runs after all graphs have been validated. It generates parent graphs first, then
   * children, so that children can resolve property access tokens against the parent's finalized
   * [BindingPropertyContext].
   *
   * @param validationResult The validation result to generate
   * @param parentBindingContext Parent graph's binding property context for hierarchical lookup.
   *   Null for root graphs.
   */
  context(traceScope: TraceScope)
  private fun generateDependencyGraph(
    validationResult: ValidationResult,
    parentBindingContext: BindingPropertyContext?,
  ) {
    val node = validationResult.node
    val metroGraph = node.metroGraphOrFail

    trace("[${metroGraph.kotlinFqName.shortName().asString()}] Generate graph") {
      try {
        // Generate this graph's implementation
        val bindingPropertyContext =
          IrGraphGenerator(
              metroContext = metroContext,
              traceScope = this,
              diagnosticTag = metroGraph.diagnosticTag,
              graphNodesByClass = graphNodes::get,
              node = node,
              graphClass = metroGraph,
              bindingGraph = validationResult.bindingGraph,
              sealResult = validationResult.sealResult,
              metroDeclarations = metroDeclarations,
              graphExtensionGenerator = validationResult.graphExtensionGenerator,
              parentBindingContext = parentBindingContext,
            )
            .generate()

        // Generate child graphs with this graph's binding context as their parent
        for (childResult in validationResult.childValidationResults) {
          generateDependencyGraph(childResult, parentBindingContext = bindingPropertyContext)
        }
      } catch (e: Exception) {
        if (e is ExitProcessingException) {
          // Implement unimplemented overrides to reduce noise in failure output
          // Otherwise compiler may complain that these are invalid bytecode
          implementCreatorFunctions(
            node.sourceGraph,
            node.creator,
            node.sourceGraph.metroGraphOrFail,
          )

          node.accessors
            .asSequence()
            .map { it.metroFunction.ir }
            .plus(node.injectors.map { it.metroFunction.ir })
            .plus(node.bindsCallables.values.flatten().map { it.callableMetadata.function })
            .plus(node.graphExtensions.flatMap { it.value }.map { it.accessor.ir })
            .filterNot { it.isExternalParent }
            .forEach { function ->
              with(function) {
                val declarationToFinalize =
                  propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
                if (declarationToFinalize.isFakeOverride) {
                  declarationToFinalize.finalizeFakeOverride(
                    metroGraph.thisReceiverOrFail.copyTo(this)
                  )
                  body =
                    if (returnType != pluginContext.irBuiltIns.unitType) {
                      stubExpressionBody(
                        "Graph transform failed. If you're seeing this at runtime, it means that the Metro compiler plugin reported a compiler error but kotlinc for some reason didn't fail the build!"
                      )
                    } else {
                      pluginContext.createIrBuilder(symbol).run {
                        irBlockBody { +irReturn(irGetObject(pluginContext.irBuiltIns.unitClass)) }
                      }
                    }
                }
              }
            }
          throw e
        }
        throw AssertionError(
            "Code gen exception while processing ${node.sourceGraph.classIdOrFail}. ${e.message}",
            e,
          )
          .apply {
            // Don't fill in the stacktrace here as it's not relevant to the issue
            setStackTrace(emptyArray())
          }
      }
    }

    metroGraph.dumpToMetroLog()

    writeDiagnostic(
      "graph-dumpKotlin",
      { "${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt" },
    ) {
      metroGraph.dumpKotlinLike()
    }
  }

  private fun implementCreatorFunctions(
    sourceGraph: IrClass,
    creator: GraphNode.Creator?,
    metroGraph: IrClass,
  ) {
    // NOTE: may not have a companion object if this graph is a contributed graph, which has no
    // static creators
    val companionObject = sourceGraph.companionObject() ?: return
    val factoryCreator = creator?.expectAsOrNull<GraphNode.Creator.Factory>()
    if (factoryCreator != null) {
      // TODO would be nice if we could just class delegate to the `Impl` object
      val implementFactoryFunction: IrClass.() -> Unit = {
        val samName = factoryCreator.function.name.asString()
        requireSimpleFunction(samName).owner.apply {
          if (isFakeOverride) {
            finalizeFakeOverride(metroGraph.thisReceiverOrFail)
          }
          val createFunction = this
          body =
            pluginContext.createIrBuilder(symbol).run {
              irExprBodySafe(
                irCallConstructorWithSameParameters(
                  source = createFunction,
                  constructor = metroGraph.primaryConstructor!!.symbol,
                )
              )
            }
        }
      }

      // Implement the factory's `Impl` class if present
      val factoryImpl =
        factoryCreator.type.requireNestedClass(Symbols.Names.Impl).apply(implementFactoryFunction)

      if (
        factoryCreator.type.isInterface &&
          companionObject.implements(factoryCreator.type.classIdOrFail)
      ) {
        // Implement the interface creator function directly in this companion object
        companionObject.implementFactoryFunction()
      } else {
        companionObject.apply {
          // Implement a factory() function that returns the factory impl instance
          requireSimpleFunction(Symbols.StringNames.FACTORY).owner.apply {
            if (origin == Origins.MetroGraphFactoryCompanionGetter) {
              if (isFakeOverride) {
                finalizeFakeOverride(metroGraph.thisReceiverOrFail)
              }
              body =
                pluginContext.createIrBuilder(symbol).run {
                  irExprBodySafe(
                    irCallConstructor(factoryImpl.primaryConstructor!!.symbol, emptyList())
                  )
                }
            }
          }
        }
      }
    } else {
      // Generate a no-arg invoke() function
      companionObject.apply {
        requireSimpleFunction(Symbols.StringNames.INVOKE).owner.apply {
          if (isFakeOverride) {
            finalizeFakeOverride(metroGraph.thisReceiverOrFail)
          }
          body =
            pluginContext.createIrBuilder(symbol).run {
              irExprBodySafe(irCallConstructor(metroGraph.primaryConstructor!!.symbol, emptyList()))
            }
        }
      }
    }

    companionObject.dumpToMetroLog()
  }
}
