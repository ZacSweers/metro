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
import dev.zacsweers.metro.compiler.ir.BindingGraphGenerator
import dev.zacsweers.metro.compiler.ir.DependencyGraphNode
import dev.zacsweers.metro.compiler.ir.DependencyGraphNodeCache
import dev.zacsweers.metro.compiler.ir.IrBindingStack
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrGraphGenerator
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.MetroIrErrors
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.irCallConstructorWithSameParameters
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.location
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.requireNestedClass
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
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
  private val processedMetroDependencyGraphsByClass = mutableMapOf<ClassId, IrClass>()

  private val dependencyGraphNodeCache =
    DependencyGraphNodeCache(this, contributionData, bindingContainerTransformer)

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

    val metroGraph =
      if (declaration.origin === Origins.ContributedGraph) {
        // If it's a contributed graph, there is no inner generated graph
        declaration
      } else {
        declaration.nestedClasses.singleOrNull { it.name == Symbols.Names.MetroGraph }
          ?: error("Expected generated dependency graph for ${declaration.classIdOrFail}")
      }

    try {
      tryTransformDependencyGraph(declaration, dependencyGraphAnno, metroGraph)
    } catch (_: ExitProcessingException) {
      // End processing, don't fail up because this would've been warned before
    }

    // TODO dump option to detect unused

    return super.visitClass(declaration)
  }

  private fun tryTransformDependencyGraph(
    dependencyGraphDeclaration: IrClass,
    dependencyGraphAnno: IrConstructorCall,
    metroGraph: IrClass,
  ) {
    val graphClassId = dependencyGraphDeclaration.classIdOrFail
    processedMetroDependencyGraphsByClass[graphClassId]?.let {
      return
    }
    if (dependencyGraphDeclaration.isExternalParent) {
      // Externally compiled, just use its generated class
      processedMetroDependencyGraphsByClass[graphClassId] = metroGraph
      return
    }

    parentTracer.traceNested(
      "Transform dependency graph",
      dependencyGraphDeclaration.kotlinFqName.shortName().asString(),
    ) { tracer ->
      transformDependencyGraph(
        graphClassId,
        dependencyGraphDeclaration,
        dependencyGraphAnno,
        metroGraph,
        tracer,
      )
    }

    processedMetroDependencyGraphsByClass[graphClassId] = metroGraph
  }

  private fun transformDependencyGraph(
    graphClassId: ClassId,
    dependencyGraphDeclaration: IrClass,
    dependencyGraphAnno: IrConstructorCall,
    metroGraph: IrClass,
    parentTracer: Tracer,
  ) {
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
          )
          .generate()
      }

    try {
      val result =
        parentTracer.traceNested("Validate binding graph") { tracer ->
          tracer.traceNested("Validate graph") {
            bindingGraph.seal(it) { errors ->
              for ((declaration, message) in errors) {
                // TODO in kotlin 2.2.20 we can just use the reporter
                val toReport =
                  declaration?.takeIf { it.fileOrNull != null } ?: dependencyGraphDeclaration
                if (toReport.fileOrNull != null) {
                  diagnosticReporter
                    .at(declaration ?: dependencyGraphDeclaration)
                    .report(MetroIrErrors.METRO_ERROR, message)
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

      writeDiagnostic({
        "graph-dump-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.txt"
      }) {
        bindingGraph.dumpGraph(node.sourceGraph.kotlinFqName.asString(), short = false)
      }

      // Check if any parents haven't been generated yet. If so, generate them now
      for (parent in node.allExtendedNodes.values) {
        if (!parent.isExtendable) continue
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

      parentTracer.traceNested("Transform metro graph") { tracer ->
        IrGraphGenerator(
            metroContext = metroContext,
            contributionData = contributionData,
            dependencyGraphNodesByClass = dependencyGraphNodeCache::get,
            node = node,
            graphClass = metroGraph,
            bindingGraph = bindingGraph,
            sealResult = result,
            parentTracer = tracer,
            bindingContainerTransformer = bindingContainerTransformer,
            membersInjectorTransformer = membersInjectorTransformer,
            assistedFactoryTransformer = assistedFactoryTransformer,
          )
          .generate()
      }
    } catch (e: Exception) {
      if (e is ExitProcessingException) {
        // Implement unimplemented overrides to reduce noise in failure output
        // Otherwise compiler may complain that these are invalid bytecode
        implementCreatorFunctions(node.sourceGraph, node.creator, node.sourceGraph.metroGraphOrFail)

        node.accessors
          .map { it.first }
          .plus(node.injectors.map { it.first })
          .plus(node.bindsCallables.map { it.function })
          .plus(node.contributedGraphs.map { it.value })
          .forEach { function ->
            with(function.ir) {
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

    processedMetroDependencyGraphsByClass[graphClassId] = metroGraph

    metroGraph.dumpToMetroLog()

    writeDiagnostic({
      "graph-dumpKotlin-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt"
    }) {
      metroGraph.dumpKotlinLike()
    }
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
      val implementFactoryFunction: IrClass.() -> Unit = {
        requireSimpleFunction(factoryCreator.function.name.asString()).owner.apply {
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

      companionObject.apply {
        if (factoryCreator.type.isInterface) {
          // Implement the interface creator function directly in this companion object
          implementFactoryFunction()
        } else {
          // Implement the factory's $$Impl class
          val factoryClass =
            factoryCreator.type
              .requireNestedClass(Symbols.Names.MetroImpl)
              .apply(implementFactoryFunction)

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
                    irCallConstructor(factoryClass.primaryConstructor!!.symbol, emptyList()),
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
