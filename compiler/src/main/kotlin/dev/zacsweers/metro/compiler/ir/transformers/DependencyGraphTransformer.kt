// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ExitProcessingException
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.MetroOptions
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
import dev.zacsweers.metro.compiler.ir.ParentContext
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.graph.BindingGraphGenerator
import dev.zacsweers.metro.compiler.ir.graph.BindingPropertyContext
import dev.zacsweers.metro.compiler.ir.graph.GraphNode
import dev.zacsweers.metro.compiler.ir.graph.GraphNodes
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.graph.IrBindingStack
import dev.zacsweers.metro.compiler.ir.graph.IrDynamicGraphGenerator
import dev.zacsweers.metro.compiler.ir.graph.IrGraphExtensionGenerator
import dev.zacsweers.metro.compiler.ir.graph.IrGraphGenerator
import dev.zacsweers.metro.compiler.ir.graph.generatedGraphExtensionData
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.irCallConstructorWithSameParameters
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.isCompanionObject
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.nestedClassOrNull
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireNestedClass
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ScopeWithIr
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isLocal
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
)

internal class DependencyGraphTransformer(
  context: IrMetroContext,
  private val contributionData: IrContributionData,
  traceScope: TraceScope,
  hintGenerator: HintGenerator,
) :
  IrElementTransformerVoidWithContext(),
  TransformerContextAccess,
  IrMetroContext by context,
  TraceScope by traceScope {

  private val membersInjectorTransformer = MembersInjectorTransformer(context)
  private val injectConstructorTransformer =
    InjectConstructorTransformer(context, membersInjectorTransformer)
  private val assistedFactoryTransformer =
    AssistedFactoryTransformer(context, injectConstructorTransformer)
  private val bindingContainerTransformer = BindingContainerTransformer(context)
  private val contributionHintIrTransformer by memoize {
    ContributionHintIrTransformer(context, hintGenerator)
  }
  private val bindingContainerResolver = IrBindingContainerResolver(bindingContainerTransformer)
  private val contributionMerger = IrContributionMerger(this, contributionData)
  private val dynamicGraphGenerator =
    IrDynamicGraphGenerator(this, bindingContainerResolver, contributionMerger)
  private val createGraphTransformer = CreateGraphTransformer(this, dynamicGraphGenerator)

  private val graphNodes =
    GraphNodes(this, bindingContainerTransformer, bindingContainerResolver, contributionMerger)

  override val currentFileAccess: IrFile
    get() = currentFile

  override val currentScriptAccess: ScopeWithIr?
    get() = currentScript

  override val currentClassAccess: ScopeWithIr?
    get() = currentClass

  override val currentFunctionAccess: ScopeWithIr?
    get() = currentFunction

  override val currentPropertyAccess: ScopeWithIr?
    get() = currentProperty

  override val currentAnonymousInitializerAccess: ScopeWithIr?
    get() = currentAnonymousInitializer

  override val currentValueParameterAccess: ScopeWithIr?
    get() = currentValueParameter

  override val currentScopeAccess: ScopeWithIr?
    get() = currentScope

  override val parentScopeAccess: ScopeWithIr?
    get() = parentScope

  override val allScopesAccess: MutableList<ScopeWithIr>
    get() = allScopes

  override val currentDeclarationParentAccess: IrDeclarationParent?
    get() = currentDeclarationParent

  override fun visitCall(expression: IrCall): IrExpression {
    return createGraphTransformer.visitCall(expression)
      ?: AsContributionTransformer.visitCall(expression, metroContext)
      // Optimization: skip intermediate visit methods (visitFunctionAccessExpression, etc.)
      // since we don't override them. Call visitExpression directly to save stack frames.
      ?: super.visitExpression(expression)
  }

  override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
    if (options.generateContributionHintsInFir) {
      contributionHintIrTransformer.visitFunction(declaration)
    }
    return super.visitSimpleFunction(declaration)
  }

  override fun visitClassNew(declaration: IrClass): IrStatement {
    val shouldNotProcess =
      declaration.isLocal ||
        declaration.kind == ClassKind.ENUM_CLASS ||
        declaration.kind == ClassKind.ENUM_ENTRY
    if (shouldNotProcess) {
      return super.visitClassNew(declaration)
    }

    log("Reading ${declaration.kotlinFqName}")

    // TODO need to better divvy these
    // TODO can we eagerly check for known metro types and skip?
    // Native/WASM/JS compilation hint gen can't be done in IR
    // https://youtrack.jetbrains.com/issue/KT-75865
    val generateHints = options.generateContributionHints && !options.generateContributionHintsInFir
    if (generateHints) {
      contributionHintIrTransformer.visitClass(declaration)
    }
    membersInjectorTransformer.visitClass(declaration)
    injectConstructorTransformer.visitClass(declaration)
    assistedFactoryTransformer.visitClass(declaration)

    if (!declaration.isCompanionObject) {
      // Companion objects are only processed in the context of their parent classes
      bindingContainerTransformer.findContainer(declaration)
    }

    val dependencyGraphAnno =
      declaration.annotationsIn(metroSymbols.dependencyGraphAnnotations).singleOrNull()
        ?: return super.visitClassNew(declaration)

    tryProcessDependencyGraph(declaration, dependencyGraphAnno)

    return super.visitClassNew(declaration)
  }

  private fun tryProcessDependencyGraph(
    dependencyGraphDeclaration: IrClass,
    dependencyGraphAnno: IrConstructorCall,
  ) {
    val metroGraph =
      if (dependencyGraphDeclaration.origin == Origins.GeneratedGraphExtension) {
        // If it's a contributed graph, we process that directly while processing the parent. Do
        // nothing
        return
      } else {
        dependencyGraphDeclaration.nestedClassOrNull(Origins.GraphImplClassDeclaration)
          ?: reportCompilerBug(
            "Expected generated dependency graph for ${dependencyGraphDeclaration.classIdOrFail}"
          )
      }
    try {
      processDependencyGraph(
        dependencyGraphDeclaration,
        dependencyGraphAnno,
        metroGraph,
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
  internal fun processDependencyGraph(
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

    // Phase 1: Validate the entire graph tree (recursively validates children)
    traceNested("[$tag] Transform dependency graph", tag) {
      val validationResult =
        traceNested("Prepare and validate", tag) {
          validateDependencyGraph(
            graphClassId,
            dependencyGraphDeclaration,
            dependencyGraphAnno,
            metroGraph,
            parentContext,
          )
        }
      if (validationResult.sealResult.hasErrors) {
        val result = validationResult.sealResult
        return result
      }

      // Phase 2: Generate (only for root graphs - children are generated by parent)
      if (parentContext == null) {
        generateDependencyGraph(validationResult, parentBindingContext = null)
      }

      return validationResult.sealResult
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
    parentContext: ParentContext?,
  ): ValidationResult {
    val node =
      graphNodes.getOrComputeNode(
        dependencyGraphDeclaration,
        IrBindingStack(
          dependencyGraphDeclaration,
          metroContext.loggerFor(MetroLogger.Type.GraphNodeConstruction),
        ),
        metroGraph,
        dependencyGraphAnno,
      ) as GraphNode.Local

    // Generate creator functions
    traceNested("Implement creator functions") {
      implementCreatorFunctions(node.sourceGraph, node.creator, metroGraph)
    }

    val bindingGraph =
      traceNested("Build binding graph") {
        BindingGraphGenerator(
            metroContext,
            node,
            injectConstructorTransformer,
            membersInjectorTransformer,
            contributionData,
            parentContext,
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

    // Before validating/sealing the parent graph, analyze contributed child graphs to
    // determine any parent-scoped static bindings that are required by children and
    // add synthetic roots for them so they are materialized in the parent.
    if (node.graphExtensions.isNotEmpty()) {
      // Collect parent-available scoped binding keys to match against
      // @Binds not checked because they cannot be scoped!
      val localParentContext = parentContext ?: ParentContext(metroContext)

      // This instance
      localParentContext.add(node.typeKey)

      // @Provides
      node.providerFactories.values.flatten().forEach { providerFactory ->
        if (providerFactory.annotations.isScoped) {
          localParentContext.add(providerFactory.typeKey)
        }
      }

      // Instance bindings
      node.creator?.parameters?.regularParameters?.forEach { parameter ->
        // Make both provides and includes available
        localParentContext.add(parameter.typeKey)
      }

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

      // Second pass on graph extensions to actually process them and create GraphExtension bindings
      for ((contributedGraphKey, accessors) in node.graphExtensions) {
        val extensionAccessor = accessors.first() // Only need one for below linking

        val accessor = extensionAccessor.accessor

        // Determine the actual graph extension type key
        val actualGraphExtensionTypeKey = contributedGraphKey

        // Generate the contributed graph class
        val contributedGraph =
          graphExtensionGenerator.getOrBuildGraphExtensionImpl(
            actualGraphExtensionTypeKey,
            node.sourceGraph,
            accessor,
          )

        // Validate the child graph (generation is deferred until after parent generates)
        val childTag = contributedGraph.kotlinFqName.shortName().asString()
        val childValidation =
          traceNested("[$childTag] Validate child graph", childTag) {
            validateDependencyGraph(
              contributedGraph.classIdOrFail,
              contributedGraph,
              contributedGraph.annotationsIn(metroSymbols.dependencyGraphAnnotations).single(),
              contributedGraph,
              localParentContext,
            )
          }

        childValidationResults.add(childValidation)

        // Capture the used keys for this graph extension
        val usedContextKeys = localParentContext.usedContextKeys()

        if (contributedGraphKey in directExtensions) {
          val binding =
            IrBinding.GraphExtension(
              typeKey = contributedGraphKey,
              parent = node.sourceGraph,
              accessor = accessor.ir,
              parentGraphKey = node.typeKey,
            )

          // Replace the binding with the updated version
          bindingGraph.addBinding(contributedGraphKey, binding, IrBindingStack.empty())

          // Necessary since we don't treat graph extensions as part of roots
          bindingGraph.keep(
            binding.contextualTypeKey,
            IrBindingStack.Entry.generatedExtensionAt(
              binding.contextualTypeKey,
              node.sourceGraph.kotlinFqName.asString(),
              accessor.ir,
            ),
          )
        }

        writeDiagnostic({
          "parent-keys-used-${node.sourceGraph.name}-by-${contributedGraph.name}.txt"
        }) {
          usedContextKeys.sortedBy { it.typeKey }.joinToString(separator = "\n")
        }

        // For any key both child uses and parent has as a scoped static binding,
        // mark it as a keep in the parent graph so it materializes during seal
        for (contextKey in usedContextKeys) {
          bindingGraph.keep(contextKey, IrBindingStack.Entry.simpleTypeRef(contextKey))
          // Track that children need this context key - used by BindingPropertyCollector
          bindingGraph.reserveContextKey(contextKey)
        }
      }

      // Pop the parent graph after all contributed graphs are processed
      usedParentContextKeys = localParentContext.popParentGraph()

      // Write diagnostic for parent keys used in child graphs
      if (usedParentContextKeys.isNotEmpty()) {
        writeDiagnostic({ "parent-keys-used-all-${node.sourceGraph.name}.txt" }) {
          usedParentContextKeys.sortedBy { it.typeKey }.joinToString(separator = "\n")
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
      )

    if (sealResult.hasErrors) {
      // Return early with errors - caller will handle
      return validationResult
    }

    // Mark bindings from enclosing parents to ensure they're generated there
    // Only applicable in graph extensions
    if (parentContext != null) {
      for (key in sealResult.reachableKeys) {
        val isSelfKey =
          key == node.typeKey || key == node.metroGraph?.generatedGraphExtensionData?.typeKey
        if (!isSelfKey && key in parentContext) {
          parentContext.mark(key)
        }
      }
    }

    writeDiagnostic({
      "graph-dump-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.txt"
    }) {
      bindingGraph.dumpGraph(node.sourceGraph.kotlinFqName.asString(), short = false)
    }

    return validationResult
  }

  private fun IrBindingGraph.BindingGraphResult.reportUnusedInputs(graphDeclaration: IrClass) {
    val severity = options.unusedGraphInputsSeverity
    if (!severity.isEnabled) return

    val diagnosticFactory =
      when (severity) {
        MetroOptions.DiagnosticSeverity.WARN -> MetroDiagnostics.UNUSED_GRAPH_INPUT_WARNING
        MetroOptions.DiagnosticSeverity.ERROR -> MetroDiagnostics.UNUSED_GRAPH_INPUT_ERROR
        // Already checked above, but for exhaustive when
        MetroOptions.DiagnosticSeverity.NONE -> return
      }

    if (unusedKeys.isNotEmpty()) {
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
        reportCompat(
          irDeclarations = sequenceOf(unusedBinding.reportableDeclaration, graphDeclaration),
          factory = diagnosticFactory,
          a = message,
        )
      }
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

    traceNested(
      "[${metroGraph.kotlinFqName.shortName().asString()}] Generate graph",
      metroGraph.kotlinFqName.asString(),
    ) {
      try {
        // Generate this graph's implementation
        val bindingPropertyContext =
          IrGraphGenerator(
              metroContext = metroContext,
              traceScope = this,
              graphNodesByClass = graphNodes::get,
              node = node,
              graphClass = metroGraph,
              bindingGraph = validationResult.bindingGraph,
              sealResult = validationResult.sealResult,
              bindingContainerTransformer = bindingContainerTransformer,
              membersInjectorTransformer = membersInjectorTransformer,
              assistedFactoryTransformer = assistedFactoryTransformer,
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

    writeDiagnostic({
      "graph-dumpKotlin-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt"
    }) {
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
