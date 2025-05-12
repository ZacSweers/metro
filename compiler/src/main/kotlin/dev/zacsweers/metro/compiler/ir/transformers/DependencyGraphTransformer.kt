// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ExitProcessingException
import dev.zacsweers.metro.compiler.METRO_VERSION
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.PLUGIN_ID
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.Binding
import dev.zacsweers.metro.compiler.ir.DependencyGraphNode
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.IrBindingStack
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrContributedGraphGenerator
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.allCallableMembers
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.appendBindingStack
import dev.zacsweers.metro.compiler.ir.asContextualTypeKey
import dev.zacsweers.metro.compiler.ir.buildBlockBody
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.doubleCheck
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.getAllSuperTypes
import dev.zacsweers.metro.compiler.ir.getConstBooleanArgumentOrNull
import dev.zacsweers.metro.compiler.ir.getSingleConstBooleanArgumentOrNull
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irLambda
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.location
import dev.zacsweers.metro.compiler.ir.metroAnnotationsOf
import dev.zacsweers.metro.compiler.ir.metroFunctionOf
import dev.zacsweers.metro.compiler.ir.metroGraph
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.requireNestedClass
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.shouldUnwrapMapKeyValues
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.stubExpression
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.tracer
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.transformMultiboundQualifier
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.ir.withEntry
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.memoized
import dev.zacsweers.metro.compiler.proto.BindsCallableId
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.trace
import dev.zacsweers.metro.compiler.tracing.traceNested
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.backend.common.lower.irThrow
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irConcat
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNotIs
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.parent
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.addArgument
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.DeepCopyIrTreeWithSymbols
import org.jetbrains.kotlin.ir.util.SymbolRemapper
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getValueArgument
import org.jetbrains.kotlin.ir.util.isFakeOverriddenFromAny
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isFromJava
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isStatic
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.isWasm
import org.jetbrains.kotlin.platform.konan.isNative

internal class DependencyGraphTransformer(
  context: IrMetroContext,
  moduleFragment: IrModuleFragment,
  private val contributionData: IrContributionData,
) : IrElementTransformerVoid(), IrMetroContext by context {

  private val membersInjectorTransformer = MembersInjectorTransformer(context)
  private val injectConstructorTransformer =
    InjectConstructorTransformer(context, membersInjectorTransformer)
  private val assistedFactoryTransformer =
    AssistedFactoryTransformer(context, injectConstructorTransformer)
  private val providesTransformer = ProvidesTransformer(context)
  private val contributionHintIrTransformer by unsafeLazy {
    ContributionHintIrTransformer(context, moduleFragment)
  }

  // Keyed by the source declaration
  private val dependencyGraphNodesByClass = mutableMapOf<ClassId, DependencyGraphNode>()

  // Keyed by the source declaration
  private val processedMetroDependencyGraphsByClass = mutableMapOf<ClassId, IrClass>()

  override fun visitCall(expression: IrCall): IrExpression {
    return CreateGraphTransformer.visitCall(expression, metroContext)
      ?: AsContributionTransformer.visitCall(expression, metroContext)
      ?: super.visitCall(expression)
  }

  override fun visitClass(declaration: IrClass): IrStatement {
    log("Reading ${declaration.kotlinFqName}")

    // TODO need to better divvy these
    // TODO can we eagerly check for known metro types and skip?
    // Native/WASM compilation hint gen can't be done until
    // https://youtrack.jetbrains.com/issue/KT-75865
    val generateHints =
      options.generateHintProperties &&
        !pluginContext.platform.isNative() &&
        !pluginContext.platform.isWasm()
    if (generateHints) {
      contributionHintIrTransformer.visitClass(declaration)
    }
    membersInjectorTransformer.visitClass(declaration)
    injectConstructorTransformer.visitClass(declaration)
    assistedFactoryTransformer.visitClass(declaration)
    providesTransformer.visitClass(declaration)

    val dependencyGraphAnno =
      declaration.annotationsIn(symbols.dependencyGraphAnnotations).singleOrNull()
    if (dependencyGraphAnno == null) return super.visitClass(declaration)

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

  private fun getOrComputeDependencyGraphNode(
    graphDeclaration: IrClass,
    bindingStack: IrBindingStack,
    parentTracer: Tracer,
    metroGraph: IrClass? = null,
    dependencyGraphAnno: IrConstructorCall? = null,
  ): DependencyGraphNode {
    val graphClassId = graphDeclaration.classIdOrFail
    dependencyGraphNodesByClass[graphClassId]?.let {
      return it
    }

    val node =
      parentTracer.traceNested("Build DependencyGraphNode") { tracer ->
        computeDependencyGraphNode(
          graphClassId,
          graphDeclaration,
          bindingStack,
          tracer,
          metroGraph,
          dependencyGraphAnno,
        )
      }

    dependencyGraphNodesByClass[graphClassId] = node

    return node
  }

  private fun computeDependencyGraphNode(
    graphClassId: ClassId,
    graphDeclaration: IrClass,
    bindingStack: IrBindingStack,
    parentTracer: Tracer,
    metroGraph: IrClass? = null,
    dependencyGraphAnno: IrConstructorCall? = null,
  ): DependencyGraphNode {
    val dependencyGraphAnno =
      dependencyGraphAnno
        ?: graphDeclaration.annotationsIn(symbols.dependencyGraphAnnotations).singleOrNull()
    val isGraph = dependencyGraphAnno != null
    val supertypes =
      graphDeclaration.getAllSuperTypes(pluginContext, excludeSelf = false).memoized()

    val accessors = mutableListOf<Pair<MetroSimpleFunction, IrContextualTypeKey>>()
    val bindsFunctions = mutableListOf<Pair<MetroSimpleFunction, IrContextualTypeKey>>()
    val scopes = mutableSetOf<IrAnnotation>()
    val providerFactories = mutableListOf<Pair<IrTypeKey, ProviderFactory>>()
    val extendedGraphNodes = mutableMapOf<IrTypeKey, DependencyGraphNode>()
    val contributedGraphs = mutableMapOf<IrTypeKey, MetroSimpleFunction>()

    val isExtendable =
      dependencyGraphAnno?.getConstBooleanArgumentOrNull(Symbols.Names.isExtendable) == true

    if (graphDeclaration.isExternalParent || !isGraph) {
      val accessorsToCheck =
        if (isGraph) {
          // It's just an external graph, just read the declared types from it
          graphDeclaration
            .metroGraph // Doesn't cover contributed graphs but they're not visible anyway
            .allCallableMembers(
              metroContext,
              excludeInheritedMembers = false,
              excludeCompanionObjectMembers = true,
            )
        } else {
          supertypes.asSequence().flatMap {
            it
              .rawType()
              .allCallableMembers(
                metroContext,
                excludeInheritedMembers = true,
                excludeCompanionObjectMembers = true,
              )
          }
        }

      accessors +=
        accessorsToCheck
          .filterNot {
            it.ir.valueParameters.isNotEmpty() ||
              it.annotations.isBinds ||
              it.annotations.isProvides ||
              it.annotations.isMultibinds
          }
          .map { it to IrContextualTypeKey.from(metroContext, it.ir) }

      // Read metadata if this is an extendable graph
      val includedGraphNodes = mutableMapOf<IrTypeKey, DependencyGraphNode>()
      var graphProto: DependencyGraphProto? = null
      if (isExtendable) {
        parentTracer.traceNested("Populate inherited graph metadata") { tracer ->
          val serialized =
            pluginContext.metadataDeclarationRegistrar.getCustomMetadataExtension(
              graphDeclaration.requireNestedClass(Symbols.Names.MetroGraph),
              PLUGIN_ID,
            )
          if (serialized == null) {
            reportError(
              "Missing metadata for extendable graph ${graphDeclaration.kotlinFqName}. Was this compiled by the Metro compiler?",
              graphDeclaration.location(),
            )
            exitProcessing()
          }

          graphProto =
            tracer.traceNested("Deserialize DependencyGraphProto") {
              val metadata = MetroMetadata.ADAPTER.decode(serialized)
              metadata.dependency_graph
            }
          if (graphProto == null) {
            reportError(
              "Missing graph data for extendable graph ${graphDeclaration.kotlinFqName}. Was this compiled by the Metro compiler?",
              graphDeclaration.location(),
            )
            exitProcessing()
          }

          // Add any provider factories
          providerFactories +=
            graphProto.provider_factory_classes
              .map { classId ->
                val clazz = pluginContext.referenceClass(ClassId.fromString(classId))!!.owner
                providesTransformer.externalProviderFactoryFor(clazz)
              }
              .map { it.typeKey to it }

          // Add any binds functions
          bindsFunctions.addAll(
            graphProto.binds_callable_ids.map { bindsCallableId ->
              val classId = ClassId.fromString(bindsCallableId.class_id)
              val callableId = CallableId(classId, bindsCallableId.callable_name.asName())

              val function =
                if (bindsCallableId.is_property) {
                  pluginContext.referenceProperties(callableId).singleOrNull()?.owner?.getter
                } else {
                  pluginContext.referenceFunctions(callableId).singleOrNull()?.owner
                }

              if (function == null) {
                val message = buildString {
                  appendLine("No function found for $callableId")
                  callableId.classId?.let {
                    pluginContext.referenceClass(it)?.let {
                      appendLine("Class dump")
                      appendLine(it.owner.dumpKotlinLike())
                    }
                  } ?: run { appendLine("No class found for $callableId") }
                }
                error(message)
              }

              val metroFunction = metroFunctionOf(function)
              metroFunction to IrContextualTypeKey.from(this, function)
            }
          )

          // Read scopes from annotations
          // We copy scope annotations from parents onto this graph if it's extendable so we only
          // need
          // to copy once
          scopes.addAll(graphDeclaration.scopeAnnotations())

          includedGraphNodes.putAll(
            // TODO dedupe logic with below
            graphProto.included_classes.associate { graphClassId ->
              val clazz =
                pluginContext.referenceClass(ClassId.fromString(graphClassId))
                  ?: error("Could not find graph class $graphClassId.")
              val typeKey = IrTypeKey(clazz.defaultType)
              val node = getOrComputeDependencyGraphNode(clazz.owner, bindingStack, parentTracer)
              typeKey to node
            }
          )

          extendedGraphNodes.putAll(
            graphProto.parent_graph_classes.associate { graphClassId ->
              val clazz =
                pluginContext.referenceClass(ClassId.fromString(graphClassId))
                  ?: error("Could not find graph class $graphClassId.")
              val typeKey = IrTypeKey(clazz.defaultType)
              val node = getOrComputeDependencyGraphNode(clazz.owner, bindingStack, parentTracer)
              typeKey to node
            }
          )
        }
      }

      // TODO split DependencyGraphNode into sealed interface with external/internal variants?
      val dependentNode =
        DependencyGraphNode(
          sourceGraph = graphDeclaration,
          isExtendable = isExtendable,
          includedGraphNodes = includedGraphNodes,
          scopes = scopes,
          providerFactories = providerFactories,
          accessors = accessors,
          bindsFunctions = bindsFunctions,
          isExternal = true,
          proto = graphProto,
          extendedGraphNodes = extendedGraphNodes,
          // Following aren't necessary to see in external graphs
          contributedGraphs = contributedGraphs,
          injectors = emptyList(),
          creator = null,
        )

      dependencyGraphNodesByClass[graphClassId] = dependentNode

      return dependentNode
    }

    val nonNullMetroGraph = metroGraph ?: graphDeclaration.metroGraph
    val graphTypeKey = IrTypeKey(graphDeclaration.typeWith())
    val graphContextKey = IrContextualTypeKey.create(graphTypeKey)

    val injectors = mutableListOf<Pair<MetroSimpleFunction, IrTypeKey>>()

    for (declaration in nonNullMetroGraph.declarations) {
      if (!declaration.isFakeOverride) continue
      if (declaration is IrFunction && declaration.isFakeOverriddenFromAny()) continue
      val annotations = metroAnnotationsOf(declaration)
      if (annotations.isProvides) continue
      when (declaration) {
        is IrSimpleFunction -> {
          // Could be an injector, accessor, or contributed graph
          var isContributedGraph = false

          // If the overridden symbol has a default getter/value then skip
          var hasDefaultImplementation = false
          for (overridden in declaration.overriddenSymbolsSequence()) {
            if (overridden.owner.body != null) {
              hasDefaultImplementation = true
              break
            } else if (
              overridden.owner.parentClassOrNull?.isAnnotatedWithAny(
                symbols.classIds.contributesGraphExtensionFactoryAnnotations
              ) == true
            ) {
              isContributedGraph = true
              break
            }
          }
          if (hasDefaultImplementation) continue

          val isInjector =
            !isContributedGraph &&
              declaration.valueParameters.size == 1 &&
              !annotations.isBinds &&
              declaration.returnType.isUnit()
          if (isContributedGraph) {
            val metroFunction = metroFunctionOf(declaration, annotations)
            val contextKey = IrContextualTypeKey.from(this, declaration)
            contributedGraphs[contextKey.typeKey] = metroFunction
          } else if (isInjector) {
            // It's an injector
            val metroFunction = metroFunctionOf(declaration, annotations)
            // key is the injected type wrapped in MembersInjector
            val typeKey =
              IrTypeKey(symbols.metroMembersInjector.typeWith(declaration.valueParameters[0].type))
            injectors += (metroFunction to typeKey)
          } else {
            // Accessor or binds
            val metroFunction = metroFunctionOf(declaration, annotations)
            val contextKey = IrContextualTypeKey.from(this, declaration)
            val collection =
              if (metroFunction.annotations.isBinds) {
                bindsFunctions
              } else {
                accessors
              }
            collection += (metroFunction to contextKey)
          }
        }

        is IrProperty -> {
          // Can only be an accessor, binds, or contributed graph
          var isContributedGraph = false

          // If the overridden symbol has a default getter/value then skip
          var hasDefaultImplementation = false
          for (overridden in declaration.overriddenSymbolsSequence()) {
            if (overridden.owner.getter?.body != null) {
              hasDefaultImplementation = true
              break
            } else if (
              overridden.owner.parentClassOrNull?.isAnnotatedWithAny(
                symbols.classIds.contributesGraphExtensionFactoryAnnotations
              ) == true
            ) {
              isContributedGraph = true
              break
            }
          }
          if (hasDefaultImplementation) continue

          val getter = declaration.getter!!
          val metroFunction = metroFunctionOf(getter, annotations)
          val contextKey = IrContextualTypeKey.from(this, getter)
          if (isContributedGraph) {
            contributedGraphs[contextKey.typeKey] = metroFunction
          } else {
            val collection =
              if (metroFunction.annotations.isBinds) {
                bindsFunctions
              } else {
                accessors
              }
            collection += (metroFunction to contextKey)
          }
        }
      }
    }

    scopes += buildSet {
      val scope =
        dependencyGraphAnno.getValueArgument(Symbols.Names.scope)?.let { scopeArg ->
          pluginContext.createIrBuilder(graphDeclaration.symbol).run {
            irCall(symbols.metroSingleInConstructor).apply { putValueArgument(0, scopeArg) }
          }
        }

      if (scope != null) {
        add(IrAnnotation(scope))
        dependencyGraphAnno
          .getValueArgument(Symbols.Names.additionalScopes)
          ?.expectAs<IrVararg>()
          ?.elements
          ?.forEach { scopeArg ->
            val scopeClassExpression = scopeArg.expectAs<IrExpression>()
            val newAnno =
              pluginContext.createIrBuilder(graphDeclaration.symbol).run {
                irCall(symbols.metroSingleInConstructor).apply {
                  putValueArgument(0, scopeClassExpression)
                }
              }
            add(IrAnnotation(newAnno))
          }
      }
    }

    val scopesOnType = graphDeclaration.scopeAnnotations()
    scopes += scopesOnType
    for ((i, type) in supertypes.withIndex()) {
      val clazz = type.classOrFail.owner

      if (i != 0) {
        // We don't need to do a double lookup
        scopes += clazz.scopeAnnotations()
      }

      providerFactories += providesTransformer.factoryClassesFor(clazz)
    }

    if (isExtendable) {
      // Copy inherited scopes onto this graph for faster lookups downstream
      // Note this is only for scopes inherited from supertypes, not from extended parent graphs
      val inheritedScopes = (scopes - scopesOnType).map { it.ir }
      if (graphDeclaration.origin === Origins.ContributedGraph) {
        // If it's a contributed graph, just add it directly as these are not visible to metadata
        // anyway
        graphDeclaration.annotations += inheritedScopes
      } else {
        pluginContext.metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(
          graphDeclaration,
          inheritedScopes,
        )
      }
    }

    val creator =
      if (graphDeclaration.origin === Origins.ContributedGraph) {
        val ctor = graphDeclaration.primaryConstructor!!
        val ctorParams = ctor.parameters(metroContext)
        DependencyGraphNode.Creator.Constructor(graphDeclaration.primaryConstructor!!, ctorParams)
      } else {
        // TODO since we already check this in FIR can we leave a more specific breadcrumb somewhere
        graphDeclaration.nestedClasses
          .singleOrNull { klass ->
            klass.isAnnotatedWithAny(symbols.dependencyGraphFactoryAnnotations)
          }
          ?.let { factory ->
            // Validated in FIR so we can assume we'll find just one here
            val createFunction = factory.singleAbstractFunction(this)
            DependencyGraphNode.Creator.Factory(
              factory,
              createFunction,
              createFunction.parameters(this),
            )
          }
      }

    try {
      checkGraphSelfCycle(graphDeclaration, graphTypeKey, bindingStack)
    } catch (e: ExitProcessingException) {
      implementCreatorFunctions(graphDeclaration, creator, nonNullMetroGraph)
      throw e
    }

    val includedGraphNodes = mutableMapOf<IrTypeKey, DependencyGraphNode>()
    creator
      ?.parameters
      ?.valueParameters
      .orEmpty()
      .filter { it.isIncludes || it.isExtends }
      .forEach {
        val type = it.typeKey.type.rawType()
        val node =
          bindingStack.withEntry(
            IrBindingStack.Entry.requestedAt(graphContextKey, creator!!.function)
          ) {
            getOrComputeDependencyGraphNode(type, bindingStack, parentTracer)
          }
        if (it.isExtends) {
          extendedGraphNodes[it.typeKey] = node
        } else {
          // it.isIncludes
          includedGraphNodes[it.typeKey] = node
        }
      }

    val dependencyGraphNode =
      DependencyGraphNode(
        sourceGraph = graphDeclaration,
        isExtendable = isExtendable,
        includedGraphNodes = includedGraphNodes,
        contributedGraphs = contributedGraphs,
        scopes = scopes,
        bindsFunctions = bindsFunctions,
        providerFactories = providerFactories,
        accessors = accessors,
        injectors = injectors,
        isExternal = false,
        creator = creator,
        extendedGraphNodes = extendedGraphNodes,
        typeKey = graphTypeKey,
      )

    // Check after creating a node for access to recursive allDependencies
    val overlapErrors = mutableSetOf<String>()
    val seenAncestorScopes = mutableMapOf<IrAnnotation, DependencyGraphNode>()
    for (depNode in dependencyGraphNode.allExtendedNodes.values) {
      // If any intersect, report an error to onError with the intersecting types (including
      // which parent it is coming from)
      val overlaps = scopes.intersect(depNode.scopes)
      if (overlaps.isNotEmpty()) {
        for (overlap in overlaps) {
          overlapErrors +=
            "- ${overlap.render(short = false)} (from ancestor '${depNode.sourceGraph.kotlinFqName}')"
        }
      }
      for (parentScope in depNode.scopes) {
        seenAncestorScopes.put(parentScope, depNode)?.let { previous ->
          graphDeclaration.reportError(
            buildString {
              appendLine(
                "Graph extensions (@Extends) may not have multiple ancestors with the same scopes:"
              )
              appendLine("Scope: ${parentScope.render(short = false)}")
              appendLine("Ancestor 1: ${previous.sourceGraph.kotlinFqName}")
              appendLine("Ancestor 2: ${depNode.sourceGraph.kotlinFqName}")
            }
          )
          exitProcessing()
        }
      }
    }
    if (overlapErrors.isNotEmpty()) {
      graphDeclaration.reportError(
        buildString {
          appendLine(
            "Graph extensions (@Extends) may not have overlapping scopes with its ancestor graphs but the following scopes overlap:"
          )
          for (overlap in overlapErrors) {
            appendLine(overlap)
          }
        }
      )
      exitProcessing()
    }

    return dependencyGraphNode
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

    val tracer =
      tracer(
        dependencyGraphDeclaration.kotlinFqName.shortName().asString(),
        "Transform dependency graph",
      )
    tracer.trace { tracer ->
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
      getOrComputeDependencyGraphNode(
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

    val bindingGraph = parentTracer.traceNested("Build binding graph") { createBindingGraph(node) }

    try {
      val result =
        parentTracer.traceNested("Validate binding graph") { tracer ->
          tracer.traceNested("Check self-cycles") {
            checkGraphSelfCycle(
              dependencyGraphDeclaration,
              node.typeKey,
              IrBindingStack(node.sourceGraph, loggerFor(MetroLogger.Type.CycleDetection)),
            )
          }

          tracer.traceNested("Validate graph") {
            bindingGraph.validate(it) { errors ->
              for ((declaration, message) in errors) {
                (declaration ?: dependencyGraphDeclaration).reportError(message)
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

      parentTracer.traceNested("Transform metro graph") { tracer ->
        generateMetroGraph(node, metroGraph, bindingGraph, result, tracer)
      }
    } catch (e: Exception) {
      if (e is ExitProcessingException) {
        throw e
      }
      throw AssertionError(
        "Code gen exception while processing ${dependencyGraphDeclaration.classIdOrFail}. ${e.message}",
        e,
      )
    }

    processedMetroDependencyGraphsByClass[graphClassId] = metroGraph

    metroGraph.dumpToMetroLog()

    writeDiagnostic({
      "graph-dumpKotlin-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt"
    }) {
      metroGraph.dumpKotlinLike()
    }
  }

  private fun checkGraphSelfCycle(
    graphDeclaration: IrClass,
    graphTypeKey: IrTypeKey,
    bindingStack: IrBindingStack,
  ) {
    if (bindingStack.entryFor(graphTypeKey) != null) {
      // TODO dagger doesn't appear to error for this case to model off of
      val message = buildString {
        if (bindingStack.entries.size == 1) {
          // If there's just one entry, specify that it's a self-referencing cycle for clarity
          appendLine(
            "[Metro/GraphDependencyCycle] Graph dependency cycle detected! The below graph depends on itself."
          )
        } else {
          appendLine("[Metro/GraphDependencyCycle] Graph dependency cycle detected!")
        }
        appendBindingStack(bindingStack, short = false)
      }
      graphDeclaration.reportError(message)
      exitProcessing()
    }
  }

  private fun createBindingGraph(node: DependencyGraphNode): IrBindingGraph {
    val graph =
      IrBindingGraph(
        this,
        newBindingStack = {
          IrBindingStack(node.sourceGraph, loggerFor(MetroLogger.Type.BindingGraphConstruction))
        },
      )

    // Add explicit bindings from @Provides methods
    val bindingStack =
      IrBindingStack(
        node.sourceGraph,
        metroContext.loggerFor(MetroLogger.Type.BindingGraphConstruction),
      )

    // Add instance parameters
    val graphInstanceBinding =
      Binding.BoundInstance(
        node.typeKey,
        "${node.sourceGraph.name}Provider",
        node.sourceGraph.location(),
      )
    graph.addBinding(node.typeKey, graphInstanceBinding, bindingStack)
    // Add aliases for all its supertypes
    // TODO dedupe supertype iteration
    for (superType in node.sourceGraph.getAllSuperTypes(pluginContext, excludeSelf = true)) {
      val superTypeKey = IrTypeKey(superType)
      graph.addBinding(
        superTypeKey,
        Binding.Alias(
          superTypeKey,
          node.typeKey,
          null,
          Parameters.empty(),
          MetroAnnotations.none(),
        ),
        bindingStack,
      )
    }
    node.creator?.parameters?.valueParameters.orEmpty().forEach { creatorParam ->
      // Only expose the binding if it's a bound instance or extended graph. Included containers are
      // not directly available
      if (creatorParam.isBindsInstance || creatorParam.isExtends) {
        val paramTypeKey = creatorParam.typeKey
        graph.addBinding(paramTypeKey, Binding.BoundInstance(creatorParam), bindingStack)

        if (creatorParam.isExtends) {
          val parentType = paramTypeKey.type.rawType()
          // If it's a contributed graph, add an alias for the parent types since that's what
          // bindings will look for. i.e. $$ContributedLoggedInGraph -> LoggedInGraph + supertypes
          // TODO for chained children, how will they know this?
          // TODO dedupe supertype iteration
          for (superType in parentType.getAllSuperTypes(pluginContext, excludeSelf = true)) {
            val parentTypeKey = IrTypeKey(superType)
            graph.addBinding(
              parentTypeKey,
              Binding.Alias(
                parentTypeKey,
                paramTypeKey,
                null,
                Parameters.empty(),
                MetroAnnotations.none(),
              ),
              bindingStack,
            )
          }
        }
      }
    }

    val providerFactoriesToAdd = buildList {
      addAll(node.providerFactories)
      addAll(
        node.allExtendedNodes.flatMap { (_, extendedNode) ->
          extendedNode.providerFactories.filterNot {
            // Do not include scoped providers as these should _only_ come from this graph
            // instance
            it.second.annotations.isScoped
          }
        }
      )
    }

    providerFactoriesToAdd.forEach { (typeKey, providerFactory) ->
      // Track a lookup of the provider class for IC
      trackClassLookup(node.sourceGraph, providerFactory.clazz)

      val contextKey =
        if (providerFactory.annotations.isIntoMultibinding) {
          IrContextualTypeKey.create(
            typeKey.transformMultiboundQualifier(metroContext, providerFactory.annotations)
          )
        } else {
          IrContextualTypeKey.create(typeKey)
        }
      val provider =
        Binding.Provided(
          providerFactory = providerFactory,
          contextualTypeKey = contextKey,
          parameters = providerFactory.parameters,
          annotations = providerFactory.annotations,
        )

      if (provider.isIntoMultibinding) {
        val originalQualifier = providerFactory.providesFunction.qualifierAnnotation()
        graph
          .getOrCreateMultibinding(
            pluginContext = pluginContext,
            annotations = providerFactory.annotations,
            contextKey = contextKey,
            declaration = providerFactory.providesFunction,
            originalQualifier = originalQualifier,
            bindingStack = bindingStack,
          )
          .sourceBindings
          .add(contextKey.typeKey)
      }

      graph.addBinding(contextKey.typeKey, provider, bindingStack)
    }

    val accessorsToAdd = buildList {
      addAll(node.accessors)
      addAll(
        node.allExtendedNodes.flatMap { (_, extendedNode) ->
          // Pass down @Multibinds declarations in the same way we do for multibinding providers
          extendedNode.accessors.filter { it.first.annotations.isMultibinds }
        }
      )
    }

    accessorsToAdd.forEach { (getter, contextualTypeKey) ->
      val multibinds = getter.annotations.multibinds
      val isMultibindingDeclaration = multibinds != null

      if (isMultibindingDeclaration) {
        if (contextualTypeKey.typeKey !in graph) {
          val multibinding =
            Binding.Multibinding.fromMultibindsDeclaration(
              metroContext,
              getter,
              multibinds,
              contextualTypeKey,
            )
          graph.addBinding(contextualTypeKey.typeKey, multibinding, bindingStack)
        } else {
          // If it's already in the graph, ensure its allowEmpty is up to date and update its
          // location
          val allowEmpty = multibinds.ir.getSingleConstBooleanArgumentOrNull() ?: false
          graph
            .requireBinding(contextualTypeKey.typeKey, bindingStack)
            .expectAs<Binding.Multibinding>()
            .let {
              it.allowEmpty = allowEmpty
              it.declaration = getter.ir
            }
        }
      } else {
        graph.addAccessor(
          contextualTypeKey,
          IrBindingStack.Entry.requestedAt(contextualTypeKey, getter.ir),
        )
      }
    }

    // Add bindings from graph dependencies
    // TODO dedupe this allDependencies iteration with graph gen
    // TODO try to make accessors in this single-pass
    node.allIncludedNodes.forEach { depNode ->
      val accessorNames =
        depNode.proto?.provider_field_names?.toSet().orEmpty() +
          depNode.proto?.instance_field_names?.toSet().orEmpty()
      // Only add accessors for included types. If they're an accessor to a scoped provider, they
      // will be handled by the provider field accessor later
      for ((getter, contextualTypeKey) in depNode.accessors) {
        val name = getter.ir.name.asString()
        if (name.removeSuffix(Symbols.StringNames.METRO_ACCESSOR) in accessorNames) {
          // We'll handle this farther down
          continue
        }
        graph.addBinding(
          contextualTypeKey.typeKey,
          Binding.GraphDependency(
            graph = depNode.sourceGraph,
            getter = getter.ir,
            isProviderFieldAccessor = false,
            typeKey = contextualTypeKey.typeKey,
          ),
          bindingStack,
        )
        // Record a lookup for IC
        trackFunctionCall(node.sourceGraph, getter.ir)
      }
    }

    node.allExtendedNodes.forEach { (_, depNode) ->
      if (depNode.isExtendable && depNode.proto != null) {
        val providerFieldAccessorsByName = mutableMapOf<Name, MetroSimpleFunction>()
        val instanceFieldAccessorsByName = mutableMapOf<Name, MetroSimpleFunction>()

        val providerFieldsSet = depNode.proto.provider_field_names.toSet()
        val instanceFieldsSet = depNode.proto.instance_field_names.toSet()

        val graphImpl = depNode.sourceGraph.metroGraph
        for (accessor in graphImpl.functions) {
          // TODO exclude toString/equals/hashCode or use marker annotation?
          when (accessor.name.asString().removeSuffix(Symbols.StringNames.METRO_ACCESSOR)) {
            in providerFieldsSet -> {
              val metroFunction = metroFunctionOf(accessor)
              providerFieldAccessorsByName[metroFunction.ir.name] = metroFunction
            }
            in instanceFieldsSet -> {
              val metroFunction = metroFunctionOf(accessor)
              instanceFieldAccessorsByName[metroFunction.ir.name] = metroFunction
            }
          }
        }

        depNode.proto.provider_field_names.forEach { providerField ->
          val accessor =
            providerFieldAccessorsByName.getValue(
              "${providerField}${Symbols.StringNames.METRO_ACCESSOR}".asName()
            )
          val contextualTypeKey = IrContextualTypeKey.from(this, accessor.ir)
          val existingBinding = graph.findBinding(contextualTypeKey.typeKey)
          if (existingBinding != null) {
            // If it's a graph type we can just proceed, can happen with common ancestors
            val rawType = existingBinding.typeKey.type.rawTypeOrNull()
            if (rawType?.annotationsIn(symbols.dependencyGraphAnnotations).orEmpty().any()) {
              return@forEach
            }
          }
          graph.addBinding(
            contextualTypeKey.typeKey,
            Binding.GraphDependency(
              graph = depNode.sourceGraph,
              getter = accessor.ir,
              isProviderFieldAccessor = true,
              typeKey = contextualTypeKey.typeKey,
            ),
            bindingStack,
          )
          // Record a lookup for IC
          trackFunctionCall(node.sourceGraph, accessor.ir)
        }

        depNode.proto.instance_field_names.forEach { instanceField ->
          val accessor =
            instanceFieldAccessorsByName.getValue(
              "${instanceField}${Symbols.StringNames.METRO_ACCESSOR}".asName()
            )
          val contextualTypeKey = IrContextualTypeKey.from(this, accessor.ir)
          val existingBinding = graph.findBinding(contextualTypeKey.typeKey)
          if (existingBinding != null) {
            // If it's a graph type we can just proceed, can happen with common ancestors
            val rawType = existingBinding.typeKey.type.rawTypeOrNull()
            if (rawType?.annotationsIn(symbols.dependencyGraphAnnotations).orEmpty().any()) {
              return@forEach
            }
          }
          graph.addBinding(
            contextualTypeKey.typeKey,
            Binding.GraphDependency(
              graph = depNode.sourceGraph,
              getter = accessor.ir,
              isProviderFieldAccessor = true,
              typeKey = contextualTypeKey.typeKey,
            ),
            bindingStack,
          )
          // Record a lookup for IC
          trackFunctionCall(node.sourceGraph, accessor.ir)
        }
      }
    }

    // Add MembersInjector bindings defined on injector functions
    node.injectors.forEach { (injector, typeKey) ->
      val contextKey = IrContextualTypeKey(typeKey)
      val entry = IrBindingStack.Entry.requestedAt(contextKey, injector.ir)

      graph.addInjector(typeKey, entry)
      bindingStack.withEntry(entry) {
        val targetClass = injector.ir.valueParameters.single().type.rawType()
        val generatedInjector = membersInjectorTransformer.getOrGenerateInjector(targetClass)
        val allParams = generatedInjector?.injectFunctions?.values?.toList().orEmpty()
        val parameters =
          when (allParams.size) {
            0 -> Parameters.empty()
            1 -> allParams.first()
            else -> allParams.reduce { current, next -> current.mergeValueParametersWith(next) }
          }

        val binding =
          Binding.MembersInjected(
            contextKey,
            // Need to look up the injector class and gather all params
            parameters =
              Parameters(injector.callableId, null, null, parameters.valueParameters, null),
            reportableLocation = injector.ir.location(),
            function = injector.ir,
            isFromInjectorFunction = true,
            targetClassId = targetClass.classIdOrFail,
          )

        graph.addBinding(typeKey, binding, bindingStack)
      }
    }

    // Add aliases ("@Binds"). Important this runs last so it can resolve aliases
    val bindsFunctionsToAdd = buildList {
      addAll(node.bindsFunctions)
      // Exclude scoped Binds, those will be exposed via provider field accessor
      addAll(node.allExtendedNodes.values.filter { it.isExtendable }.flatMap { it.bindsFunctions })
    }
    bindsFunctionsToAdd.forEach { (bindingCallable, initialContextKey) ->
      val annotations = bindingCallable.annotations
      val parameters = bindingCallable.ir.parameters(metroContext)
      // TODO what about T -> T but into multibinding
      val bindsImplType =
        if (annotations.isBinds) {
          parameters.extensionOrFirstParameter?.contextualTypeKey
            ?: error(
              "Missing receiver parameter for @Binds function: ${bindingCallable.ir.dumpKotlinLike()} in class ${bindingCallable.ir.parentAsClass.classId}"
            )
        } else {
          null
        }

      val contextKey =
        if (annotations.isIntoMultibinding) {
          IrContextualTypeKey.create(
            initialContextKey.typeKey.transformMultiboundQualifier(metroContext, annotations)
          )
        } else {
          initialContextKey
        }

      val binding =
        Binding.Alias(
          contextKey.typeKey,
          bindsImplType!!.typeKey,
          bindingCallable.ir,
          parameters,
          annotations,
        )

      if (annotations.isIntoMultibinding) {
        graph
          .getOrCreateMultibinding(
            pluginContext,
            annotations,
            contextKey,
            bindingCallable.ir,
            annotations.qualifier,
            bindingStack,
          )
          .sourceBindings
          .add(binding.typeKey)
      }

      graph.addBinding(binding.typeKey, binding, bindingStack)
    }

    // Don't eagerly create bindings for injectable types, they'll be created on-demand
    // when dependencies are analyzed
    // TODO collect unused bindings?

    return graph
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
                irCall(metroGraph.primaryConstructor!!.symbol).apply {
                  for (param in createFunction.valueParameters) {
                    putValueArgument(param.index, irGet(param))
                  }
                },
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

  private fun generateMetroGraph(
    node: DependencyGraphNode,
    graphClass: IrClass,
    bindingGraph: IrBindingGraph,
    sealResult: IrBindingGraph.BindingGraphResult,
    parentTracer: Tracer,
  ) =
    with(graphClass) {
      val ctor = primaryConstructor!!

      // Fields for providers. May include both scoped and unscoped providers as well as bound
      // instances
      val providerFields = mutableMapOf<IrTypeKey, IrField>()
      val multibindingProviderFields = mutableMapOf<Binding.Provided, IrField>()
      val fieldNameAllocator = dev.zacsweers.metro.compiler.NameAllocator()
      val extraConstructorStatements = mutableListOf<IrBuilderWithScope.() -> IrStatement>()

      // Fields for this graph and other instance params
      val instanceFields = mutableMapOf<IrTypeKey, IrField>()

      node.creator?.let { creator ->
        for ((i, param) in creator.parameters.valueParameters.withIndex()) {
          val isBindsInstance = param.isBindsInstance

          // TODO if we copy the annotations over in FIR we can skip this creator lookup all
          //  together
          val irParam = ctor.valueParameters[i]

          val addBoundInstanceField: (initializer: IrBuilderWithScope.() -> IrExpression) -> Unit =
            { initializer ->
              providerFields[param.typeKey] =
                addField(
                    fieldName = fieldNameAllocator.newName("${param.name}InstanceProvider"),
                    fieldType = symbols.metroProvider.typeWith(param.type),
                    fieldVisibility = DescriptorVisibilities.PRIVATE,
                  )
                  .apply {
                    isFinal = true
                    this.initializer =
                      pluginContext.createIrBuilder(symbol).run {
                        irExprBody(instanceFactory(param.type, initializer()))
                      }
                  }
            }
          if (isBindsInstance) {
            addBoundInstanceField { irGet(irParam) }
          } else {
            // It's a graph dep. Add all its accessors as available keys and point them at
            // this constructor parameter for provider field initialization
            val graphDep =
              node.includedGraphNodes[param.typeKey]
                ?: node.extendedGraphNodes[param.typeKey]
                ?: error("Undefined graph node ${param.typeKey}")
            instanceFields[graphDep.typeKey] =
              addSimpleInstanceField(
                fieldNameAllocator.newName(
                  graphDep.sourceGraph.name.asString().decapitalizeUS() + "Instance"
                ),
                graphDep.typeKey.type,
                { irGet(irParam) },
              )
            if (graphDep.isExtendable) {
              // Extended graphs
              addBoundInstanceField { irGet(irParam) }
              // Check that the input parameter is an instance of the metrograph class
              // Only do this for $$MetroGraph instances. Not necessary for ContributedGraphs
              if (graphDep.sourceGraph != graphClass) {
                val depMetroGraph = graphDep.sourceGraph.metroGraph
                extraConstructorStatements.add {
                  irIfThen(
                    condition = irNotIs(irGet(irParam), depMetroGraph.defaultType),
                    type = pluginContext.irBuiltIns.unitType,
                    thenPart =
                      irThrow(
                        irInvoke(
                          callee = context.irBuiltIns.illegalArgumentExceptionSymbol,
                          args =
                            listOf(
                              irConcat().apply {
                                addArgument(
                                  irString(
                                    "Constructor parameter ${irParam.name} _must_ be a Metro-compiler-generated instance of ${graphDep.sourceGraph.kotlinFqName.asString()} but was "
                                  )
                                )
                                addArgument(
                                  irInvoke(
                                    dispatchReceiver = irGet(irParam),
                                    callee = context.irBuiltIns.memberToString,
                                  )
                                )
                              }
                            ),
                        )
                      ),
                  )
                }
              }
            }
          }
        }
      }

      val thisReceiverParameter = thisReceiverOrFail
      val thisGraphField =
        addSimpleInstanceField(
          fieldNameAllocator.newName("thisGraphInstance"),
          node.typeKey.type,
          { irGet(thisReceiverParameter) },
        )

      instanceFields[node.typeKey] = thisGraphField
      // Add convenience mappings for all supertypes to this field so
      // instance providers from inherited types use this instance
      for (superType in node.sourceGraph.getAllSuperTypes(pluginContext)) {
        instanceFields[IrTypeKey(superType)] = thisGraphField
      }

      // Expose the graph as a provider field
      providerFields[node.typeKey] =
        addField(
            fieldName =
              fieldNameAllocator.newName(
                "${node.sourceGraph.name.asString().decapitalizeUS()}Provider"
              ),
            fieldType = symbols.metroProvider.typeWith(node.typeKey.type),
            fieldVisibility = DescriptorVisibilities.PRIVATE,
          )
          .apply {
            isFinal = true
            initializer =
              pluginContext.createIrBuilder(symbol).run {
                irExprBody(
                  instanceFactory(
                    node.typeKey.type,
                    irGetField(irGet(thisReceiverParameter), thisGraphField),
                  )
                )
              }
          }

      // Add instance fields for all the parent graphs
      for (parent in node.allExtendedNodes.values) {
        if (!parent.isExtendable) continue
        // Check if this parent hasn't been generated yet. If so, generate it now
        var proto = parent.proto
        val needsToGenerateParent =
          proto == null &&
            parent.sourceGraph.classId !in processedMetroDependencyGraphsByClass &&
            !parent.sourceGraph.isExternalParent
        if (needsToGenerateParent) {
          visitClass(parent.sourceGraph)
          proto = dependencyGraphNodesByClass.getValue(parent.sourceGraph.classIdOrFail).proto
        }
        if (proto == null) {
          reportError(
            "Extended parent graph ${parent.sourceGraph.kotlinFqName} is missing Metro metadata. Was it compiled by the Metro compiler?"
          )
          exitProcessing()
        }
        val parentMetroGraph = parent.sourceGraph.metroGraph
        val instanceAccessorNames = proto.instance_field_names.toSet()
        val instanceAccessors =
          parentMetroGraph.functions
            .filter {
              it.name.asString().removeSuffix(Symbols.StringNames.METRO_ACCESSOR) in
                instanceAccessorNames
            }
            .map {
              val metroFunction = metroFunctionOf(it)
              val contextKey = IrContextualTypeKey.from(metroContext, it)
              metroFunction to contextKey
            }
        for ((accessor, contextualTypeKey) in instanceAccessors) {
          instanceFields.getOrPut(contextualTypeKey.typeKey) {
            addField(
                fieldName =
                  fieldNameAllocator.newName(
                    contextualTypeKey.typeKey.type.rawType().name.asString().decapitalizeUS() +
                      "Instance"
                  ),
                fieldType = contextualTypeKey.typeKey.type,
                fieldVisibility = DescriptorVisibilities.PRIVATE,
              )
              .apply {
                isFinal = true
                initializer =
                  pluginContext.createIrBuilder(symbol).run {
                    val receiverTypeKey =
                      accessor.ir.dispatchReceiverParameter!!
                        .type
                        .let {
                          val rawType = it.rawTypeOrNull()
                          // This stringy check is unfortunate but origins are not visible
                          // across compilation boundaries
                          if (rawType?.name == Symbols.Names.MetroGraph) {
                            // if it's a $$MetroGraph, we actually want the parent type
                            rawType.parentAsClass.defaultType
                          } else {
                            it
                          }
                        }
                        .let(::IrTypeKey)
                    irExprBody(
                      irInvoke(
                        dispatchReceiver =
                          irGetField(
                            irGet(thisReceiverParameter),
                            instanceFields.getValue(receiverTypeKey),
                          ),
                        callee = accessor.ir.symbol,
                        typeHint = accessor.ir.returnType,
                      )
                    )
                  }
              }
          }
        }
      }

      // Track a stack for bindings
      val bindingStack =
        IrBindingStack(node.sourceGraph, metroContext.loggerFor(MetroLogger.Type.GraphImplCodeGen))

      // First pass: collect bindings and their dependencies for provider field ordering
      // Note we do this in two passes rather than keep a TreeMap because otherwise we'd be doing
      // dependency lookups at each insertion
      val bindingDependencies =
        parentTracer.traceNested("Collect bindings") {
          collectBindings(node, bindingGraph, bindingStack)
        }

      // Compute safe initialization order
      val initOrder =
        sealResult.sortedKeys.mapNotNull { bindingDependencies[it] }.distinctBy { it.typeKey }

      val baseGenerationContext =
        GraphGenerationContext(
          bindingGraph,
          thisReceiverParameter,
          instanceFields,
          providerFields,
          multibindingProviderFields,
          bindingStack,
        )

      for ((key, binding) in bindingGraph.bindingsSnapshot()) {
        if (binding is Binding.GraphDependency) {

          val getter = binding.getter
          if (binding.isProviderFieldAccessor) {
            // Init a provider field pointing at this
            providerFields[key] =
              addField(
                  fieldName =
                    fieldNameAllocator.newName(
                      "${getter.name.asString().decapitalizeUS().removeSuffix(Symbols.StringNames.METRO_ACCESSOR)}Provider"
                    ),
                  fieldType = symbols.metroProvider.typeWith(node.typeKey.type),
                  fieldVisibility = DescriptorVisibilities.PRIVATE,
                )
                .apply {
                  isFinal = true
                  initializer =
                    pluginContext.createIrBuilder(symbol).run {
                      // If this is in instance fields, just do a quick assignment
                      val bindingExpression =
                        if (binding.typeKey in instanceFields) {
                          val field = instanceFields.getValue(binding.typeKey)
                          instanceFactory(
                            binding.typeKey.type,
                            irGetField(irGet(thisReceiverParameter), field),
                          )
                        } else {
                          generateBindingCode(binding, generationContext = baseGenerationContext)
                        }
                      irExprBody(bindingExpression)
                    }
                }
          }
        }
      }

      // For all deferred types, assign them first as factories
      // TODO For any types that depend on deferred types, they need providers too?
      @Suppress("UNCHECKED_CAST")
      val deferredFields: Map<IrTypeKey, IrField> =
        sealResult.deferredTypes.associateWith { deferredTypeKey ->
          val binding = bindingGraph.requireBinding(deferredTypeKey, IrBindingStack.empty())
          val field =
            addField(
                fieldNameAllocator.newName(binding.nameHint.decapitalizeUS() + "Provider"),
                deferredTypeKey.type.wrapInProvider(symbols.metroProvider),
              )
              .apply {
                isFinal = true
                initializer =
                  pluginContext.createIrBuilder(symbol).run {
                    irExprBody(
                      irInvoke(callee = symbols.metroDelegateFactoryConstructor).apply {
                        putTypeArgument(0, deferredTypeKey.type)
                      }
                    )
                  }
              }

          providerFields[deferredTypeKey] = field
          field
        }

      // Create fields in dependency-order
      initOrder
        .filterNot { it.typeKey in deferredFields }
        .filterNot {
          // We don't generate fields for these even though we do track them in dependencies above,
          // it's just for propagating their aliased type in sorting
          it is Binding.Alias
        }
        .forEach { binding ->
          val key = binding.typeKey
          // Since assisted injections don't implement Factory, we can't just type these as
          // Provider<*> fields
          val fieldType =
            if (binding is Binding.ConstructorInjected && binding.isAssisted) {
              val factory =
                injectConstructorTransformer.getOrGenerateFactory(
                  binding.type,
                  binding.injectedConstructor,
                ) ?: return@forEach

              factory.factoryClass.typeWith() // TODO generic factories?
            } else {
              symbols.metroProvider.typeWith(key.type)
            }

          val field =
            addField(
                fieldName =
                  fieldNameAllocator.newName(binding.nameHint.decapitalizeUS() + "Provider"),
                fieldType = fieldType,
                fieldVisibility = DescriptorVisibilities.PRIVATE,
              )
              .apply {
                isFinal = true
                initializer =
                  pluginContext.createIrBuilder(symbol).run {
                    val provider =
                      generateBindingCode(binding, baseGenerationContext).letIf(
                        binding.scope != null
                      ) {
                        // If it's scoped, wrap it in double-check
                        // DoubleCheck.provider(<provider>)
                        it.doubleCheck(this@run, symbols, binding.typeKey)
                      }
                    irExprBody(provider)
                  }
              }
          if (binding is Binding.Provided && binding.isIntoMultibinding) {
            multibindingProviderFields[binding] = field
          } else {
            providerFields[key] = field
          }
        }

      // Add statements to our constructor's deferred fields _after_ we've added all provider
      // fields for everything else. This is important in case they reference each other
      for ((deferredTypeKey, field) in deferredFields) {
        val binding = bindingGraph.requireBinding(deferredTypeKey, bindingStack)
        extraConstructorStatements.add {
          irInvoke(
              dispatchReceiver = irGetObject(symbols.metroDelegateFactoryCompanion),
              callee = symbols.metroDelegateFactorySetDelegate,
              // TODO de-dupe?
              args =
                listOf(
                  irGetField(irGet(thisReceiverParameter), field),
                  pluginContext.createIrBuilder(symbol).run {
                    generateBindingCode(
                        binding,
                        baseGenerationContext,
                        fieldInitKey = deferredTypeKey,
                      )
                      .letIf(binding.scope != null) {
                        // If it's scoped, wrap it in double-check
                        // DoubleCheck.provider(<provider>)
                        it.doubleCheck(this@run, symbols, binding.typeKey)
                      }
                  },
                ),
            )
            .apply { putTypeArgument(0, deferredTypeKey.type) }
        }
      }

      // Add extra constructor statements
      with(ctor) {
        val originalBody = checkNotNull(body)
        buildBlockBody(pluginContext) {
          +originalBody.statements
          for (extra in extraConstructorStatements) {
            +extra()
          }
        }
      }

      parentTracer.traceNested("Implement overrides") { tracer ->
        node.implementOverrides(baseGenerationContext, tracer)
      }

      if (node.isExtendable) {
        parentTracer.traceNested("Generate Metro metadata") {
          // Finally, generate metadata
          val graphProto =
            node.toProto(
              bindingGraph = bindingGraph,
              supertypeClasses =
                node.allIncludedNodes
                  .filter { it.isExtendable }
                  .mapToSet { it.sourceGraph.classIdOrFail.asString() },
              parentGraphClasses =
                node.allExtendedNodes.values.mapToSet { it.sourceGraph.classIdOrFail.asString() },
              providerFields =
                providerFields
                  .filterKeys { typeKey -> typeKey != node.typeKey }
                  .filterKeys { typeKey ->
                    val binding = bindingGraph.requireBinding(typeKey, IrBindingStack.empty())
                    when {
                      // Don't re-expose existing accessors
                      binding is Binding.GraphDependency && binding.isProviderFieldAccessor -> false
                      // Only expose scoped bindings. Some provider fields may be for non-scoped
                      // bindings just for reuse. BoundInstance bindings still need to be passed on
                      binding.scope == null && binding !is Binding.BoundInstance -> false
                      else -> true
                    }
                  }
                  .values
                  .map { it.name.asString() }
                  .sorted(),
              instanceFields =
                instanceFields
                  .filterKeys { typeKey -> typeKey != node.typeKey }
                  .values
                  .map { it.name.asString() }
                  .sorted(),
            )
          val metroMetadata = MetroMetadata(METRO_VERSION, graphProto)

          writeDiagnostic({
            "graph-metadata-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt"
          }) {
            metroMetadata.toString()
          }

          // IR-generated types do not have metadata
          if (graphClass.origin !== Origins.ContributedGraph) {
            val serialized = MetroMetadata.ADAPTER.encode(metroMetadata)
            pluginContext.metadataDeclarationRegistrar.addCustomMetadataExtension(
              graphClass,
              PLUGIN_ID,
              serialized,
            )
          }
          dependencyGraphNodesByClass[node.sourceGraph.classIdOrFail] =
            node.copy(proto = graphProto)
        }

        // Expose getters for provider and instance fields and expose them to metadata
        sequence {
            for (entry in providerFields) {
              val binding = bindingGraph.requireBinding(entry.key, bindingStack)
              if (binding is Binding.GraphDependency && binding.isProviderFieldAccessor) {
                // This'll get looked up directly by child graphs
                continue
              } else if (binding.scope == null && binding !is Binding.BoundInstance) {
                // Don't expose redundant accessors for unscoped bindings. BoundInstance bindings
                // still get passed on
                continue
              }
              yield(entry)
            }
            yieldAll(instanceFields.entries)
          }
          .filter {
            // Skip the graph instance field
            it.key != node.typeKey
          }
          .distinctBy {
            // Only generate once per field. Can happen for cases
            // where we add convenience keys for graph instance supertypes.
            it.value
          }
          .forEach { (key, field) ->
            val getter =
              addFunction(
                  name = "${field.name.asString()}${Symbols.StringNames.METRO_ACCESSOR}",
                  returnType = field.type,
                  // TODO is this... ok?
                  visibility = DescriptorVisibilities.INTERNAL,
                  origin = Origins.InstanceFieldAccessor,
                )
                .apply {
                  key.qualifier?.let {
                    annotations +=
                      it.ir.transform(DeepCopyIrTreeWithSymbols(SymbolRemapper.EMPTY), null)
                        as IrConstructorCall
                  }
                  // TODO add deprecation + hidden annotation to hide? Not sure if necessary
                  body =
                    pluginContext.createIrBuilder(symbol).run {
                      val expression =
                        if (key in instanceFields) {
                          irGetField(irGet(dispatchReceiverParameter!!), field)
                        } else {
                          val binding = bindingGraph.requireBinding(key, bindingStack)
                          generateBindingCode(
                            binding,
                            baseGenerationContext.withReceiver(dispatchReceiverParameter!!),
                          )
                        }
                      irExprBodySafe(symbol, expression)
                    }
                }
            pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(getter)
          }
      }
    }

  private fun DependencyGraphNode.toProto(
    bindingGraph: IrBindingGraph,
    supertypeClasses: Set<String>,
    parentGraphClasses: Set<String>,
    providerFields: List<String>,
    instanceFields: List<String>,
  ): DependencyGraphProto {
    val bindsCallableIds =
      bindingGraph.bindingsSnapshot().values.filterIsInstance<Binding.Alias>().mapNotNull { binding
        ->
        binding.ir
          ?.overriddenSymbolsSequence()
          ?.lastOrNull()
          ?.owner
          ?.propertyIfAccessor
          ?.expectAsOrNull<IrDeclarationWithName>()
          ?.let {
            when (it) {
              is IrSimpleFunction -> {
                val callableId = it.callableId
                return@let BindsCallableId(
                  callableId.classId!!.asString(),
                  callableId.callableName.asString(),
                  is_property = false,
                )
              }
              is IrProperty -> {
                val callableId = it.callableId
                return@let BindsCallableId(
                  callableId.classId!!.asString(),
                  callableId.callableName.asString(),
                  is_property = true,
                )
              }
              else -> null
            }
          }
      }

    var multibindingAccessors = 0
    val accessorNames =
      accessors
        .sortedBy { it.first.ir.name.asString() }
        .onEachIndexed { index, (_, contextKey) ->
          val isMultibindingAccessor =
            bindingGraph.requireBinding(contextKey, IrBindingStack.empty()) is Binding.Multibinding
          if (isMultibindingAccessor) {
            multibindingAccessors = multibindingAccessors or (1 shl index)
          }
        }
        .map { it.first.ir.name.asString() }

    return DependencyGraphProto(
      is_graph = true,
      provider_field_names = providerFields,
      instance_field_names = instanceFields,
      provider_factory_classes =
        providerFactories.map { (_, factory) -> factory.clazz.classIdOrFail.asString() }.sorted(),
      binds_callable_ids =
        bindsCallableIds.sortedWith(
          compareBy<BindsCallableId> { it.class_id }
            .thenBy { it.callable_name }
            .thenBy { it.is_property }
        ),
      accessor_callable_names = accessorNames,
      included_classes = supertypeClasses.sorted(),
      parent_graph_classes = parentGraphClasses.sorted(),
      multibinding_accessor_indices = multibindingAccessors,
    )
  }

  // TODO add asProvider support?
  private fun IrClass.addSimpleInstanceField(
    name: String,
    type: IrType,
    initializerExpression: IrBuilderWithScope.() -> IrExpression,
  ): IrField =
    addField(fieldName = name, fieldType = type, fieldVisibility = DescriptorVisibilities.PRIVATE)
      .apply {
        isFinal = true
        initializer =
          pluginContext.createIrBuilder(symbol).run { irExprBody(initializerExpression()) }
      }

  private fun DependencyGraphNode.implementOverrides(
    context: GraphGenerationContext,
    parentTracer: Tracer,
  ) {
    // Implement abstract getters for accessors
    accessors.forEach { (function, contextualTypeKey) ->
      function.ir.apply {
        val declarationToFinalize =
          function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(context.thisReceiver)
        }
        val irFunction = this
        val binding = context.graph.requireBinding(contextualTypeKey, context.bindingStack)
        context.bindingStack.push(IrBindingStack.Entry.requestedAt(contextualTypeKey, function.ir))
        body =
          pluginContext.createIrBuilder(symbol).run {
            if (binding is Binding.Multibinding) {
              // TODO if we have multiple accessors pointing at the same type, implement
              //  one and make the rest call that one. Not multibinding specific. Maybe
              //  groupBy { typekey }?
            }
            irExprBodySafe(
              symbol,
              typeAsProviderArgument(
                metroContext,
                contextualTypeKey,
                generateBindingCode(
                  binding,
                  context.withReceiver(irFunction.dispatchReceiverParameter!!),
                  contextualTypeKey,
                ),
                isAssisted = false,
                isGraphInstance = false,
              ),
            )
          }
      }
      context.bindingStack.pop()
    }

    // Implement abstract injectors
    injectors.forEach { (overriddenFunction, typeKey) ->
      overriddenFunction.ir.apply {
        finalizeFakeOverride(context.thisReceiver)
        val targetParam = valueParameters[0]
        val binding =
          context.graph.requireBinding(typeKey, context.bindingStack) as Binding.MembersInjected
        context.bindingStack.push(
          IrBindingStack.Entry.requestedAt(IrContextualTypeKey(typeKey), this)
        )

        // We don't get a MembersInjector instance/provider from the graph. Instead, we call
        // all the target inject functions directly
        body =
          pluginContext.createIrBuilder(symbol).irBlockBody {
            // TODO reuse, consolidate calling code with how we implement this in
            //  constructor inject code gen
            // val injectors =
            // membersInjectorTransformer.getOrGenerateAllInjectorsFor(declaration)
            // val memberInjectParameters = injectors.flatMap { it.parameters.values.flatten()
            // }

            for (type in
              pluginContext
                .referenceClass(binding.targetClassId)!!
                .owner
                .getAllSuperTypes(pluginContext, excludeSelf = false, excludeAny = true)) {
              val clazz = type.rawType()
              val generatedInjector =
                membersInjectorTransformer.getOrGenerateInjector(clazz) ?: continue
              for ((function, parameters) in generatedInjector.injectFunctions) {
                +irInvoke(
                  dispatchReceiver = irGetObject(function.parentAsClass.symbol),
                  callee = function.symbol,
                  args =
                    buildList {
                      add(irGet(targetParam))
                      for (parameter in parameters.valueParameters) {
                        val paramBinding =
                          context.graph.requireBinding(
                            parameter.contextualTypeKey,
                            context.bindingStack,
                          )
                        add(
                          typeAsProviderArgument(
                            metroContext,
                            parameter.contextualTypeKey,
                            generateBindingCode(
                              paramBinding,
                              context.withReceiver(
                                overriddenFunction.ir.dispatchReceiverParameter!!
                              ),
                              parameter.contextualTypeKey,
                            ),
                            isAssisted = false,
                            isGraphInstance = false,
                          )
                        )
                      }
                    },
                )
              }
            }
          }
      }
      context.bindingStack.pop()
    }

    // Implement no-op bodies for Binds providers
    bindsFunctions.forEach { (function, _) ->
      function.ir.apply {
        val declarationToFinalize =
          function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(context.thisReceiver)
        }
        body = stubExpressionBody(metroContext)
      }
    }

    // Implement no-op bodies for contributed graphs
    contributedGraphs.forEach { (typeKey, function) ->
      function.ir.apply {
        val declarationToFinalize =
          function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(context.thisReceiver)
        }
        val irFunction = this
        val contributedGraph =
          getOrBuildContributedGraph(typeKey, sourceGraph, function, parentTracer)
        val ctor = contributedGraph.primaryConstructor!!
        body =
          pluginContext.createIrBuilder(symbol).run {
            irExprBodySafe(
              symbol,
              irCallConstructor(ctor.symbol, emptyList()).apply {
                // First arg is always the graph instance
                putValueArgument(0, irGet(irFunction.dispatchReceiverParameter!!))
                for (i in 0 until valueParameters.size) {
                  putValueArgument(i + 1, irGet(irFunction.valueParameters[i]))
                }
              },
            )
          }
      }
    }
  }

  private fun getOrBuildContributedGraph(
    typeKey: IrTypeKey,
    parentGraph: IrClass,
    contributedAccessor: MetroSimpleFunction,
    parentTracer: Tracer,
  ): IrClass {
    val classId = typeKey.type.rawType().classIdOrFail
    return parentGraph.nestedClasses.firstOrNull { it.classId == classId }
      ?: run {
        val generator = IrContributedGraphGenerator(metroContext, contributionData)
        // Find the function declaration in the original @ContributesGraphExtension.Factory
        val sourceFunction =
          contributedAccessor.ir
            .overriddenSymbolsSequence()
            .filter {
              it.owner.parentClassOrNull?.isAnnotatedWithAny(
                metroContext.symbols.classIds.contributesGraphExtensionFactoryAnnotations
              ) == true
            }
            .lastOrNull()
            ?.owner ?: contributedAccessor.ir

        val sourceFactory = sourceFunction.parentAsClass
        val sourceGraph = sourceFactory.parentAsClass
        parentTracer.traceNested("Generate contributed graph ${sourceGraph.name}") {
          generator.generateContributedGraph(
            parentGraph = parentGraph,
            sourceGraph = sourceGraph,
            sourceFactory = sourceFactory,
            factoryFunction = sourceFunction,
          )
        }
      }
  }

  private fun collectBindings(
    node: DependencyGraphNode,
    graph: IrBindingGraph,
    bindingStack: IrBindingStack,
  ): Map<IrTypeKey, Binding> {
    val bindingDependencies = mutableMapOf<IrTypeKey, Binding>()
    // Track used unscoped bindings. We only need to generate a field if they're used more than
    // once
    val usedUnscopedBindings = mutableSetOf<IrTypeKey>()
    val visitedBindings = mutableSetOf<IrTypeKey>()

    // Collect from roots
    node.accessors.forEach { (accessor, contextualTypeKey) ->
      findAndProcessBinding(
        contextKey = contextualTypeKey,
        stackEntry = IrBindingStack.Entry.requestedAt(contextualTypeKey, accessor.ir),
        node = node,
        graph = graph,
        bindingStack = bindingStack,
        bindingDependencies = bindingDependencies,
        usedUnscopedBindings = usedUnscopedBindings,
        visitedBindings = visitedBindings,
      )
    }

    if (node.isExtendable) {
      // Ensure all scoped providers have fields in extendable graphs, even if they are not used in
      // this graph
      graph.bindingsSnapshot().forEach { (_, binding) ->
        if (binding is Binding.Provided && binding.annotations.isScoped) {
          processBinding(
            binding,
            node,
            graph,
            bindingStack,
            bindingDependencies,
            usedUnscopedBindings,
            visitedBindings,
          )
        }
      }
    }

    return bindingDependencies
  }

  private fun findAndProcessBinding(
    contextKey: IrContextualTypeKey,
    stackEntry: IrBindingStack.Entry,
    node: DependencyGraphNode,
    graph: IrBindingGraph,
    bindingStack: IrBindingStack,
    bindingDependencies: MutableMap<IrTypeKey, Binding>,
    usedUnscopedBindings: MutableSet<IrTypeKey>,
    visitedBindings: MutableSet<IrTypeKey>,
  ) {
    val key = contextKey.typeKey
    // Skip if already visited
    if (key in visitedBindings) {
      if (key in usedUnscopedBindings && key !in bindingDependencies) {
        // Only add unscoped binding provider fields if they're used more than once
        bindingDependencies[key] = graph.requireBinding(key, bindingStack)
      }
      return
    }

    bindingStack.withEntry(stackEntry) {
      val binding = graph.requireBinding(contextKey, bindingStack)
      processBinding(
        binding,
        node,
        graph,
        bindingStack,
        bindingDependencies,
        usedUnscopedBindings,
        visitedBindings,
      )
    }
  }

  private fun processBinding(
    binding: Binding,
    node: DependencyGraphNode,
    graph: IrBindingGraph,
    bindingStack: IrBindingStack,
    bindingDependencies: MutableMap<IrTypeKey, Binding>,
    usedUnscopedBindings: MutableSet<IrTypeKey>,
    visitedBindings: MutableSet<IrTypeKey>,
  ) {
    val isMultibindingProvider =
      (binding is Binding.Provided || binding is Binding.Alias) &&
        binding.annotations.isIntoMultibinding
    val key = binding.typeKey

    // Skip if already visited
    // TODO de-dupe with findAndProcessBinding
    if (!isMultibindingProvider && key in visitedBindings) {
      if (key in usedUnscopedBindings && key !in bindingDependencies) {
        // Only add unscoped binding provider fields if they're used more than once
        bindingDependencies[key] = graph.requireBinding(key, bindingStack)
      }
      return
    }

    val bindingScope = binding.scope

    // Check scoping compatibility
    // TODO FIR error?
    if (bindingScope != null) {
      if (node.scopes.isEmpty() || bindingScope !in node.scopes) {
        val isUnscoped = node.scopes.isEmpty()
        // Error if there are mismatched scopes
        val declarationToReport = node.sourceGraph
        bindingStack.push(
          IrBindingStack.Entry.simpleTypeRef(
            binding.contextualTypeKey,
            usage = "(scoped to '$bindingScope')",
          )
        )
        val message = buildString {
          append("[Metro/IncompatiblyScopedBindings] ")
          append(declarationToReport.kotlinFqName)
          if (isUnscoped) {
            // Unscoped graph but scoped binding
            append(" (unscoped) may not reference scoped bindings:")
          } else {
            // Scope mismatch
            append(
              " (scopes ${node.scopes.joinToString { "'$it'" }}) may not reference bindings from different scopes:"
            )
          }
          appendLine()
          appendBindingStack(bindingStack, short = false)
          if (!isUnscoped && binding is Binding.ConstructorInjected) {
            val matchingParent =
              node.allExtendedNodes.values.firstOrNull { bindingScope in it.scopes }
            if (matchingParent != null) {
              appendLine()
              appendLine()
              val shortTypeKey = binding.typeKey.render(short = true)
              appendLine(
                """
                  (Hint)
                  It appears that extended parent graph '${matchingParent.sourceGraph.kotlinFqName}' does declare the '$bindingScope' scope but doesn't use '$shortTypeKey' directly.
                  To work around this, consider declaring an accessor for '$shortTypeKey' in that graph (i.e. `val ${shortTypeKey.decapitalizeUS()}: $shortTypeKey`).
                  See https://github.com/ZacSweers/metro/issues/377 for more details.
                """
                  .trimIndent()
              )
            }
          }
        }
        declarationToReport.reportError(message)
        exitProcessing()
      }
    }

    visitedBindings += key

    // Scoped, graph, and members injector bindings always need (provider) fields
    if (
      bindingScope != null ||
        binding is Binding.GraphDependency ||
        (binding is Binding.MembersInjected && !binding.isFromInjectorFunction)
    ) {
      bindingDependencies[key] = binding
    }

    when (binding) {
      is Binding.Assisted -> {
        // For assisted bindings, we need provider fields for the assisted factory impl type
        // The factory impl type depends on a provider of the assisted type
        val targetBinding = graph.requireBinding(binding.target, bindingStack)
        bindingDependencies[key] = targetBinding
        // TODO is this safe to end up as a provider field? Can someone create a
        //  binding such that you have an assisted type on the DI graph that is
        //  provided by a provider that depends on the assisted factory? I suspect
        //  yes, so in that case we should probably track a separate field mapping
        usedUnscopedBindings += binding.target.typeKey
        // By definition, assisted parameters are not available on the graph
        // But we _do_ need to process the target type's parameters!
        processBinding(
          binding = targetBinding,
          node = node,
          graph = graph,
          bindingStack = bindingStack,
          bindingDependencies = bindingDependencies,
          usedUnscopedBindings = usedUnscopedBindings,
          visitedBindings = visitedBindings,
        )
        return
      }

      is Binding.Multibinding -> {
        // For multibindings, we depend on anything the delegate providers depend on
        if (bindingScope != null) {
          // This is scoped so we want to keep an instance
          // TODO are these allowed?
          //  bindingDependencies[key] = buildMap {
          //    for (provider in binding.providers) {
          //      putAll(provider.dependencies)
          //    }
          //  }
        } else {
          // Process all providers deps, but don't need a specific dep for this one
          // TODO eventually would be nice to just let a binding.dependencies lookup handle this
          //  but currently the later logic uses parameters for lookups
          for (providerKey in binding.sourceBindings) {
            val provider = graph.requireBinding(providerKey, bindingStack)
            processBinding(
              binding = provider,
              node = node,
              graph = graph,
              bindingStack = bindingStack,
              bindingDependencies = bindingDependencies,
              usedUnscopedBindings = usedUnscopedBindings,
              visitedBindings = visitedBindings,
            )
          }
        }
        return
      }

      else -> {
        // Do nothing here
      }
    }

    // Track dependencies before creating fields
    if (bindingScope == null) {
      usedUnscopedBindings += key
    }

    // Recursively process dependencies
    for (param in binding.parameters.nonInstanceParameters) {
      if (param.isAssisted) continue

      // Process binding dependencies
      findAndProcessBinding(
        contextKey = param.contextualTypeKey,
        stackEntry = (param as ConstructorParameter).bindingStackEntry,
        node = node,
        graph = graph,
        bindingStack = bindingStack,
        bindingDependencies = bindingDependencies,
        usedUnscopedBindings = usedUnscopedBindings,
        visitedBindings = visitedBindings,
      )
    }

    if (binding is Binding.Alias) {
      // Track this even though we won't generate a field so that we can reference it when sorting
      // Annoyingly, I was never able to create a test that actually failed without this, but did
      // need this fix to fix a real world example in github.com/zacsweers/catchup
      bindingDependencies[key] = graph.requireBinding(binding.aliasedType, bindingStack)
    }
  }

  private fun IrBuilderWithScope.generateBindingArguments(
    targetParams: Parameters<out Parameter>,
    function: IrFunction,
    binding: Binding,
    generationContext: GraphGenerationContext,
  ): List<IrExpression?> {
    val params = function.parameters(metroContext)
    // TODO only value args are supported atm
    val paramsToMap = buildList {
      if (
        binding is Binding.Provided &&
          targetParams.instance?.type?.rawTypeOrNull()?.isObject != true
      ) {
        targetParams.instance?.let(::add)
      }
      addAll(targetParams.valueParameters.filterNot { it.isAssisted })
    }
    if (
      binding is Binding.Provided &&
        binding.providerFactory.providesFunction.correspondingPropertySymbol == null
    ) {
      check(params.valueParameters.size == paramsToMap.size) {
        """
        Inconsistent parameter types for type ${binding.typeKey}!
        Input type keys:
          - ${paramsToMap.map { it.typeKey }.joinToString()}
        Binding parameters (${function.kotlinFqName}):
          - ${function.valueParameters.map { IrContextualTypeKey.from(metroContext,it).typeKey }.joinToString()}
        """
          .trimIndent()
      }
    }

    return params.valueParameters.mapIndexed { i, param ->
      val contextualTypeKey = paramsToMap[i].contextualTypeKey
      val typeKey = contextualTypeKey.typeKey

      val metroProviderSymbols = symbols.providerSymbolsFor(contextualTypeKey)

      // TODO consolidate this logic with generateBindingCode
      if (!contextualTypeKey.requiresProviderInstance) {
        // IFF the parameter can take a direct instance, try our instance fields
        generationContext.instanceFields[typeKey]?.let { instanceField ->
          return@mapIndexed irGetField(irGet(generationContext.thisReceiver), instanceField).let {
            with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
          }
        }
      }

      val providerInstance =
        if (typeKey in generationContext.providerFields) {
          // If it's in provider fields, invoke that field
          irGetField(
            irGet(generationContext.thisReceiver),
            generationContext.providerFields.getValue(typeKey),
          )
        } else if (
          binding is Binding.Provided &&
            binding.isIntoMultibinding &&
            binding in generationContext.multibindingProviderFields
        ) {
          irGetField(
            irGet(generationContext.thisReceiver),
            generationContext.multibindingProviderFields.getValue(binding),
          )
        } else {
          val entry =
            when (binding) {
              is Binding.ConstructorInjected -> {
                val constructor = binding.injectedConstructor
                IrBindingStack.Entry.injectedAt(
                  contextualTypeKey,
                  constructor,
                  constructor.valueParameters[i],
                )
              }

              is Binding.ObjectClass -> error("Object classes cannot have dependencies")

              is Binding.Provided,
              is Binding.Alias -> {
                IrBindingStack.Entry.injectedAt(
                  contextualTypeKey,
                  function,
                  function.valueParameters[i],
                )
              }

              is Binding.Assisted -> {
                IrBindingStack.Entry.injectedAt(contextualTypeKey, function)
              }

              is Binding.MembersInjected -> {
                IrBindingStack.Entry.injectedAt(contextualTypeKey, function)
              }

              is Binding.Multibinding -> {
                // TODO can't be right?
                IrBindingStack.Entry.injectedAt(contextualTypeKey, function)
              }

              is Binding.Absent,
              is Binding.BoundInstance,
              is Binding.GraphDependency -> error("Should never happen, logic is handled above")
            }
          generationContext.bindingStack.push(entry)
          // Generate binding code for each param
          val paramBinding =
            generationContext.graph.requireBinding(
              contextualTypeKey,
              generationContext.bindingStack,
            )

          if (paramBinding is Binding.Absent) {
            // Null argument expressions get treated as absent in the final call
            return@mapIndexed null
          }

          generateBindingCode(
            paramBinding,
            generationContext,
            contextualTypeKey = param.contextualTypeKey,
          )
        }

      typeAsProviderArgument(
        metroContext,
        param.contextualTypeKey,
        providerInstance,
        isAssisted = param.isAssisted,
        isGraphInstance = param.isGraphInstance,
      )
    }
  }

  private fun generateMapKeyLiteral(binding: Binding): IrExpression {
    val mapKey =
      when (binding) {
        is Binding.Alias -> binding.annotations.mapKeys.first().ir
        is Binding.Provided -> binding.annotations.mapKeys.first().ir
        is Binding.ConstructorInjected -> binding.annotations.mapKeys.first().ir
        else -> error("Unsupported multibinding source: $binding")
      }

    val unwrapValue = shouldUnwrapMapKeyValues(mapKey)
    val expression =
      if (!unwrapValue) {
        mapKey
      } else {
        // We can just copy the expression!
        mapKey.getValueArgument(0)!!
      }

    return expression
  }

  private fun IrBuilderWithScope.generateBindingCode(
    binding: Binding,
    generationContext: GraphGenerationContext,
    contextualTypeKey: IrContextualTypeKey = binding.contextualTypeKey,
    fieldInitKey: IrTypeKey? = null,
  ): IrExpression {
    if (binding is Binding.Absent) {
      error(
        "Absent bindings need to be checked prior to generateBindingCode(). ${binding.typeKey} missing."
      )
    }

    val metroProviderSymbols = symbols.providerSymbolsFor(contextualTypeKey)

    // If we already have a provider field we can just return it
    if (
      binding is Binding.Provided &&
        binding.isIntoMultibinding &&
        binding in generationContext.multibindingProviderFields
    ) {
      generationContext.multibindingProviderFields[binding]?.let {
        return irGetField(irGet(generationContext.thisReceiver), it).let {
          with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
        }
      }
    }

    // If we're initializing the field for this key, don't ever try to reach for an existing
    // provider for it.
    // This is important for cases like DelegateFactory and breaking cycles.
    if (fieldInitKey == null || fieldInitKey != binding.typeKey) {
      generationContext.providerFields[binding.typeKey]?.let {
        return irGetField(irGet(generationContext.thisReceiver), it).let {
          with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
        }
      }
    }

    return when (binding) {
      is Binding.ConstructorInjected -> {
        // Example_Factory.create(...)
        val injectableConstructor = binding.injectedConstructor
        val factory =
          injectConstructorTransformer.getOrGenerateFactory(binding.type, injectableConstructor)
            ?: return stubExpression(metroContext)

        with(factory) {
          invokeCreateExpression { createFunction ->
            generateBindingArguments(
              createFunction.parameters(metroContext),
              createFunction,
              binding,
              generationContext,
            )
          }
        }
      }

      is Binding.ObjectClass -> {
        instanceFactory(binding.typeKey.type, irGetObject(binding.type.symbol))
      }

      is Binding.Alias -> {
        // For binds functions, just use the backing type
        val aliasedBinding =
          binding.aliasedBinding(generationContext.graph, generationContext.bindingStack)
        check(aliasedBinding != binding) { "Aliased binding aliases itself" }
        return generateBindingCode(aliasedBinding, generationContext)
      }

      is Binding.Provided -> {
        val factoryClass =
          providesTransformer.getOrLookupFactoryClass(binding)?.clazz
            ?: return stubExpression(metroContext)
        // Invoke its factory's create() function
        val creatorClass =
          if (factoryClass.isObject) {
            factoryClass
          } else {
            factoryClass.companionObject()!!
          }
        val createFunction = creatorClass.requireSimpleFunction(Symbols.StringNames.CREATE)
        // Must use the provider's params for IrTypeKey as that has qualifier
        // annotations
        val args =
          generateBindingArguments(
            binding.parameters,
            createFunction.owner,
            binding,
            generationContext,
          )
        irInvoke(
          dispatchReceiver = irGetObject(creatorClass.symbol),
          callee = createFunction,
          args = args,
        )
      }

      is Binding.Assisted -> {
        // Example9_Factory_Impl.create(example9Provider);
        val implClass =
          assistedFactoryTransformer.getOrGenerateImplClass(binding.type)
            ?: return stubExpression(metroContext)

        val dispatchReceiver: IrExpression?
        val createFunction: IrSimpleFunctionSymbol
        val isFromDagger: Boolean
        if (options.enableDaggerRuntimeInterop && implClass.isFromJava()) {
          // Dagger interop
          createFunction =
            implClass
              .simpleFunctions()
              .first {
                it.isStatic &&
                  (it.name == Symbols.Names.create ||
                    it.name == Symbols.Names.createFactoryProvider)
              }
              .symbol
          dispatchReceiver = null
          isFromDagger = true
        } else {
          val implClassCompanion = implClass.companionObject()!!
          createFunction = implClassCompanion.requireSimpleFunction(Symbols.StringNames.CREATE)
          dispatchReceiver = irGetObject(implClassCompanion.symbol)
          isFromDagger = false
        }

        val targetBinding =
          generationContext.graph.requireBinding(binding.target.typeKey, IrBindingStack.empty())
        val delegateFactoryProvider = generateBindingCode(targetBinding, generationContext)
        val invokeCreateExpression =
          irInvoke(
            dispatchReceiver = dispatchReceiver,
            callee = createFunction,
            args = listOf(delegateFactoryProvider),
          )
        if (isFromDagger) {
          with(symbols.daggerSymbols) {
            val targetType =
              (createFunction.owner.returnType as IrSimpleType).arguments[0].typeOrFail
            transformToMetroProvider(invokeCreateExpression, targetType)
          }
        } else {
          invokeCreateExpression
        }
      }

      is Binding.Multibinding -> {
        generateMultibindingExpression(binding, contextualTypeKey, generationContext, fieldInitKey)
      }

      is Binding.MembersInjected -> {
        val injectedClass = pluginContext.referenceClass(binding.targetClassId)!!.owner
        val injectedType = injectedClass.defaultType
        val injectorClass = membersInjectorTransformer.getOrGenerateInjector(injectedClass)?.ir

        if (injectorClass == null) {
          // Return a noop
          irInvoke(
              dispatchReceiver = irGetObject(symbols.metroMembersInjectors),
              callee = symbols.metroMembersInjectorsNoOp,
            )
            .apply { putTypeArgument(0, injectedType) }
            .let { with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) } }
        } else {
          val createFunction = injectorClass.requireSimpleFunction(Symbols.StringNames.CREATE)
          val args =
            generateBindingArguments(
              binding.parameters,
              createFunction.owner,
              binding,
              generationContext,
            )
          instanceFactory(
              injectedType,
              // InjectableClass_MembersInjector.create(stringValueProvider,
              // exampleComponentProvider)
              irInvoke(
                dispatchReceiver =
                  if (injectorClass.isObject) {
                    irGetObject(injectorClass.symbol)
                  } else {
                    // It's static from java, dagger interop
                    check(createFunction.owner.isStatic)
                    null
                  },
                callee = createFunction,
                args = args,
              ),
            )
            .let { with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) } }
        }
      }

      is Binding.Absent -> {
        // Should never happen, this should be checked before function/constructor injections.
        error("Unable to generate code for unexpected Absent binding: $binding")
      }

      is Binding.BoundInstance -> {
        // Should never happen, this should get handled in the provider fields logic above.
        error("Unable to generate code for unexpected BoundInstance binding: $binding")
      }

      is Binding.GraphDependency -> {
        val ownerKey = IrTypeKey(binding.graph.defaultType)
        val graphInstanceField =
          generationContext.instanceFields[ownerKey]
            ?: run {
              error(
                "No matching included type instance found for type ${ownerKey}. Available instance fields ${generationContext.instanceFields.keys}"
              )
            }

        val getterContextKey = IrContextualTypeKey.from(metroContext, binding.getter)
        val lambda =
          irLambda(
            context = pluginContext,
            parent = generationContext.thisReceiver.parent,
            receiverParameter = null,
            emptyList(),
            binding.typeKey.type,
            suspend = false,
          ) {
            val invokeGetter =
              irInvoke(
                dispatchReceiver =
                  irGetField(irGet(generationContext.thisReceiver), graphInstanceField),
                callee = binding.getter.symbol,
                typeHint = binding.typeKey.type,
              )
            val returnExpression =
              if (getterContextKey.isWrappedInProvider) {
                irInvoke(invokeGetter, callee = symbols.providerInvoke)
              } else if (getterContextKey.isWrappedInLazy) {
                irInvoke(invokeGetter, callee = symbols.lazyGetValue)
              } else {
                invokeGetter
              }
            +irReturn(returnExpression)
          }
        irInvoke(
            dispatchReceiver = null,
            callee = symbols.metroProviderFunction,
            typeHint = binding.typeKey.type.wrapInProvider(symbols.metroProvider),
            args = listOf(lambda),
          )
          .apply { putTypeArgument(0, binding.typeKey.type) }
      }
    }
  }

  private fun IrBuilderWithScope.generateMultibindingExpression(
    binding: Binding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    return if (binding.isSet) {
      generateSetMultibindingExpression(binding, contextualTypeKey, generationContext, fieldInitKey)
    } else {
      // It's a map
      generateMapMultibindingExpression(binding, contextualTypeKey, generationContext, fieldInitKey)
    }
  }

  private fun IrBuilderWithScope.generateSetMultibindingExpression(
    binding: Binding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    val elementType = (binding.typeKey.type as IrSimpleType).arguments.single().typeOrFail
    val (collectionProviders, individualProviders) =
      binding.sourceBindings
        .map {
          generationContext.graph
            .requireBinding(it, generationContext.bindingStack)
            .expectAs<Binding.BindingWithAnnotations>()
        }
        .partition { it.annotations.isElementsIntoSet }
    // If we have any @ElementsIntoSet, we need to use SetFactory
    return if (collectionProviders.isNotEmpty() || contextualTypeKey.requiresProviderInstance) {
      generateSetFactoryExpression(
        elementType,
        collectionProviders,
        individualProviders,
        generationContext,
        fieldInitKey,
      )
    } else {
      generateSetBuilderExpression(binding, elementType, generationContext, fieldInitKey)
    }
  }

  private fun IrBuilderWithScope.generateSetBuilderExpression(
    binding: Binding.Multibinding,
    elementType: IrType,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    val callee: IrSimpleFunctionSymbol
    val args: List<IrExpression>
    when (val size = binding.sourceBindings.size) {
      0 -> {
        // emptySet()
        callee = symbols.emptySet
        args = emptyList()
      }

      1 -> {
        // setOf(<one>)
        callee = symbols.setOfSingleton
        val provider =
          binding.sourceBindings.first().let {
            generationContext.graph.requireBinding(it, generationContext.bindingStack)
          }
        args = listOf(generateMultibindingArgument(provider, generationContext, fieldInitKey))
      }

      else -> {
        // buildSet(<size>) { ... }
        callee = symbols.buildSetWithCapacity
        args = buildList {
          add(irInt(size))
          add(
            irLambda(
              context = pluginContext,
              parent = parent,
              receiverParameter = pluginContext.irBuiltIns.mutableSetClass.typeWith(elementType),
              valueParameters = emptyList(),
              returnType = pluginContext.irBuiltIns.unitType,
              suspend = false,
            ) { function ->
              // This is the mutable set receiver
              val functionReceiver = function.extensionReceiverParameter!!
              binding.sourceBindings
                .map { generationContext.graph.requireBinding(it, generationContext.bindingStack) }
                .forEach { provider ->
                  +irInvoke(
                    dispatchReceiver = irGet(functionReceiver),
                    callee = symbols.mutableSetAdd.symbol,
                    args =
                      listOf(
                        generateMultibindingArgument(provider, generationContext, fieldInitKey)
                      ),
                  )
                }
            }
          )
        }
      }
    }

    return irCall(callee = callee, type = binding.typeKey.type, typeArguments = listOf(elementType))
      .apply {
        for ((i, arg) in args.withIndex()) {
          putValueArgument(i, arg)
        }
      }
  }

  private fun IrBuilderWithScope.generateSetFactoryExpression(
    elementType: IrType,
    collectionProviders: List<Binding>,
    individualProviders: List<Binding>,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    // SetFactory.<String>builder(1, 1)
    //   .addProvider(FileSystemModule_Companion_ProvideString1Factory.create())
    //   .addCollectionProvider(provideString2Provider)
    //   .build()

    // SetFactory.<String>builder(1, 1)
    val builder: IrExpression =
      irInvoke(
          dispatchReceiver = irGetObject(symbols.setFactoryCompanionObject),
          callee = symbols.setFactoryBuilderFunction,
          typeHint = symbols.setFactoryBuilder.typeWith(elementType),
        )
        .apply {
          putTypeArgument(0, elementType)
          putValueArgument(0, irInt(individualProviders.size))
          putValueArgument(1, irInt(collectionProviders.size))
        }

    val withProviders =
      individualProviders.fold(builder) { receiver, provider ->
        irInvoke(
            dispatchReceiver = receiver,
            callee = symbols.setFactoryBuilderAddProviderFunction,
            typeHint = builder.type,
          )
          .apply {
            putValueArgument(
              0,
              generateBindingCode(provider, generationContext, fieldInitKey = fieldInitKey),
            )
          }
      }

    // .addProvider(FileSystemModule_Companion_ProvideString1Factory.create())
    val withCollectionProviders =
      collectionProviders.fold(withProviders) { receiver, provider ->
        irInvoke(
            dispatchReceiver = receiver,
            callee = symbols.setFactoryBuilderAddCollectionProviderFunction,
            typeHint = builder.type,
          )
          .apply {
            putValueArgument(
              0,
              generateBindingCode(provider, generationContext, fieldInitKey = fieldInitKey),
            )
          }
      }

    // .build()
    return irInvoke(
      dispatchReceiver = withCollectionProviders,
      callee = symbols.setFactoryBuilderBuildFunction,
      typeHint =
        pluginContext.irBuiltIns.setClass
          .typeWith(elementType)
          .wrapInProvider(symbols.metroProvider),
    )
  }

  private fun IrBuilderWithScope.generateMapMultibindingExpression(
    binding: Binding.Multibinding,
    contextualTypeKey: IrContextualTypeKey,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    // MapFactory.<Integer, Integer>builder(2)
    //   .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
    //   .put(2, provideMapInt2Provider)
    //   .build()
    // MapProviderFactory.<Integer, Integer>builder(2)
    //   .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
    //   .put(2, provideMapInt2Provider)
    //   .build()
    val valueWrappedType = contextualTypeKey.wrappedType.findMapValueType()!!

    val mapTypeArgs = (contextualTypeKey.typeKey.type as IrSimpleType).arguments
    check(mapTypeArgs.size == 2) { "Unexpected map type args: ${mapTypeArgs.joinToString()}" }
    val keyType: IrType = mapTypeArgs[0].typeOrFail
    val rawValueType = mapTypeArgs[1].typeOrFail
    val rawValueTypeMetadata =
      rawValueType.typeOrFail.asContextualTypeKey(metroContext, null, hasDefault = false)

    // TODO what about Map<String, Provider<Lazy<String>>>?
    //  isDeferrable() but we need to be able to convert back to the middle type
    val useProviderFactory: Boolean = valueWrappedType is WrappedType.Provider
    val valueType: IrType = rawValueTypeMetadata.typeKey.type

    val targetCompanionObject =
      if (useProviderFactory) {
        symbols.mapProviderFactoryCompanionObject
      } else {
        symbols.mapFactoryCompanionObject
      }

    val size = binding.sourceBindings.size
    val mapProviderType =
      pluginContext.irBuiltIns.mapClass
        .typeWith(
          keyType,
          if (useProviderFactory) {
            rawValueType.wrapInProvider(symbols.metroProvider)
          } else {
            rawValueType
          },
        )
        .wrapInProvider(symbols.metroProvider)

    // TODO check if binding allows empty?
    if (size == 0) {
      // If it's empty then short-circuit here
      val emptyCallee =
        if (useProviderFactory) {
          // MapProviderFactory.empty()
          symbols.mapProviderFactoryEmptyFunction
        } else {
          // MapFactory.empty()
          symbols.mapFactoryEmptyFunction
        }

      return irInvoke(
        dispatchReceiver = irGetObject(targetCompanionObject),
        callee = emptyCallee,
        typeHint = mapProviderType,
      )
    }

    val builderFunction =
      if (useProviderFactory) {
        symbols.mapProviderFactoryBuilderFunction
      } else {
        symbols.mapFactoryBuilderFunction
      }
    val builderType =
      if (useProviderFactory) {
        symbols.mapProviderFactoryBuilder
      } else {
        symbols.mapFactoryBuilder
      }

    // MapFactory.<Integer, Integer>builder(2)
    // MapProviderFactory.<Integer, Integer>builder(2)
    val builder: IrExpression =
      irInvoke(
          dispatchReceiver = irGetObject(targetCompanionObject),
          callee = builderFunction,
          typeHint = builderType.typeWith(keyType, valueType),
        )
        .apply {
          putTypeArgument(0, keyType)
          putTypeArgument(1, valueType)
          putValueArgument(0, irInt(size))
        }

    val putFunction =
      if (useProviderFactory) {
        symbols.mapProviderFactoryBuilderPutFunction
      } else {
        symbols.mapFactoryBuilderPutFunction
      }
    val putAllFunction =
      if (useProviderFactory) {
        symbols.mapProviderFactoryBuilderPutAllFunction
      } else {
        symbols.mapFactoryBuilderPutAllFunction
      }

    val withProviders =
      binding.sourceBindings
        .map { generationContext.graph.requireBinding(it, generationContext.bindingStack) }
        .fold(builder) { receiver, sourceBinding ->
          val providerTypeMetadata = sourceBinding.contextualTypeKey

          // TODO FIR this should be an error actually
          val isMap =
            providerTypeMetadata.typeKey.type.rawType().symbol == context.irBuiltIns.mapClass

          val putter =
            if (isMap) {
              // use putAllFunction
              // .putAll(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
              // TODO is this only for inheriting in GraphExtensions?
              TODO("putAll isn't yet supported")
            } else {
              // .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
              putFunction
            }
          irInvoke(dispatchReceiver = receiver, callee = putter, typeHint = builder.type).apply {
            putValueArgument(0, generateMapKeyLiteral(sourceBinding))
            putValueArgument(
              1,
              generateBindingCode(sourceBinding, generationContext, fieldInitKey = fieldInitKey),
            )
          }
        }

    // .build()
    val buildFunction =
      if (useProviderFactory) {
        symbols.mapProviderFactoryBuilderBuildFunction
      } else {
        symbols.mapFactoryBuilderBuildFunction
      }

    return irInvoke(
      dispatchReceiver = withProviders,
      callee = buildFunction,
      typeHint = mapProviderType,
    )
  }

  private fun IrBuilderWithScope.generateMultibindingArgument(
    provider: Binding,
    generationContext: GraphGenerationContext,
    fieldInitKey: IrTypeKey?,
  ): IrExpression {
    val bindingCode = generateBindingCode(provider, generationContext, fieldInitKey = fieldInitKey)
    return typeAsProviderArgument(
      metroContext,
      contextKey = IrContextualTypeKey.create(provider.typeKey),
      bindingCode = bindingCode,
      isAssisted = false,
      isGraphInstance = false,
    )
  }
}

internal class GraphGenerationContext(
  val graph: IrBindingGraph,
  val thisReceiver: IrValueParameter,
  // TODO we can end up in awkward situations where we
  //  have the same type keys in both instance and provider fields
  //  this is tricky because depending on the context, it's not valid
  //  to use an instance (for example - you need a provider). How can we
  //  clean this up?
  val instanceFields: Map<IrTypeKey, IrField>,
  val providerFields: Map<IrTypeKey, IrField>,
  val multibindingProviderFields: Map<Binding.Provided, IrField>,
  val bindingStack: IrBindingStack,
) {
  // Each declaration in FIR is actually generated with a different "this" receiver, so we
  // need to be able to specify this per-context.
  // TODO not sure if this is really the best way to do this? Only necessary when implementing
  //  accessors/injectors
  fun withReceiver(receiver: IrValueParameter): GraphGenerationContext =
    GraphGenerationContext(
      graph,
      receiver,
      instanceFields,
      providerFields,
      multibindingProviderFields,
      bindingStack,
    )
}
