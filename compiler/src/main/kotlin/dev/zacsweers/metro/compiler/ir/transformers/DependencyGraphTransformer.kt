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
import dev.zacsweers.metro.compiler.ir.IrGraphGenerator
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
import dev.zacsweers.metro.compiler.ir.extensionReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.getAllSuperTypes
import dev.zacsweers.metro.compiler.ir.getConstBooleanArgumentOrNull
import dev.zacsweers.metro.compiler.ir.getSingleConstBooleanArgumentOrNull
import dev.zacsweers.metro.compiler.ir.hiddenDeprecated
import dev.zacsweers.metro.compiler.ir.irCallConstructorWithSameParameters
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irLambda
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.location
import dev.zacsweers.metro.compiler.ir.metroAnnotationsOf
import dev.zacsweers.metro.compiler.ir.metroFunctionOf
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.metroGraphOrNull
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
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
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
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
      (graphDeclaration.metroGraphOrNull ?: graphDeclaration)
        .getAllSuperTypes(pluginContext, excludeSelf = false)
        .memoized()

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
            .metroGraphOrFail // Doesn't cover contributed graphs but they're not visible anyway
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
            it.ir.regularParameters.isNotEmpty() ||
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
                  append("No function found for ")
                  appendLine(callableId)
                  callableId.classId?.let {
                    pluginContext.referenceClass(it)?.let {
                      appendLine("Class dump")
                      appendLine(it.owner.dumpKotlinLike())
                    }
                  }
                    ?: run {
                      append("No class found for ")
                      appendLine(callableId)
                    }
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
          supertypes = supertypes.toList(),
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

    val nonNullMetroGraph = metroGraph ?: graphDeclaration.metroGraphOrFail
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
              declaration.regularParameters.size == 1 &&
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
              IrTypeKey(
                symbols.metroMembersInjector.typeWith(declaration.regularParameters[0].type)
              )
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
            irCall(symbols.metroSingleInConstructor).apply { arguments[0] = scopeArg }
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
                  arguments[0] = scopeClassExpression
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
      ?.regularParameters
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
        supertypes = supertypes.toList(),
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
              append("Scope: ")
              appendLine(parentScope.render(short = false))
              append("Ancestor 1: ")
              appendLine(previous.sourceGraph.kotlinFqName)
              append("Ancestor 2: ")
              appendLine(depNode.sourceGraph.kotlinFqName)
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
          proto = dependencyGraphNodesByClass.getValue(parent.sourceGraph.classIdOrFail).proto
        }
        if (proto == null) {
          parent.sourceGraph.reportError(
            "Extended parent graph ${parent.sourceGraph.kotlinFqName} is missing Metro metadata. Was it compiled by the Metro compiler?"
          )
          exitProcessing()
        }
      }

      parentTracer.traceNested("Transform metro graph") { tracer ->
        IrGraphGenerator(
          metroContext,
          contributionData,
          dependencyGraphNodesByClass,
          providesTransformer,
          injectConstructorTransformer,
          membersInjectorTransformer,
          assistedFactoryTransformer
        )
          .generateMetroGraph(node, metroGraph, bindingGraph, result, tracer)
      }
    } catch (e: Exception) {
      if (e is ExitProcessingException) {
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

    // Mapping of supertypes to aliased bindings
    // We populate this for the current graph type first and then
    // add to them when processing extended parent graphs IFF there
    // is not already an existing entry. We do it this way to handle
    // cases where both the child graph and parent graph implement
    // a shared interface. In this scenario, the child alias wins
    // and we do not need to try to add another (duplicate) binding
    val superTypeToAlias = mutableMapOf<IrTypeKey, IrTypeKey>()

    // Add aliases for all its supertypes
    // TODO dedupe supertype iteration
    for (superType in node.supertypes) {
      val superTypeKey = IrTypeKey(superType)
      superTypeToAlias.putIfAbsent(superTypeKey, node.typeKey)
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

    // Add aliases ("@Binds")
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

    node.creator?.parameters?.regularParameters.orEmpty().forEach { creatorParam ->
      // Only expose the binding if it's a bound instance or extended graph. Included containers are
      // not directly available
      if (creatorParam.isBindsInstance || creatorParam.isExtends) {
        val paramTypeKey = creatorParam.typeKey
        graph.addBinding(paramTypeKey, Binding.BoundInstance(creatorParam), bindingStack)
      }
    }

    // Traverse all parent graph supertypes to create binding aliases as needed
    node.allExtendedNodes.forEach { (typeKey, extendedNode) ->
      // If it's a contributed graph, add an alias for the parent types since that's what
      // bindings will look for. i.e. $$ContributedLoggedInGraph -> LoggedInGraph + supertypes
      for (superType in extendedNode.supertypes) {
        val parentTypeKey = IrTypeKey(superType)

        // Ignore the graph declaration itself, handled separately
        if (parentTypeKey == typeKey) continue

        superTypeToAlias.putIfAbsent(parentTypeKey, typeKey)
      }
    }

    // Now that we've processed all supertypes
    superTypeToAlias.forEach { (superTypeKey, aliasedType) ->
      // We may have already added a `@Binds` declaration explicitly, this is ok!
      // TODO warning?
      if (superTypeKey !in graph) {
        graph.addBinding(
          superTypeKey,
          Binding.Alias(
            superTypeKey,
            aliasedType,
            null,
            Parameters.empty(),
            MetroAnnotations.none(),
          ),
          bindingStack,
        )
      }
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
      if (depNode.isExtendable) {
        depNode.proto?.let { proto ->
          val providerFieldAccessorsByName = mutableMapOf<Name, MetroSimpleFunction>()
          val instanceFieldAccessorsByName = mutableMapOf<Name, MetroSimpleFunction>()

          val providerFieldsSet = proto.provider_field_names.toSet()
          val instanceFieldsSet = proto.instance_field_names.toSet()

          val graphImpl = depNode.sourceGraph.metroGraphOrFail
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

          proto.provider_field_names.forEach { providerField ->
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

          proto.instance_field_names.forEach { instanceField ->
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
    }

    // Add MembersInjector bindings defined on injector functions
    node.injectors.forEach { (injector, typeKey) ->
      val contextKey = IrContextualTypeKey(typeKey)
      val entry = IrBindingStack.Entry.requestedAt(contextKey, injector.ir)

      graph.addInjector(typeKey, entry)
      bindingStack.withEntry(entry) {
        val targetClass = injector.ir.regularParameters.single().type.rawType()
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
              Parameters(
                injector.callableId,
                null,
                null,
                parameters.regularParameters,
                parameters.contextParameters,
                null,
              ),
            reportableLocation = injector.ir.location(),
            function = injector.ir,
            isFromInjectorFunction = true,
            targetClassId = targetClass.classIdOrFail,
          )

        graph.addBinding(typeKey, binding, bindingStack)
      }
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
