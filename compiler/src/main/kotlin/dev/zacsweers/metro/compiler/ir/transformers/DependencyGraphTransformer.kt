// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ExitProcessingException
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.exitProcessing
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
import dev.zacsweers.metro.compiler.ir.graph.DependencyGraphNode
import dev.zacsweers.metro.compiler.ir.graph.DependencyGraphNodeCache
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
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.nestedClassOrNull
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireNestedClass
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.scopeAnnotations
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.transformMultiboundQualifier
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.Tracer
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
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isLocal
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.ClassId

internal class DependencyGraphTransformer(
  context: IrMetroContext,
  private val contributionData: IrContributionData,
  private val parentTracer: Tracer,
  hintGenerator: HintGenerator,
) : IrElementTransformerVoidWithContext(), TransformerContextAccess, IrMetroContext by context {

  private val membersInjectorTransformer = MembersInjectorTransformer(context)
  private val injectConstructorTransformer =
    InjectConstructorTransformer(context, membersInjectorTransformer)
  private val assistedFactoryTransformer =
    AssistedFactoryTransformer(context, injectConstructorTransformer)
  private val bindingContainerTransformer = BindingContainerTransformer(context)
  private val contributionHintIrTransformer by memoize {
    ContributionHintIrTransformer(context, hintGenerator)
  }
  private val bindingContainerResolver = IrBindingContainerResolver(metroSymbols)
  private val contributionMerger = IrContributionMerger(this, contributionData)
  private val dynamicGraphGenerator =
    IrDynamicGraphGenerator(this, bindingContainerResolver, contributionMerger)
  private val createGraphTransformer = CreateGraphTransformer(this, dynamicGraphGenerator)

  // Keyed by the source declaration
  private val processedMetroDependencyGraphsByClass =
    mutableMapOf<ClassId, IrBindingGraph.BindingGraphResult?>()

  private val dependencyGraphNodeCache =
    DependencyGraphNodeCache(this, bindingContainerTransformer, contributionMerger)

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
      ?: super.visitCall(expression)
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
    val generateHints =
      options.generateContributionHints && !options.generateJvmContributionHintsInFir
    if (generateHints) {
      contributionHintIrTransformer.visitClass(declaration)
    }
    membersInjectorTransformer.visitClass(declaration)
    injectConstructorTransformer.visitClass(declaration)
    assistedFactoryTransformer.visitClass(declaration)
    bindingContainerTransformer.findContainer(declaration)

    val dependencyGraphAnno =
      declaration.annotationsIn(metroSymbols.dependencyGraphAnnotations).singleOrNull()
        ?: return super.visitClassNew(declaration)

    tryProcessDependencyGraph(declaration, dependencyGraphAnno)

    // TODO dump option to detect unused

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

  internal fun processDependencyGraph(
    dependencyGraphDeclaration: IrClass,
    dependencyGraphAnno: IrConstructorCall,
    metroGraph: IrClass,
    parentContext: ParentContext?,
  ): IrBindingGraph.BindingGraphResult? {
    val graphClassId = dependencyGraphDeclaration.classIdOrFail
    processedMetroDependencyGraphsByClass[graphClassId]?.let {
      return it
    }
    if (dependencyGraphDeclaration.isExternalParent) {
      // Externally compiled, just use its generated class
      processedMetroDependencyGraphsByClass[graphClassId] = null
      return null
    }

    val tag = dependencyGraphDeclaration.kotlinFqName.shortName().asString()
    val result =
      parentTracer.traceNested("[$tag] Transform dependency graph", tag) { tracer ->
        transformDependencyGraph(
          graphClassId,
          dependencyGraphDeclaration,
          dependencyGraphAnno,
          metroGraph,
          tracer,
          parentContext,
        )
      }

    processedMetroDependencyGraphsByClass[graphClassId] = result
    return result
  }

  private fun transformDependencyGraph(
    graphClassId: ClassId,
    dependencyGraphDeclaration: IrClass,
    dependencyGraphAnno: IrConstructorCall,
    metroGraph: IrClass,
    parentTracer: Tracer,
    parentContext: ParentContext?,
  ): IrBindingGraph.BindingGraphResult? {
    val node =
      dependencyGraphNodeCache.getOrComputeDependencyGraphNode(
        dependencyGraphDeclaration,
        IrBindingStack(
          dependencyGraphDeclaration,
          metroContext.loggerFor(MetroLogger.Type.GraphNodeConstruction),
        ),
        parentTracer,
        metroGraph,
        dependencyGraphAnno,
      )

    // Generate creator functions
    parentTracer.traceNested("Implement creator functions") {
      implementCreatorFunctions(node.sourceGraph, node.creator, metroGraph)
    }

    val bindingGraph =
      parentTracer.traceNested("Build binding graph") {
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

    val propertyNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)

    // Preallocate any existing property and field names in this graph
    for (property in node.metroGraphOrFail.properties) {
      propertyNameAllocator.newName(property.name.asString())
    }

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
      for ((_, providerFactory) in node.providerFactories) {
        if (providerFactory.annotations.isScoped) {
          // TODO this lookup is getting duplicated a few places, would be good to isolated
          val targetKey =
            if (providerFactory.annotations.isIntoMultibinding) {
              providerFactory.typeKey.transformMultiboundQualifier(providerFactory.annotations)
            } else {
              providerFactory.typeKey
            }
          localParentContext.add(targetKey)
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
      localParentContext.pushParentGraph(node, propertyNameAllocator)

      // Second pass on graph extensions to actually process them and create GraphExtension bindings
      for ((contributedGraphKey, accessors) in node.graphExtensions) {
        val extensionAccessor = accessors.first() // Only need one for below linking

        val contributedExtension = contributedGraphKey.type.rawType()
        val accessor = extensionAccessor.accessor

        // Determine the actual graph extension type key
        val actualGraphExtensionTypeKey = contributedGraphKey

        // Generate the contributed graph class
        val contributedGraph =
          graphExtensionGenerator.getOrBuildGraphExtensionImpl(
            actualGraphExtensionTypeKey,
            node.sourceGraph,
            accessor,
            parentTracer,
          )

        // Process the child
        processDependencyGraph(
          contributedGraph,
          contributedGraph.annotationsIn(metroSymbols.dependencyGraphAnnotations).single(),
          contributedGraph,
          localParentContext,
        )
          ?: reportCompilerBug(
            "Expected generated dependency graph for ${contributedExtension.classIdOrFail}"
          )

        // Capture the used keys for this graph extension
        val usedKeys = localParentContext.usedKeys()

        if (contributedGraphKey in directExtensions) {
          val dependencies = usedKeys.map { IrContextualTypeKey.create(it) }
          val binding =
            IrBinding.GraphExtension(
              typeKey = contributedGraphKey,
              parent = node.sourceGraph,
              accessor = accessor.ir,
              extensionScopes = contributedExtension.scopeAnnotations(),
              dependencies = dependencies,
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
          usedKeys.sorted().joinToString(separator = "\n")
        }

        // For any key both child uses and parent has as a scoped static binding,
        // mark it as a keep in the parent graph so it materializes during seal
        for (key in usedKeys) {
          val contextKey = IrContextualTypeKey.create(key)
          bindingGraph.keep(contextKey, IrBindingStack.Entry.simpleTypeRef(contextKey))
          bindingGraph.reserveProperty(key, localParentContext.getPropertyAccess(key)!!)
        }
      }

      // Pop the parent graph after all contributed graphs are processed
      val usedParentKeys = localParentContext.popParentGraph()

      // Write diagnostic for parent keys used in child graphs
      if (usedParentKeys.isNotEmpty()) {
        writeDiagnostic({ "parent-keys-used-all-${node.sourceGraph.name}.txt" }) {
          usedParentKeys.sorted().joinToString(separator = "\n")
        }
      }
    }

    try {
      val result =
        parentTracer.traceNested("Validate binding graph") { tracer ->
          tracer.traceNested("Validate graph") {
            bindingGraph.seal(it) { errors ->
              for ((declaration, message) in errors) {
                reportCompat(
                  irDeclarations = sequenceOf(declaration, dependencyGraphDeclaration),
                  factory = MetroDiagnostics.METRO_ERROR,
                  a = message,
                )
              }
            }
          }
        }

      if (result.hasErrors) {
        // Return early, nothing we can do here
        return result
      }

      // Mark bindings from enclosing parents to ensure they're generated there
      // Only applicable in graph extensions
      if (parentContext != null) {
        for (key in result.reachableKeys) {
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

      // Check if any parents haven't been generated yet. If so, generate them now
      if (dependencyGraphDeclaration.origin != Origins.GeneratedGraphExtension) {
        for (parent in node.allExtendedNodes.values) {
          var proto = parent.proto
          val needsToGenerateParent =
            proto == null &&
              parent.sourceGraph.classId !in processedMetroDependencyGraphsByClass &&
              !parent.sourceGraph.isExternalParent
          if (needsToGenerateParent) {
            visitClass(parent.sourceGraph)
            proto =
              dependencyGraphNodeCache
                .requirePreviouslyComputed(parent.sourceGraph.classIdOrFail)
                .proto
          }
          if (proto == null) {
            reportCompat(
              parent.sourceGraph,
              MetroDiagnostics.METRO_ERROR,
              "Extended parent graph ${parent.sourceGraph.kotlinFqName} is missing Metro metadata. Was it compiled by the Metro compiler?",
            )
            exitProcessing()
          }
        }
      }

      // TODO split this to a separate function, call from parent generation

      parentTracer.traceNested("Transform metro graph") { tracer ->
        IrGraphGenerator(
            metroContext = metroContext,
            dependencyGraphNodesByClass = dependencyGraphNodeCache::get,
            node = node,
            graphClass = metroGraph,
            bindingGraph = bindingGraph,
            sealResult = result,
            propertyNameAllocator = propertyNameAllocator,
            parentTracer = tracer,
            bindingContainerTransformer = bindingContainerTransformer,
            membersInjectorTransformer = membersInjectorTransformer,
            assistedFactoryTransformer = assistedFactoryTransformer,
            graphExtensionGenerator = graphExtensionGenerator,
          )
          .generate()
      }

      processedMetroDependencyGraphsByClass[graphClassId] = result
    } catch (e: Exception) {
      if (e is ExitProcessingException) {
        // Implement unimplemented overrides to reduce noise in failure output
        // Otherwise compiler may complain that these are invalid bytecode
        implementCreatorFunctions(node.sourceGraph, node.creator, node.sourceGraph.metroGraphOrFail)

        node.accessors
          .map { it.metroFunction.ir }
          .plus(node.injectors.map { it.metroFunction.ir })
          .plus(node.bindsCallables.map { it.callableMetadata.function })
          .plus(node.graphExtensions.flatMap { it.value }.map { it.accessor.ir })
          .filterNot { it.isExternalParent }
          .forEach { function ->
            with(function) {
              val declarationToFinalize = propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
              if (declarationToFinalize.isFakeOverride) {
                declarationToFinalize.finalizeFakeOverride(
                  metroGraph.thisReceiverOrFail.copyTo(this)
                )
                body =
                  if (returnType != pluginContext.irBuiltIns.unitType) {
                    stubExpressionBody("Graph transform failed")
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
          "Code gen exception while processing ${dependencyGraphDeclaration.classIdOrFail}. ${e.message}",
          e,
        )
        .apply {
          // Don't fill in the stacktrace here as it's not relevant to the issue
          setStackTrace(emptyArray())
        }
    }

    metroGraph.dumpToMetroLog()

    writeDiagnostic({
      "graph-dumpKotlin-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt"
    }) {
      metroGraph.dumpKotlinLike()
    }

    // If we get here we've definitely stored a result
    return processedMetroDependencyGraphsByClass.getValue(graphClassId)
  }

  private fun implementCreatorFunctions(
    sourceGraph: IrClass,
    creator: DependencyGraphNode.Creator?,
    metroGraph: IrClass,
  ) {
    // NOTE: may not have a companion object if this graph is a contributed graph, which has no
    // static creators
    val companionObject = sourceGraph.companionObject() ?: return
    val factoryCreator = creator?.expectAsOrNull<DependencyGraphNode.Creator.Factory>()
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
