// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ExitProcessingException
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.ParentContext
import dev.zacsweers.metro.compiler.ir.BindingGraphGenerator
import dev.zacsweers.metro.compiler.ir.DependencyGraphNode
import dev.zacsweers.metro.compiler.ir.DependencyGraphNodeCache
import dev.zacsweers.metro.compiler.ir.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.IrBindingStack
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrGraphExtensionGenerator
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrGraphGenerator
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.MetroIrErrors
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.irCallConstructorWithSameParameters
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.location
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.requireNestedClass
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.transformMultiboundQualifier
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isLocal
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.ClassId

internal class DependencyGraphTransformer(
  context: IrMetroContext,
  private val contributionData: IrContributionData,
  private val parentTracer: Tracer,
  hintGenerator: HintGenerator,
) : IrElementTransformerVoid(), IrMetroContext by context {

  private val membersInjectorTransformer = MembersInjectorTransformer(context)
  private val injectConstructorTransformer =
    InjectConstructorTransformer(context, membersInjectorTransformer)
  private val assistedFactoryTransformer =
    AssistedFactoryTransformer(context, injectConstructorTransformer)
  private val bindingContainerTransformer = BindingContainerTransformer(context)
  private val contributionHintIrTransformer by unsafeLazy {
    ContributionHintIrTransformer(context, hintGenerator, injectConstructorTransformer)
  }

  // Keyed by the source declaration
  private val processedMetroDependencyGraphsByClass =
    mutableMapOf<ClassId, IrBindingGraph.BindingGraphResult?>()

  private val dependencyGraphNodeCache =
    DependencyGraphNodeCache(this, contributionData, bindingContainerTransformer)

  // Cache of precomputed seal results for contributed graphs so we can reuse them later
  private val precomputedSealResults = mutableMapOf<ClassId, IrBindingGraph.BindingGraphResult>()

  override fun visitCall(expression: IrCall): IrExpression {
    return CreateGraphTransformer.visitCall(expression, metroContext)
      ?: AsContributionTransformer.visitCall(expression, metroContext)
      ?: super.visitCall(expression)
  }

  override fun visitClass(declaration: IrClass): IrStatement {
    val shouldNotProcess =
      declaration.isLocal ||
        declaration.kind == ClassKind.ENUM_CLASS ||
        declaration.kind == ClassKind.ENUM_ENTRY
    if (shouldNotProcess) {
      return super.visitClass(declaration)
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
      declaration.annotationsIn(symbols.dependencyGraphAnnotations).singleOrNull()
        ?: return super.visitClass(declaration)

    tryProcessDependencyGraph(declaration, dependencyGraphAnno)

    // TODO dump option to detect unused

    return super.visitClass(declaration)
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
        dependencyGraphDeclaration.nestedClasses.singleOrNull {
          it.name == Symbols.Names.MetroGraph
        }
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

  private fun processDependencyGraph(
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
      IrGraphExtensionGenerator(metroContext, contributionData, node.sourceGraph.metroGraphOrFail)

    // Before validating/sealing the parent graph, analyze contributed child graphs to
    // determine any parent-scoped static bindings that are required by children and
    // add synthetic roots for them so they are materialized in the parent.
    if (node.graphExtensions.isNotEmpty()) {
      // Collect parent-available scoped binding keys to match against
      // @Binds not checked because they cannot be scoped!
      val localParentContext = parentContext ?: ParentContext()

      // @Provides
      for ((_, providerFactory) in node.providerFactories) {
        if (providerFactory.annotations.isScoped) {
          // TODO this lookup is getting duplicated a few places, would be good to isolated
          val targetKey = if (providerFactory.annotations.isIntoMultibinding) {
            providerFactory.typeKey.transformMultiboundQualifier(providerFactory.annotations)
          } else {
            providerFactory.typeKey
          }
          localParentContext.add(targetKey)
        }
      }

      // @Inject classes
      if (options.enableScopedInjectClassHints) {
        for (scope in node.scopes) {
          val classesInScope = contributionData.getScopedInjectClasses(scope)
          for (scopedClass in classesInScope) {
            // TODO look these up once somewhere and cache
            val annotations = scopedClass.type.rawType().metroAnnotations(symbols.classIds)
            val targetKey = if (annotations.isIntoMultibinding) {
              scopedClass.transformMultiboundQualifier(annotations)
            } else {
              scopedClass
            }
            localParentContext.add(targetKey)
          }
        }
      }

      // Instance bindings
      node.creator?.parameters?.let { parameters ->
        for (parameter in parameters.regularParameters) {
          if (parameter.isIncludes || parameter.isBindsInstance) {
            localParentContext.add(parameter.typeKey)
          }
        }
      }

      // Included graph dependencies
      for (included in node.allIncludedNodes) {
        localParentContext.addAll(included.publicAccessors)
      }

      // Extended graphs
      localParentContext.addAll(node.allExtendedNodes.keys)

      // Transform the contributed graphs
      // Push the parent graph for all contributed graph processing
      localParentContext.pushParentGraph(node)
      
      for ((contributedGraphKey, accessor) in node.graphExtensions) {
        val contributedExtension = contributedGraphKey.type.rawTypeOrNull() ?: continue

        // Generate the contributed graph class
        val contributedGraph =
          graphExtensionGenerator.getOrBuildContributedGraph(
            contributedGraphKey,
            node.sourceGraph,
            accessor,
            parentTracer,
          )

        // Process the child
          processDependencyGraph(
            contributedGraph,
            contributedGraph.annotationsIn(symbols.dependencyGraphAnnotations).single(),
            contributedGraph,
            localParentContext,
          )
            ?: reportCompilerBug(
              "Expected generated dependency graph for ${contributedExtension.classIdOrFail}"
            )

        // For any key both child uses and parent has as a scoped static binding,
        // mark it as a keep in the parent graph so it materializes during seal
        for (key in localParentContext.usedKeys()) {
          val contextKey = IrContextualTypeKey.create(key)
          bindingGraph.keep(contextKey, IrBindingStack.Entry.simpleTypeRef(contextKey))
        }
      }
      
      // Pop the parent graph after all contributed graphs are processed
      localParentContext.popParentGraph()
    }

    try {
      val result =
        parentTracer.traceNested("Validate binding graph") { tracer ->
          tracer.traceNested("Validate graph") {
            bindingGraph.seal(it) { errors ->
              for ((declaration, message) in errors) {
                // TODO in kotlin 2.2.20 we can just use the reporter
                val toReport =
                  declaration?.takeIf {
                    it.fileOrNull != null && it.origin != Origins.GeneratedGraphExtension
                  } ?: dependencyGraphDeclaration
                if (toReport.fileOrNull != null && toReport.origin != Origins.GeneratedGraphExtension) {
                  diagnosticReporter.at(toReport).report(MetroIrErrors.METRO_ERROR, message)
                } else {
                  messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    message,
                    toReport.location(),
                  )
                }
              }
              exitProcessing()
            }
          }
        }

      // Mark bindings from enclosing parents to ensure they're generated there
      // Only applicable in graph extensions
      if (parentContext != null) {
        for (key in result.reachableKeys) {
          if (key in parentContext) {
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
            diagnosticReporter
              .at(parent.sourceGraph)
              .report(
                MetroIrErrors.METRO_ERROR,
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
            parentTracer = tracer,
            bindingContainerTransformer = bindingContainerTransformer,
            membersInjectorTransformer = membersInjectorTransformer,
            assistedFactoryTransformer = assistedFactoryTransformer,
            contributedGraphGenerator = graphExtensionGenerator,
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
          .map { it.first.ir }
          .plus(node.injectors.map { it.first.ir })
          .plus(node.bindsCallables.map { it.callableMetadata.function })
          .plus(node.graphExtensions.map { it.value.ir })
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
      // TODO would be nice if we could just class delegate to the $$Impl object
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
                symbol,
                irCallConstructorWithSameParameters(
                  source = createFunction,
                  constructor = metroGraph.primaryConstructor!!.symbol,
                ),
              )
            }
        }
      }

      // Implement the factory's $$Impl class if present
      val factoryImpl =
        factoryCreator.type
          .requireNestedClass(Symbols.Names.MetroImpl)
          .apply(implementFactoryFunction)

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
                    symbol,
                    irCallConstructor(factoryImpl.primaryConstructor!!.symbol, emptyList()),
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
              irExprBodySafe(
                symbol,
                irCallConstructor(metroGraph.primaryConstructor!!.symbol, emptyList()),
              )
            }
        }
      }
    }

    companionObject.dumpToMetroLog()
  }
}
