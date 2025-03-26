// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ExitProcessingException
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.PLUGIN_ID
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.Binding
import dev.zacsweers.metro.compiler.ir.BindingGraph
import dev.zacsweers.metro.compiler.ir.BindingStack
import dev.zacsweers.metro.compiler.ir.ContextualTypeKey
import dev.zacsweers.metro.compiler.ir.DependencyGraphNode
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.TypeKey
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
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irLambda
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.location
import dev.zacsweers.metro.compiler.ir.locationOrNull
import dev.zacsweers.metro.compiler.ir.metroAnnotationsOf
import dev.zacsweers.metro.compiler.ir.metroFunctionOf
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
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.stubExpression
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.timedComputation
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.ir.withEntry
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.memoized
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.split
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import org.jetbrains.kotlin.backend.common.lower.irThrow
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
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
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
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
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal class DependencyGraphData {
  val graphs = mutableMapOf<ClassId, DependencyGraphNode>()
}

internal class DependencyGraphTransformer(
  context: IrMetroContext,
  moduleFragment: IrModuleFragment,
) : IrElementTransformer<DependencyGraphData>, IrMetroContext by context {

  private val membersInjectorTransformer = MembersInjectorTransformer(context)
  private val injectConstructorTransformer =
    InjectConstructorTransformer(context, membersInjectorTransformer)
  private val assistedFactoryTransformer =
    AssistedFactoryTransformer(context, injectConstructorTransformer)
  private val providesTransformer = ProvidesTransformer(context)
  private val contributionHintIrTransformer = ContributionHintIrTransformer(context, moduleFragment)

  // Keyed by the source declaration
  private val dependencyGraphNodesByClass = mutableMapOf<ClassId, DependencyGraphNode>()

  // Keyed by the source declaration
  private val metroDependencyGraphsByClass = mutableMapOf<ClassId, IrClass>()

  override fun visitCall(expression: IrCall, data: DependencyGraphData): IrElement {
    // Covers replacing createGraphFactory() compiler intrinsics with calls to the real
    // graph factory
    val callee = expression.symbol.owner
    when (callee.symbol) {
      symbols.metroCreateGraphFactory -> {
        // Get the called type
        val type =
          expression.getTypeArgument(0)
            ?: error("Missing type argument for ${symbols.metroCreateGraphFactory.owner.name}")
        val rawType = type.rawType()
        if (!rawType.isAnnotatedWithAny(symbols.dependencyGraphFactoryAnnotations)) {
          // TODO FIR error
          error(
            "Cannot create a graph factory instance of non-factory type ${rawType.kotlinFqName}"
          )
        }
        val parentDeclaration = rawType.parentAsClass
        val companion = parentDeclaration.companionObject()!!

        // If there's no $$Impl class, the companion object is the impl
        val companionIsTheFactory =
          companion.implements(pluginContext, rawType.classIdOrFail) &&
            rawType.nestedClasses.singleOrNull { it.name == Symbols.Names.metroImpl } == null

        if (companionIsTheFactory) {
          return pluginContext.createIrBuilder(expression.symbol).run {
            irGetObject(companion.symbol)
          }
        } else {
          val factoryFunction =
            companion.functions.single {
              // Note we don't filter on Origins.MetroGraphFactoryCompanionGetter, because
              // sometimes a user may have already defined one. An FIR checker will validate that
              // any such function is
              // valid, so just trust it if one is found
              it.name == Symbols.Names.factoryFunctionName
            }
          // Replace it with a call directly to the factory function
          return pluginContext.createIrBuilder(expression.symbol).run {
            irCall(callee = factoryFunction.symbol, type = type).apply {
              dispatchReceiver = irGetObject(companion.symbol)
            }
          }
        }
      }

      symbols.metroCreateGraph -> {
        // Get the called type
        val type =
          expression.getTypeArgument(0)
            ?: error("Missing type argument for ${symbols.metroCreateGraph.owner.name}")
        val rawType = type.rawType()
        if (!rawType.isAnnotatedWithAny(symbols.dependencyGraphAnnotations)) {
          // TODO FIR error
          error(
            "Cannot create an dependency graph instance of non-graph type ${rawType.kotlinFqName}"
          )
        }
        val companion = rawType.companionObject()!!
        val factoryFunction =
          companion.functions.single {
            it.origin == Origins.MetroGraphCreatorsObjectInvokeDeclaration
          }
        // Replace it with a call directly to the create function
        return pluginContext.createIrBuilder(expression.symbol).run {
          irCall(callee = factoryFunction.symbol, type = type).apply {
            dispatchReceiver = irGetObject(companion.symbol)
          }
        }
      }
    }

    return super.visitCall(expression, data)
  }

  override fun visitClass(declaration: IrClass, data: DependencyGraphData): IrStatement {
    log("Reading <$declaration>")

    // TODO need to better divvy these
    // TODO can we eagerly check for known metro types and skip?
    contributionHintIrTransformer.visitClass(declaration)
    membersInjectorTransformer.visitClass(declaration)
    injectConstructorTransformer.visitClass(declaration)
    assistedFactoryTransformer.visitClass(declaration)
    providesTransformer.visitClass(declaration)

    val dependencyGraphAnno =
      declaration.annotationsIn(symbols.dependencyGraphAnnotations).singleOrNull()
    if (dependencyGraphAnno == null) return super.visitClass(declaration, data)

    try {
      getOrBuildDependencyGraph(declaration, dependencyGraphAnno)
    } catch (_: ExitProcessingException) {
      // End processing, don't fail up because this would've been warned before
    }

    // TODO dump option to detect unused

    return super.visitClass(declaration, data)
  }

  private fun getOrComputeDependencyGraphNode(
    graphDeclaration: IrClass,
    bindingStack: BindingStack,
    metroGraph: IrClass? = null,
    dependencyGraphAnno: IrConstructorCall? = null,
  ): DependencyGraphNode {
    val graphClassId = graphDeclaration.classIdOrFail
    dependencyGraphNodesByClass[graphClassId]?.let {
      return it
    }

    val dependencyGraphAnno =
      dependencyGraphAnno
        ?: graphDeclaration.annotationsIn(symbols.dependencyGraphAnnotations).singleOrNull()
    val isGraph = dependencyGraphAnno != null
    val supertypes =
      graphDeclaration.getAllSuperTypes(pluginContext, excludeSelf = false).memoized()

    val accessors = mutableListOf<Pair<MetroSimpleFunction, ContextualTypeKey>>()
    val bindsFunctions = mutableListOf<Pair<MetroSimpleFunction, ContextualTypeKey>>()
    val scopes = mutableSetOf<IrAnnotation>()
    val providerFactories = mutableListOf<Pair<TypeKey, ProviderFactory>>()

    val isExtendable =
      dependencyGraphAnno?.getConstBooleanArgumentOrNull(Symbols.Names.isExtendable) == true

    if (graphDeclaration.isExternalParent || !isGraph) {
      val accessorsToCheck =
        if (isGraph) {
          // It's just an external graph, just read the declared types from it
          graphDeclaration
            .requireNestedClass(Symbols.Names.metroGraph)
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
          .map { it to ContextualTypeKey.from(metroContext, it.ir, it.annotations) }

      // Read metadata if this is an extendable graph
      if (isExtendable) {
        val serialized =
          pluginContext.metadataDeclarationRegistrar.getCustomMetadataExtension(
            graphDeclaration.requireNestedClass(Symbols.Names.metroGraph),
            PLUGIN_ID,
          )
        if (serialized == null) {
          reportError(
            "Missing metadata for extendable graph ${graphDeclaration.kotlinFqName}. Was this compiled by the Metro compiler?",
            graphDeclaration.location(),
          )
          exitProcessing()
        }

        val metadata = MetroMetadata.ADAPTER.decode(serialized)
        val graphProto = metadata.dependency_graph
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
          graphProto.binds_callable_ids.map { callableIdString ->
            val (classIdString, name) =
              callableIdString.split(callableIdString.indexOfLast { it == '.' })
            val callableId = CallableId(ClassId.fromString(classIdString), name.asName())

            val function = pluginContext.referenceFunctions(callableId).single().owner

            val metroFunction = metroFunctionOf(function)
            metroFunction to ContextualTypeKey.from(this, function, metroFunction.annotations)
          }
        )

        // Read scopes from annotations
        // We copy scope annotations from parents onto this graph if it's extendable so we only need
        // to copy once
        scopes.addAll(graphDeclaration.scopeAnnotations())
      }

      val dependentNode =
        DependencyGraphNode(
          sourceGraph = graphDeclaration,
          isExtendable = isExtendable,
          dependencies = emptyMap(),
          scopes = scopes,
          providerFactories = providerFactories,
          accessors = accessors,
          bindsFunctions = bindsFunctions,
          injectors = emptyList(), // Unnecessary to us here
          isExternal = true,
          creator = null,
          typeKey = TypeKey(graphDeclaration.typeWith()),
        )

      dependencyGraphNodesByClass[graphClassId] = dependentNode

      return dependentNode
    }

    val nonNullMetroGraph =
      metroGraph ?: graphDeclaration.requireNestedClass(Symbols.Names.metroGraph)
    val graphTypeKey = TypeKey(graphDeclaration.typeWith())
    val graphContextKey =
      ContextualTypeKey(
        graphTypeKey,
        isWrappedInProvider = false,
        isWrappedInLazy = false,
        isLazyWrappedInProvider = false,
        hasDefault = false,
      )

    val injectors = mutableListOf<Pair<MetroSimpleFunction, ContextualTypeKey>>()

    for (declaration in nonNullMetroGraph.declarations) {
      if (!declaration.isFakeOverride) continue
      if (declaration is IrFunction && declaration.isFakeOverriddenFromAny()) continue
      val annotations = metroAnnotationsOf(declaration)
      if (annotations.isProvides) continue
      when (declaration) {
        is IrSimpleFunction -> {
          // Could be an injector or accessor

          // If the overridden symbol has a default getter/value then skip
          var hasDefaultImplementation = false
          for (overridden in declaration.overriddenSymbolsSequence()) {
            if (overridden.owner.body != null) {
              hasDefaultImplementation = true
              break
            }
          }
          if (hasDefaultImplementation) continue

          if (declaration.valueParameters.isNotEmpty() && !annotations.isBinds) {
            // It's an injector
            val metroFunction = metroFunctionOf(declaration, annotations)
            val contextKey =
              ContextualTypeKey.from(
                context = this,
                function = declaration,
                annotations = metroFunction.annotations,
                type = declaration.valueParameters.first().type,
              )
            injectors += (metroFunction to contextKey)
          } else {
            // Accessor or binds
            val metroFunction = metroFunctionOf(declaration, annotations)
            val contextKey = ContextualTypeKey.from(this, declaration, metroFunction.annotations)
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
          // Can only be an accessor or binds

          // If the overridden symbol has a default getter/value then skip
          var hasDefaultImplementation = false
          for (overridden in declaration.overriddenSymbolsSequence()) {
            if (overridden.owner.getter?.body != null) {
              hasDefaultImplementation = true
              break
            }
          }
          if (hasDefaultImplementation) continue

          val getter = declaration.getter!!
          val metroFunction = metroFunctionOf(getter, annotations)
          val contextKey = ContextualTypeKey.from(this, getter, metroFunction.annotations)
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

    scopes += buildSet {
      val scope =
        dependencyGraphAnno.getValueArgument("scope".asName())?.let { scopeArg ->
          pluginContext.createIrBuilder(graphDeclaration.symbol).run {
            irCall(symbols.metroSingleInConstructor).apply { putValueArgument(0, scopeArg) }
          }
        }

      if (scope != null) {
        add(IrAnnotation(scope))
        dependencyGraphAnno
          .getValueArgument("additionalScopes".asName())
          ?.expectAs<IrVararg>()
          ?.elements
          ?.forEach {
            val scopeClassExpression = it.expectAs<IrExpression>()
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
      val inheritedScopes = scopes - scopesOnType
      pluginContext.metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(
        graphDeclaration,
        inheritedScopes.map { it.ir },
      )
    }

    // TODO since we already check this in FIR can we leave a more specific breadcrumb somewhere
    val creator =
      graphDeclaration.nestedClasses
        .singleOrNull { klass ->
          klass.isAnnotatedWithAny(symbols.dependencyGraphFactoryAnnotations)
        }
        ?.let { factory ->
          // Validated in FIR so we can assume we'll find just one here
          val createFunction = factory.singleAbstractFunction(this)
          DependencyGraphNode.Creator(factory, createFunction, createFunction.parameters(this))
        }

    try {
      checkGraphSelfCycle(graphDeclaration, graphTypeKey, bindingStack)
    } catch (e: ExitProcessingException) {
      implementCreatorFunctions(graphDeclaration, creator, nonNullMetroGraph)
      throw e
    }

    val graphDependencies =
      creator
        ?.parameters
        ?.valueParameters
        .orEmpty()
        .filter { !it.isBindsInstance }
        .associate {
          val type = it.typeKey.type.rawType()
          val node =
            bindingStack.withEntry(
              BindingStack.Entry.requestedAt(graphContextKey, creator!!.createFunction)
            ) {
              getOrComputeDependencyGraphNode(type, bindingStack)
            }
          it.typeKey to node
        }

    for ((_, depNode) in graphDependencies) {
      if (depNode.isExtendable) {
        scopes += depNode.scopes
      }
    }

    val dependencyGraphNode =
      DependencyGraphNode(
        sourceGraph = graphDeclaration,
        isExtendable = isExtendable,
        dependencies = graphDependencies,
        scopes = scopes,
        bindsFunctions = bindsFunctions,
        providerFactories = providerFactories,
        accessors = accessors,
        injectors = injectors,
        isExternal = false,
        creator = creator,
        typeKey = graphTypeKey,
      )
    dependencyGraphNodesByClass[graphClassId] = dependencyGraphNode
    return dependencyGraphNode
  }

  private fun getOrBuildDependencyGraph(
    dependencyGraphDeclaration: IrClass,
    dependencyGraphAnno: IrConstructorCall,
  ): IrClass {
    val graphClassId = dependencyGraphDeclaration.classIdOrFail
    metroDependencyGraphsByClass[graphClassId]?.let {
      return it
    }

    val metroGraph =
      dependencyGraphDeclaration.nestedClasses.singleOrNull { it.name == Symbols.Names.metroGraph }
        ?: error("Expected generated dependency graph for $graphClassId")

    if (dependencyGraphDeclaration.isExternalParent) {
      // Externally compiled, look up its generated class
      metroDependencyGraphsByClass[graphClassId] = metroGraph
    }

    val node =
      getOrComputeDependencyGraphNode(
        dependencyGraphDeclaration,
        BindingStack(
          dependencyGraphDeclaration,
          metroContext.loggerFor(MetroLogger.Type.GraphNodeConstruction),
        ),
        metroGraph,
        dependencyGraphAnno,
      )

    // Generate creator functions
    implementCreatorFunctions(node.sourceGraph, node.creator, metroGraph)

    val bindingGraph = createBindingGraph(node)

    val platformName =
      pluginContext.platform?.let { platform ->
        platform.componentPlatforms.joinToString("-") { it.platformName }
      }

    val reportsDir =
      options.reportsDestination
        ?.letIf(platformName != null) { it.resolve(platformName!!) }
        ?.createDirectories()

    try {
      checkGraphSelfCycle(
        dependencyGraphDeclaration,
        node.typeKey,
        BindingStack(node.sourceGraph, loggerFor(MetroLogger.Type.CycleDetection)),
      )

      val deferredTypes =
        bindingGraph.validate(node) { message ->
          dependencyGraphDeclaration.reportError(message)
          exitProcessing()
        }

      reportsDir
        ?.resolve("graph-dump-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.txt")
        ?.apply { deleteIfExists() }
        ?.writeText(bindingGraph.dumpGraph(node.sourceGraph.kotlinFqName.asString(), short = false))

      generateMetroGraph(node, metroGraph, bindingGraph, deferredTypes)
    } catch (e: Exception) {
      if (e is ExitProcessingException) {
        throw e
      }
      throw AssertionError(
        "Code gen exception while processing ${dependencyGraphDeclaration.classIdOrFail}",
        e,
      )
    }

    metroDependencyGraphsByClass[graphClassId] = metroGraph

    metroGraph.dumpToMetroLog()

    reportsDir
      ?.resolve("graph-dumpKotlin-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt")
      ?.apply { deleteIfExists() }
      ?.writeText(metroGraph.dumpKotlinLike())

    return metroGraph
  }

  private fun checkGraphSelfCycle(
    graphDeclaration: IrClass,
    graphTypeKey: TypeKey,
    bindingStack: BindingStack,
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

  private fun createBindingGraph(node: DependencyGraphNode): BindingGraph {
    val graph = BindingGraph(this)

    // Add explicit bindings from @Provides methods
    val bindingStack =
      BindingStack(
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
      val superTypeKey = TypeKey(superType)
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
    node.creator?.parameters?.valueParameters.orEmpty().forEach {
      graph.addBinding(it.typeKey, Binding.BoundInstance(it), bindingStack)
    }

    val providerFactoriesToAdd = buildList {
      addAll(node.providerFactories)
      addAll(node.allDependencies.filter { it.isExtendable }.flatMap { it.providerFactories })
    }
    providerFactoriesToAdd.forEach { (typeKey, providerFactory) ->
      val parameters = providerFactory.parameters
      val contextKey = ContextualTypeKey(typeKey)

      val provider =
        Binding.Provided(
          providerFactory = providerFactory,
          contextualTypeKey = contextKey,
          parameters = parameters,
          annotations = providerFactory.annotations,
        )

      if (provider.isIntoMultibinding) {
        val multibindingType =
          when {
            provider.intoSet -> {
              pluginContext.irBuiltIns.setClass.typeWith(provider.typeKey.type)
            }

            provider.elementsIntoSet -> provider.typeKey.type
            provider.intoMap -> {
              val mapKey =
                provider.mapKey
                  ?: run {
                    // Hard error because the FIR checker should catch these, so this implies broken
                    // FIR code gen
                    error(
                      "Missing @MapKey for @IntoMap function: ${providerFactory.providesFunction.dumpKotlinLike()}"
                    )
                  }
              // TODO this is probably not robust enough
              val rawKeyType = mapKey.ir
              val unwrapValues = rawKeyType.shouldUnwrapMapKeyValues()
              val keyType =
                if (unwrapValues) {
                  rawKeyType.annotationClass.primaryConstructor!!.valueParameters[0].type
                } else {
                  rawKeyType.type
                }
              pluginContext.irBuiltIns.mapClass.typeWith(
                // MapKey is the key type
                keyType,
                // Return type is the value type
                provider.typeKey.type.removeAnnotations(),
              )
            }

            else -> {
              error("Unrecognized provider: ${providerFactory.providesFunction.dumpKotlinLike()}")
            }
          }
        val multibindingTypeKey = provider.typeKey.copy(type = multibindingType)
        graph
          .getOrCreateMultibinding(pluginContext, multibindingTypeKey, bindingStack)
          .sourceBindings
          .add(provider)
      } else {
        graph.addBinding(typeKey, provider, bindingStack)
      }
    }

    node.accessors.forEach { (getter, contextualTypeKey) ->
      val isMultibindingDeclaration = getter.annotations.isMultibinds

      if (isMultibindingDeclaration) {
        // Special case! Multibindings may be created under two conditions
        // 1. Explicitly via `@Multibinds`
        // 2. Implicitly via a `@Provides` callable that contributes into a multibinding
        // Because these may both happen, if the key already exists in the graph we won't try to add
        // it again
        val multibinding =
          Binding.Multibinding.create(metroContext, contextualTypeKey.typeKey, getter.ir)
        if (multibinding.typeKey !in graph) {
          graph.addBinding(contextualTypeKey.typeKey, multibinding, bindingStack)
        }
      } else {
        graph.addAccessor(
          contextualTypeKey,
          BindingStack.Entry.requestedAt(contextualTypeKey, getter.ir),
        )
      }
    }

    // Add bindings from graph dependencies
    // TODO dedupe this allDependencies iteration with graph gen
    node.allDependencies.forEach { depNode ->
      for ((getter, contextualTypeKey) in depNode.accessors) {
        if (depNode.isExtendable && getter in depNode.multibindingAccessors) {
          // Ignore exposed multibindings, we will aggregate them in our own graph
          println("Ignoring multibinding $getter")
          continue
        }
        graph.addBinding(
          contextualTypeKey.typeKey,
          Binding.GraphDependency(
            graph = depNode.sourceGraph,
            getter = getter.ir,
            typeKey = contextualTypeKey.typeKey,
          ),
          bindingStack,
        )
      }
      if (depNode.isExtendable && depNode.proto != null) {
        val providerFieldAccessorsByName = mutableMapOf<Name, MetroSimpleFunction>()
        val instanceFieldAccessorsByName = mutableMapOf<Name, MetroSimpleFunction>()

        val graphImpl = depNode.sourceGraph.requireNestedClass(Symbols.Names.metroGraph)
        for (accessor in graphImpl.functions) {
          when (accessor.origin) {
            Origins.ProviderFieldAccessor -> {
              val metroFunction = metroFunctionOf(accessor)
              providerFieldAccessorsByName[metroFunction.ir.name] = metroFunction
            }
            Origins.InstanceFieldAccessor -> {
              val metroFunction = metroFunctionOf(accessor)
              instanceFieldAccessorsByName[metroFunction.ir.name] = metroFunction
            }
          }
        }

        depNode.proto.provider_field_names.forEach { providerField ->
          val accessor =
            providerFieldAccessorsByName.getValue("${providerField}_metroAccessor".asName())
          val contextualTypeKey = ContextualTypeKey.from(this, accessor.ir, accessor.annotations)
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
              typeKey = contextualTypeKey.typeKey,
            ),
            bindingStack,
          )
        }
      }
    }

    // Add MembersInjector bindings defined on injector functions
    node.injectors.forEach { (injector, contextualTypeKey) ->
      val entry = BindingStack.Entry.requestedAt(contextualTypeKey, injector.ir)

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

        val membersInjectorKey =
          ContextualTypeKey(
            typeKey =
              TypeKey(symbols.metroMembersInjector.typeWith(contextualTypeKey.typeKey.type)),
            isWrappedInProvider = false,
            isWrappedInLazy = false,
            isLazyWrappedInProvider = false,
            hasDefault = false,
          )

        val binding =
          Binding.MembersInjected(
            membersInjectorKey,
            // Need to look up the injector class and gather all params
            parameters =
              Parameters(injector.callableId, null, null, parameters.valueParameters, null),
            reportableLocation = injector.ir.location(),
            function = injector.ir,
            isFromInjectorFunction = true,
            targetClassId = targetClass.classIdOrFail,
          )

        graph.addBinding(membersInjectorKey.typeKey, binding, bindingStack)
      }
    }

    // Add aliases ("@Binds"). Important this runs last so it can resolve aliases
    val bindsFunctionsToAdd = buildList {
      addAll(node.bindsFunctions)
      addAll(node.allDependencies.filter { it.isExtendable }.flatMap { it.bindsFunctions })
    }
    bindsFunctionsToAdd.forEach { (bindingCallable, contextKey) ->
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

      val binding =
        Binding.Alias(
          contextKey.typeKey,
          bindsImplType!!.typeKey,
          bindingCallable.ir,
          parameters,
          annotations,
        )

      if (annotations.isIntoMultibinding) {
        val multibindingType =
          when {
            annotations.isIntoSet -> {
              pluginContext.irBuiltIns.setClass.typeWith(contextKey.typeKey.type)
            }

            annotations.isElementsIntoSet -> contextKey.typeKey.type
            annotations.isIntoMap -> {
              val mapKey =
                annotations.mapKeys.firstOrNull()
                  ?: run {
                    // Hard error because the FIR checker should catch these, so this implies broken
                    // FIR code gen
                    error(
                      "Missing @MapKey for @IntoMap function: ${bindingCallable.ir.locationOrNull()}"
                    )
                  }
              // TODO this is probably not robust enough
              val rawKeyType = mapKey.ir
              val unwrapValues = rawKeyType.shouldUnwrapMapKeyValues()
              val keyType =
                if (unwrapValues) {
                  rawKeyType.annotationClass.primaryConstructor!!.valueParameters[0].type
                } else {
                  rawKeyType.type
                }
              pluginContext.irBuiltIns.mapClass.typeWith(
                // MapKey is the key type
                keyType,
                // Return type is the value type
                contextKey.typeKey.type.removeAnnotations(),
              )
            }

            else -> {
              error("Unrecognized provider: ${bindingCallable.ir.locationOrNull()}")
            }
          }
        val multibindingTypeKey = contextKey.typeKey.copy(type = multibindingType)
        graph
          .getOrCreateMultibinding(pluginContext, multibindingTypeKey, bindingStack)
          .sourceBindings
          .add(binding)
      } else {
        graph.addBinding(binding.typeKey, binding, bindingStack)
      }
      // Resolve aliased binding
      binding.aliasedBinding(graph, bindingStack)
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
    val companionObject = sourceGraph.companionObject()!!
    if (creator != null) {
      val implementFactoryFunction: IrClass.() -> Unit = {
        requireSimpleFunction(creator.createFunction.name.asString()).owner.apply {
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
        if (creator.type.isInterface) {
          // Implement the interface creator function directly in this companion object
          implementFactoryFunction()
        } else {
          // Implement the factory's $$Impl class
          val factoryClass =
            creator.type.requireNestedClass(Symbols.Names.metroImpl).apply(implementFactoryFunction)

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
    bindingGraph: BindingGraph,
    deferredTypes: Set<TypeKey>,
  ) =
    with(graphClass) {
      val ctor = primaryConstructor!!

      // Fields for providers. May include both scoped and unscoped providers as well as bound
      // instances
      val providerFields = mutableMapOf<TypeKey, IrField>()
      val multibindingProviderFields = mutableMapOf<Binding.Provided, IrField>()
      val fieldNameAllocator = dev.zacsweers.metro.compiler.NameAllocator()
      val extraConstructorStatements = mutableListOf<IrBuilderWithScope.() -> IrStatement>()

      // Fields for this graph and other instance params
      val instanceFields = mutableMapOf<TypeKey, IrField>()

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
            val graphDep = node.dependencies.getValue(param.typeKey)
            if (graphDep.isExtendable) {
              // Extended graphs
              addBoundInstanceField { irGet(irParam) }
              instanceFields[graphDep.typeKey] =
                addSimpleInstanceField(
                  fieldNameAllocator.newName(
                    graphDep.sourceGraph.name.asString().decapitalizeUS() + "Instance"
                  ),
                  graphDep.typeKey.type,
                  { irGet(irParam) },
                )
              // Check that the input parameter is an instance of the metrograph class
              val depMetroGraph = graphDep.sourceGraph.requireNestedClass(Symbols.Names.metroGraph)
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

      val thisReceiverParameter = thisReceiverOrFail
      val thisGraphField =
        addSimpleInstanceField(
          fieldNameAllocator.newName("thisGraphInstance"),
          thisReceiverParameter.type,
          { irGet(thisReceiverParameter) },
        )

      instanceFields[node.typeKey] = thisGraphField
      // Add convenience mappings for all supertypes to this field so
      // instance providers from inherited types use this instance
      for (superType in node.sourceGraph.getAllSuperTypes(pluginContext)) {
        instanceFields[TypeKey(superType)] = thisGraphField
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
      // TODO only add if not in providerFields already. May be a bound instance
      for (parent in node.allDependencies) {
        if (!parent.isExtendable) continue
        val parentMetroGraph = parent.sourceGraph.requireNestedClass(Symbols.Names.metroGraph)
        val instanceAccessors =
          parentMetroGraph.functions
            .filter { it.origin == Origins.InstanceFieldAccessor }
            .map {
              val metroFunction = metroFunctionOf(it)
              val contextKey = ContextualTypeKey.from(metroContext, it, metroFunction.annotations)
              metroFunction to contextKey
            }
        for ((accessor, contextualTypeKey) in instanceAccessors) {
          val field =
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
                            if (rawType?.origin == Origins.MetroGraphDeclaration) {
                              // if it's a $$MetroGraph, we actually want the parent type
                              rawType.parentAsClass.defaultType
                            } else {
                              it
                            }
                          }
                          .let(::TypeKey)
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
        BindingStack(node.sourceGraph, metroContext.loggerFor(MetroLogger.Type.GraphImplCodeGen))

      // First pass: collect bindings and their dependencies for provider field ordering
      // Note we do this in two passes rather than keep a TreeMap because otherwise we'd be doing
      // dependency lookups at each insertion
      val bindingDependencies = collectBindings(node, bindingGraph, bindingStack)

      // Compute safe initialization order
      val initOrder =
        bindingDependencies.keys
          .sortedWith { a, b ->
            when {
              // If b depends on a, a should be initialized first
              a in (bindingDependencies[b]?.dependencies.orEmpty()) -> -1
              // If a depends on b, b should be initialized first
              b in (bindingDependencies[a]?.dependencies.orEmpty()) -> 1
              // Otherwise order doesn't matter, fall back to just type order for idempotence
              else -> a.compareTo(b)
            }
          }
          .map { bindingDependencies.getValue(it) }
          .distinct()

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
          if (getter.origin == Origins.ProviderFieldAccessor) {
            // Init a provider field pointing at this
            providerFields[key] =
              addField(
                  fieldName =
                    fieldNameAllocator.newName(
                      "${getter.name.asString().decapitalizeUS().removeSuffix("_metroAccessor")}Provider"
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
      val deferredFields: Map<TypeKey, IrField> =
        deferredTypes
          .associateWith { deferredTypeKey ->
            val binding = bindingDependencies[deferredTypeKey] ?: return@associateWith null
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
          .filterValues { it != null } as Map<TypeKey, IrField>

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

      implementOverrides(node.accessors, node.bindsFunctions, node.injectors, baseGenerationContext)

      if (node.isExtendable) {
        timedComputation("Generating Metro metadata") {
          // Finally, generate metadata
          val graphProto =
            node.toProto(
              bindingGraph = bindingGraph,
              parentGraphs =
                node.allDependencies
                  .filter { it.isExtendable }
                  .mapToSet { it.sourceGraph.classIdOrFail.asString() },
              providerFields =
                providerFields
                  .filterKeys { typeKey -> typeKey != node.typeKey }
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
          val metroMetadata = MetroMetadata(graphProto)
          val serialized = MetroMetadata.ADAPTER.encode(metroMetadata)
          pluginContext.metadataDeclarationRegistrar.addCustomMetadataExtension(
            graphClass,
            PLUGIN_ID,
            serialized,
          )
          dependencyGraphNodesByClass[node.sourceGraph.classIdOrFail] =
            node.copy(proto = graphProto)
        }
        // TODO dedup logic below
        // Expose getters for provider fields and expose them to metadata
        for ((key, field) in providerFields) {
          if (key == node.typeKey) continue // Skip the graph instance field
          val binding = bindingGraph.requireBinding(key, bindingStack)
          val getter =
            addFunction(
                name = "${field.name.asString()}_metroAccessor",
                returnType = field.type,
                // TODO is this... ok?
                visibility = DescriptorVisibilities.INTERNAL,
                origin = Origins.ProviderFieldAccessor,
              )
              .apply {
                // TODO add deprecation + hidden annotation to hide? Not sure if necessary
                body =
                  pluginContext.createIrBuilder(symbol).run {
                    irExprBodySafe(
                      symbol,
                      generateBindingCode(
                        binding,
                        baseGenerationContext.withReceiver(dispatchReceiverParameter!!),
                      ),
                    )
                  }
              }
          pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(getter)
        }
        for ((key, field) in instanceFields) {
          if (key == node.typeKey) continue // Skip this graph instance field
          val getter =
            addFunction(
                name = "${field.name.asString()}_metroAccessor",
                returnType = field.type,
                // TODO is this... ok?
                visibility = DescriptorVisibilities.INTERNAL,
                origin = Origins.InstanceFieldAccessor,
              )
              .apply {
                // TODO add deprecation + hidden annotation to hide? Not sure if necessary
                body =
                  pluginContext.createIrBuilder(symbol).run {
                    irExprBodySafe(symbol, irGetField(irGet(dispatchReceiverParameter!!), field))
                  }
              }
          pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(getter)
        }
      }
    }

  private fun DependencyGraphNode.toProto(
    bindingGraph: BindingGraph,
    parentGraphs: Set<String>,
    providerFields: List<String>,
    instanceFields: List<String>,
  ): DependencyGraphProto {
    val bindsCallableIds =
      bindingGraph.bindingsSnapshot().values.filterIsInstance<Binding.Alias>().mapNotNull { binding
        ->
        binding.ir?.name?.asString()
      }

    var multibindingAccessors = 0
    val accessorIds =
      accessors
        .sortedBy { it.first.ir.name.asString() }
        .onEachIndexed { index, (_, contextKey) ->
          val isMultibindingAccessor =
            bindingGraph.requireBinding(contextKey.typeKey, BindingStack.empty()) is
              Binding.Multibinding
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
      binds_callable_ids = bindsCallableIds.sorted(),
      accessor_callable_ids = accessorIds,
      parent_graph_classes = parentGraphs.sorted(),
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

  private fun implementOverrides(
    accessors: List<Pair<MetroSimpleFunction, ContextualTypeKey>>,
    bindsFunctions: List<Pair<MetroSimpleFunction, ContextualTypeKey>>,
    injectors: List<Pair<MetroSimpleFunction, ContextualTypeKey>>,
    context: GraphGenerationContext,
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
        val binding = context.graph.requireBinding(contextualTypeKey.typeKey, context.bindingStack)
        context.bindingStack.push(BindingStack.Entry.requestedAt(contextualTypeKey, function.ir))
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
    injectors.forEach { (overriddenFunction, contextualTypeKey) ->
      overriddenFunction.ir.apply {
        finalizeFakeOverride(context.thisReceiver)
        val targetParam = valueParameters[0]
        val membersInjectorKey =
          TypeKey(symbols.metroMembersInjector.typeWith(contextualTypeKey.typeKey.type))
        val binding =
          context.graph.requireBinding(membersInjectorKey, context.bindingStack)
            as Binding.MembersInjected
        context.bindingStack.push(BindingStack.Entry.requestedAt(contextualTypeKey, this))

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
                          context.graph.getOrCreateBinding(
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
  }

  private fun collectBindings(
    node: DependencyGraphNode,
    graph: BindingGraph,
    bindingStack: BindingStack,
  ): Map<TypeKey, Binding> {
    val bindingDependencies = mutableMapOf<TypeKey, Binding>()
    // Track used unscoped bindings. We only need to generate a field if they're used more than
    // once
    val usedUnscopedBindings = mutableSetOf<TypeKey>()
    val visitedBindings = mutableSetOf<TypeKey>()

    // Collect from roots
    node.accessors.forEach { (accessor, contextualTypeKey) ->
      findAndProcessBinding(
        contextKey = contextualTypeKey,
        stackEntry = BindingStack.Entry.requestedAt(contextualTypeKey, accessor.ir),
        node = node,
        graph = graph,
        bindingStack = bindingStack,
        bindingDependencies = bindingDependencies,
        usedUnscopedBindings = usedUnscopedBindings,
        visitedBindings = visitedBindings,
      )
    }
    return bindingDependencies
  }

  private fun findAndProcessBinding(
    contextKey: ContextualTypeKey,
    stackEntry: BindingStack.Entry,
    node: DependencyGraphNode,
    graph: BindingGraph,
    bindingStack: BindingStack,
    bindingDependencies: MutableMap<TypeKey, Binding>,
    usedUnscopedBindings: MutableSet<TypeKey>,
    visitedBindings: MutableSet<TypeKey>,
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
      val binding = graph.getOrCreateBinding(contextKey, bindingStack)
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
    graph: BindingGraph,
    bindingStack: BindingStack,
    bindingDependencies: MutableMap<TypeKey, Binding>,
    usedUnscopedBindings: MutableSet<TypeKey>,
    visitedBindings: MutableSet<TypeKey>,
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
          BindingStack.Entry.simpleTypeRef(
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
        bindingDependencies[key] = binding.target
        // TODO is this safe to end up as a provider field? Can someone create a
        //  binding such that you have an assisted type on the DI graph that is
        //  provided by a provider that depends on the assisted factory? I suspect
        //  yes, so in that case we should probably track a separate field mapping
        usedUnscopedBindings += binding.target.typeKey
        // By definition, these parameters are not available on the graph
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
          for (provider in binding.sourceBindings) {
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
          - ${function.valueParameters.map { ContextualTypeKey.from(metroContext,it).typeKey }.joinToString()}
        """
          .trimIndent()
      }
    }

    return params.valueParameters.mapIndexed { i, param ->
      val contextualTypeKey = paramsToMap[i].contextualTypeKey
      val typeKey = contextualTypeKey.typeKey

      val metroProviderSymbols = symbols.providerSymbolsFor(contextualTypeKey)

      // TODO consolidate this logic with generateBindingCode
      generationContext.instanceFields[typeKey]?.let { instanceField ->
        // If it's in instance field, invoke that field
        return@mapIndexed irGetField(irGet(generationContext.thisReceiver), instanceField).let {
          with(metroProviderSymbols) { transformMetroProvider(it, contextualTypeKey) }
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
                BindingStack.Entry.injectedAt(
                  contextualTypeKey,
                  constructor,
                  constructor.valueParameters[i],
                )
              }

              is Binding.ObjectClass -> error("Object classes cannot have dependencies")

              is Binding.Provided,
              is Binding.Alias -> {
                BindingStack.Entry.injectedAt(
                  contextualTypeKey,
                  function,
                  function.valueParameters[i],
                )
              }

              is Binding.Assisted -> {
                BindingStack.Entry.injectedAt(contextualTypeKey, function)
              }

              is Binding.MembersInjected -> {
                BindingStack.Entry.injectedAt(contextualTypeKey, function)
              }

              is Binding.Multibinding -> {
                // TODO can't be right?
                BindingStack.Entry.injectedAt(contextualTypeKey, function)
              }

              is Binding.Absent,
              is Binding.BoundInstance,
              is Binding.GraphDependency -> error("Should never happen, logic is handled above")
            }
          generationContext.bindingStack.push(entry)
          // Generate binding code for each param
          val paramBinding =
            generationContext.graph.getOrCreateBinding(
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

  private fun IrConstructorCall.shouldUnwrapMapKeyValues(): Boolean {
    val mapKeyMapKeyAnnotation = annotationClass.mapKeyAnnotation()!!.ir
    // TODO FIR check valid MapKey
    //  - single arg
    //  - no generics
    val unwrapValue = mapKeyMapKeyAnnotation.getSingleConstBooleanArgumentOrNull() != false
    return unwrapValue
  }

  private fun generateMapKeyLiteral(binding: Binding): IrExpression {
    val mapKey =
      when (binding) {
        is Binding.Alias -> binding.annotations.mapKeys.first().ir
        is Binding.Provided -> binding.annotations.mapKeys.first().ir
        is Binding.ConstructorInjected -> binding.annotations.mapKeys.first().ir
        else -> error("Unsupported multibinding source: $binding")
      }

    val unwrapValue = mapKey.shouldUnwrapMapKeyValues()
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
    contextualTypeKey: ContextualTypeKey = binding.contextualTypeKey,
    fieldInitKey: TypeKey? = null,
  ): IrExpression {
    if (binding is Binding.Absent) {
      error("Absent bindings need to be checked prior to generateBindingCode()")
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
        // Must use the provider's params for TypeKey as that has qualifier
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

        val delegateFactoryProvider = generateBindingCode(binding.target, generationContext)
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
        val graphInstanceField =
          generationContext.instanceFields[binding.typeKey]
            ?: run {
              error(
                "No matching dependency graph instance found for type ${binding.typeKey}. Available instance fields ${generationContext.instanceFields.keys}"
              )
            }
        val lambda =
          irLambda(
            context = pluginContext,
            parent = generationContext.thisReceiver.parent,
            receiverParameter = null,
            emptyList(),
            binding.typeKey.type,
            suspend = false,
          ) {
            +irReturn(
              irInvoke(
                dispatchReceiver =
                  irGetField(irGet(generationContext.thisReceiver), graphInstanceField),
                callee = binding.getter.symbol,
                typeHint = binding.typeKey.type,
              )
            )
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
    contextualTypeKey: ContextualTypeKey,
    generationContext: GraphGenerationContext,
    fieldInitKey: TypeKey?,
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
    contextualTypeKey: ContextualTypeKey,
    generationContext: GraphGenerationContext,
    fieldInitKey: TypeKey?,
  ): IrExpression {
    val elementType = (binding.typeKey.type as IrSimpleType).arguments.single().typeOrFail
    val (collectionProviders, individualProviders) =
      binding.sourceBindings.partition { it.annotations.isElementsIntoSet }
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
    fieldInitKey: TypeKey?,
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
        val provider = binding.sourceBindings.first()
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
              binding.sourceBindings.forEach { provider ->
                +irInvoke(
                  dispatchReceiver = irGet(functionReceiver),
                  callee = symbols.mutableSetAdd.symbol,
                  args =
                    listOf(generateMultibindingArgument(provider, generationContext, fieldInitKey)),
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
    fieldInitKey: TypeKey?,
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
    contextualTypeKey: ContextualTypeKey,
    generationContext: GraphGenerationContext,
    fieldInitKey: TypeKey?,
  ): IrExpression {
    // MapFactory.<Integer, Integer>builder(2)
    //   .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
    //   .put(2, provideMapInt2Provider)
    //   .build()
    // MapProviderFactory.<Integer, Integer>builder(2)
    //   .put(1, FileSystemModule_Companion_ProvideMapInt1Factory.create())
    //   .put(2, provideMapInt2Provider)
    //   .build()
    val mapTypeArgs = (contextualTypeKey.typeKey.type as IrSimpleType).arguments
    check(mapTypeArgs.size == 2) { "Unexpected map type args: ${mapTypeArgs.joinToString()}" }
    val keyType: IrType = mapTypeArgs[0].typeOrFail
    val rawValueType = mapTypeArgs[1].typeOrFail
    val rawValueTypeMetadata =
      rawValueType.typeOrFail.asContextualTypeKey(
        metroContext,
        null,
        hasDefault = false,
        isIntoMultibinding = false,
      )
    val useProviderFactory: Boolean = rawValueTypeMetadata.isWrappedInProvider
    val valueType: IrType = rawValueTypeMetadata.typeKey.type

    val targetCompanionObject =
      if (useProviderFactory) {
        symbols.mapProviderFactoryCompanionObject
      } else {
        symbols.mapFactoryCompanionObject
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
          putValueArgument(0, irInt(binding.sourceBindings.size))
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
      binding.sourceBindings.fold(builder) { receiver, sourceBinding ->
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
      typeHint =
        pluginContext.irBuiltIns.mapClass
          .typeWith(keyType, rawValueType)
          .wrapInProvider(symbols.metroProvider),
    )
  }

  private fun IrBuilderWithScope.generateMultibindingArgument(
    provider: Binding,
    generationContext: GraphGenerationContext,
    fieldInitKey: TypeKey?,
  ): IrExpression {
    val bindingCode = generateBindingCode(provider, generationContext, fieldInitKey = fieldInitKey)
    return typeAsProviderArgument(
      metroContext,
      contextKey =
        ContextualTypeKey(
          provider.typeKey,
          isWrappedInProvider = false,
          isWrappedInLazy = false,
          isLazyWrappedInProvider = false,
          hasDefault = false,
        ),
      bindingCode = bindingCode,
      isAssisted = false,
      isGraphInstance = false,
    )
  }
}

internal class GraphGenerationContext(
  val graph: BindingGraph,
  val thisReceiver: IrValueParameter,
  val instanceFields: Map<TypeKey, IrField>,
  val providerFields: Map<TypeKey, IrField>,
  val multibindingProviderFields: Map<Binding.Provided, IrField>,
  val bindingStack: BindingStack,
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
