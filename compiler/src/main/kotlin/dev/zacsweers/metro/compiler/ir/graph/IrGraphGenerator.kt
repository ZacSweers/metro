// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import androidx.collection.IntObjectMap
import androidx.collection.MutableIntObjectMap
import androidx.collection.emptyIntObjectMap
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MetroDeclarations
import dev.zacsweers.metro.compiler.ir.allSupertypesSequence
import dev.zacsweers.metro.compiler.ir.buildBlockBody
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.createMetroMetadata
import dev.zacsweers.metro.compiler.ir.deepRemapperFor
import dev.zacsweers.metro.compiler.ir.doubleCheck
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.graph.expressions.BindingExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.sharding.IrGraphShardGenerator
import dev.zacsweers.metro.compiler.ir.graph.sharding.Shard
import dev.zacsweers.metro.compiler.ir.graph.sharding.ShardBinding
import dev.zacsweers.metro.compiler.ir.graph.sharding.ShardExpressionContext
import dev.zacsweers.metro.compiler.ir.graph.sharding.ShardResult
import dev.zacsweers.metro.compiler.ir.instanceFactory
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irGetProperty
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.metroGraphOrFail
import dev.zacsweers.metro.compiler.ir.metroMetadata
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.remapTypes
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.sourceGraphIfMetroGraph
import dev.zacsweers.metro.compiler.ir.stripOuterProviderOrLazy
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.suspendDoubleCheck
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.toProto
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.ir.typeOrNullableAny
import dev.zacsweers.metro.compiler.ir.withIrBuilder
import dev.zacsweers.metro.compiler.ir.wrapInProvider
import dev.zacsweers.metro.compiler.ir.wrapInSuspendProvider
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.isSyntheticGeneratedGraph
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.newName
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.konan.isNative

internal typealias PropertyInitializer =
  IrBuilderWithScope.(thisReceiver: IrValueParameter, key: IrTypeKey) -> IrExpression

internal typealias InitStatement =
  IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement

internal class IrGraphGenerator(
  metroContext: IrMetroContext,
  traceScope: TraceScope,
  private val diagnosticTag: String,
  private val graphNodesByClass: (ClassId) -> GraphNode?,
  private val node: GraphNode.Local,
  private val graphClass: IrClass,
  private val bindingGraph: IrBindingGraph,
  private val sealResult: IrBindingGraph.BindingGraphResult,
  private val metroDeclarations: MetroDeclarations,
  private val graphExtensionGenerator: IrGraphExtensionGenerator,
  /** Parent graph's binding property context for hierarchical lookup. Null for root graphs. */
  parentBindingContext: BindingPropertyContext?,
) : IrMetroContext by metroContext, TraceScope by traceScope {

  private val propertyNameAllocator =
    NameAllocator(mode = NameAllocator.Mode.COUNT).apply {
      // Preallocate any existing property and field names in this graph
      for (property in node.metroGraphOrFail.properties) {
        reserveName(property.name.asString())
      }
    }

  private val classNameAllocator =
    NameAllocator(mode = NameAllocator.Mode.COUNT).apply {
      // Preallocate any existing nested class names in this graph
      for (declaration in graphClass.nestedClasses) {
        reserveName(declaration.name.asString())
      }
    }

  private var _functionNameAllocatorInitialized = false
  private val _functionNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  private val functionNameAllocator: NameAllocator
    get() {
      if (!_functionNameAllocatorInitialized) {
        // pre-allocate existing function names
        for (function in graphClass.functions) {
          _functionNameAllocator.reserveName(function.name.asString())
        }
        _functionNameAllocatorInitialized = true
      }
      return _functionNameAllocator
    }

  private val bindingPropertyContext =
    BindingPropertyContext(bindingGraph, graphKey = node.typeKey, parent = parentBindingContext)

  /**
   * To avoid `MethodTooLargeException`, we split property field initializations up over multiple
   * constructor inits.
   *
   * @see <a href="https://github.com/ZacSweers/metro/issues/645">#645</a>
   */
  private val propertyInitializers = mutableListOf<Pair<IrProperty, PropertyInitializer>>()

  // TODO replace with irAttribute
  private val propertiesToTypeKeys = mutableMapOf<IrProperty, IrTypeKey>()

  private val graphMetadataReporter = GraphMetadataReporter(this)

  @IgnorableReturnValue
  fun IrProperty.withInit(typeKey: IrTypeKey, init: PropertyInitializer): IrProperty = apply {
    // Only necessary for fields
    if (backingField != null) {
      propertiesToTypeKeys[this] = typeKey
      propertyInitializers += (this to init)
    } else {
      getter!!.apply {
        this.body =
          createIrBuilder(symbol).run { irExprBodySafe(init(dispatchReceiverParameter!!, typeKey)) }
      }
    }
  }

  @IgnorableReturnValue
  fun IrProperty.initFinal(body: IrBuilderWithScope.() -> IrExpression): IrProperty = apply {
    backingField?.apply {
      isFinal = true
      initializer = createIrBuilder(symbol).run { irExprBody(body()) }
      return@apply
    }
    getter?.apply { this.body = createIrBuilder(symbol).run { irExprBodySafe(body()) } }
  }

  /**
   * Graph extensions may reserve property names for their linking, so if they've done that we use
   * the precomputed property rather than generate a new one.
   */
  private fun IrClass.createBindingProperty(
    contextKey: IrContextualTypeKey,
    name: Name,
    type: IrType,
    propertyKind: PropertyKind,
    visibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE,
  ): IrProperty {
    val property =
      addProperty {
          this.name = propertyNameAllocator.newName(name)
          this.visibility = visibility
        }
        .apply {
          graphPropertyData = GraphPropertyData(contextKey, type)
          contextKey.typeKey.qualifier?.ir?.let { annotations += it.deepCopyWithSymbols() }
        }

    return property.ensureInitialized(propertyKind, type)
  }

  fun generate(): BindingPropertyContext {
    with(graphClass) {
      val ctor = primaryConstructor!!
      val constructorStatements = mutableListOf<InitStatement>()
      val thisReceiverParameter = thisReceiverOrFail

      // Set up parent graph property for extension graphs
      val (parentGraphParam, parentGraphInstanceProperty) = setupParentGraphProperty(ctor)

      // Build the ancestor graph properties map for shard expression context
      val ancestorGraphProperties = buildAncestorGraphProperties(parentGraphInstanceProperty)

      // Create expression generator factory
      val expressionGeneratorFactory =
        GraphExpressionGenerator.Factory(
          context = this@IrGraphGenerator,
          traceScope = this@IrGraphGenerator,
          node = node,
          bindingPropertyContext = bindingPropertyContext,
          ancestorGraphProperties = ancestorGraphProperties,
          bindingGraph = bindingGraph,
          metroDeclarations = metroDeclarations,
          graphExtensionGenerator = graphExtensionGenerator,
        )

      // Register the parent graph instance property in the binding context (if present)
      registerParentGraphPropertyToBindingPropertyContext(
        parentGraphParam,
        parentGraphInstanceProperty,
      )

      // Process creator parameters and set up bound instance properties
      processCreatorParameters(ctor, thisReceiverParameter)

      // Create managed binding containers instance properties if used
      processBindingContainers(thisReceiverParameter)

      // Set up this graph's self-binding property
      setupThisGraphProperty(thisReceiverParameter)

      // Collect bindings and their dependencies for provider property ordering
      val initOrder = collectBindingProperties()

      // Filter bindings that need properties
      val collectedBindings = initOrder.filterOnlyIrProperties()

      // Convert collected bindings to ShardBinding for shard generator
      val shardBindings = collectedBindings.mapToShardBindings()

      // Generate shards (or graph-as-shard) with properties
      val shardResult =
        IrGraphShardGenerator(
            context = metroContext,
            graphClass = graphClass,
            shardBindings = shardBindings,
            plannedGroups = sealResult.shardGroups,
            bindingGraph = bindingGraph,
            propertyNameAllocator = propertyNameAllocator,
            classNameAllocator = classNameAllocator,
          )
          .generateShards(diagnosticTag = diagnosticTag)

      if (shardResult != null) {
        // Create shard field properties on the main class (only for nested shards)
        val shardFields = createShardFieldProperties(shardResult)

        // Register shard properties in bindingPropertyContext
        shardResult.registerProperties(bindingPropertyContext, shardFields)

        // Process each shard (property initialization and constructor code)
        processShards(
          shardResult = shardResult,
          shardFields = shardFields,
          ancestorGraphProperties = ancestorGraphProperties,
          expressionGeneratorFactory = expressionGeneratorFactory,
          thisReceiverParameter = thisReceiverParameter,
          constructorStatements = constructorStatements,
        )

        // For nested shards, add shard instantiation to main constructor
        initShardFields(shardResult, shardFields, constructorStatements)
      }

      // Add extra constructor statements
      with(ctor) {
        val originalBody = checkNotNull(body)
        buildBlockBody {
          +originalBody.statements
          constructorStatements.forEach { statement -> +statement(thisReceiverParameter) }
        }
      }

      trace("Implement overrides") { node.implementOverrides(expressionGeneratorFactory) }

      if (!graphClass.origin.isSyntheticGeneratedGraph) {
        trace("Generate Metro metadata") {
          // Finally, generate metadata
          val graphProto = node.toProto(bindingGraph = bindingGraph)
          graphMetadataReporter.write(node, bindingGraph)
          val metroMetadata = createMetroMetadata(dependency_graph = graphProto)

          writeDiagnostic(
            "graph-metadata",
            { "${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt" },
          ) {
            metroMetadata.toString()
          }

          // Write the metadata to the metroGraph class, as that's what downstream readers are
          // looking at and is the most complete view
          graphClass.metroMetadata = metroMetadata
          (graphNodesByClass(node.sourceGraph.classIdOrFail) as? GraphNode.Local)?.let {
            it.proto = graphProto
          }
        }
      }
    }
    return bindingPropertyContext
  }

  /**
   * Sets up the parent graph instance property for extension graphs.
   *
   * Extension graphs (static nested classes) have a ParentGraphParam-origin parameter as their
   * first constructor parameter. This creates a property to store the parent graph instance, which
   * is needed so that shards can access parent graph bindings via
   * `this.graph.parentGraphImpl.shard.property`.
   *
   * @return A pair of (parentGraphParam, parentGraphInstanceProperty), both null for root graphs
   */
  private fun IrClass.setupParentGraphProperty(
    ctor: IrConstructor
  ): Pair<IrValueParameter?, IrProperty?> {
    val parentGraphParam =
      ctor.regularParameters.getOrNull(0)?.takeIf { it.origin == Origins.ParentGraphParam }

    val parentGraphInstanceProperty: IrProperty? =
      if (parentGraphParam != null) {
        val parentGraphType = parentGraphParam.type
        addProperty {
            name = propertyNameAllocator.newName(parentGraphParam.name)
            visibility = DescriptorVisibilities.PRIVATE
          }
          .apply {
            addBackingFieldCompat {
                type = parentGraphType
                visibility = DescriptorVisibilities.PRIVATE
              }
              .apply {
                initializer = createIrBuilder(symbol).run { irExprBody(irGet(parentGraphParam)) }
              }
          }
          .also {
            // Store on the graph class so child extensions can access it
            graphClass.parentGraphInstanceProperty = it
          }
      } else {
        null
      }

    return parentGraphParam to parentGraphInstanceProperty
  }

  /**
   * Builds the ancestor graph properties map for shard expression context.
   *
   * Maps ancestor graph type key -> list of properties to chain through to access it. The key must
   * match GraphNode.typeKey construction:
   * - For synthetic graphs (extensions, dynamic): uses the impl type key
   * - For non-synthetic graphs: uses the interface type key (via sourceGraphIfMetroGraph)
   *
   * @param parentGraphInstanceProperty The property storing the parent graph instance, or null for
   *   root graphs
   * @return Map from ancestor graph type key to property chain for accessing it
   */
  private fun buildAncestorGraphProperties(
    parentGraphInstanceProperty: IrProperty?
  ): Map<IrTypeKey, List<IrProperty>> {
    if (parentGraphInstanceProperty == null) return emptyMap()

    val parentImplType = parentGraphInstanceProperty.backingField!!.type
    val parentImplClass = parentImplType.rawTypeOrNull()

    return buildMap {
        if (parentImplClass != null) {
          // Use the same key construction as GraphNode.typeKey:
          // - Synthetic graphs use the impl
          // - Non-synthetic graphs use sourceGraphIfMetroGraph (the interface)
          val keyClass =
            if (parentImplClass.origin.isSyntheticGeneratedGraph) {
              parentImplClass
            } else {
              parentImplClass.sourceGraphIfMetroGraph
            }
          put(IrTypeKey(keyClass.typeWith()), listOf(parentGraphInstanceProperty))
        }

        // For chained extensions, copy parent's ancestor chains with our property prepended.
        // This avoids walking the chain - parent already computed its ancestors.
        parentImplClass?.ancestorGraphPropertiesMap?.let { parentAncestors ->
          for ((ancestorKey, ancestorChain) in parentAncestors) {
            put(ancestorKey, listOf(parentGraphInstanceProperty) + ancestorChain)
          }
        }
      }
      .also {
        // Store on graph class so child extensions can access it
        graphClass.ancestorGraphPropertiesMap = it
      }
  }

  /**
   * Registers the parent graph instance property in the binding context.
   *
   * Registers under both the impl type and the interface type, since bindings may reference either
   * (factory methods typically take the interface type).
   */
  private fun registerParentGraphPropertyToBindingPropertyContext(
    parentGraphParam: IrValueParameter?,
    parentGraphInstanceProperty: IrProperty?,
  ) {
    if (parentGraphInstanceProperty == null || parentGraphParam == null) return

    // Register under both the impl type and the interface type
    val parentImplTypeKey = IrTypeKey(parentGraphParam.type)
    bindingPropertyContext.put(IrContextualTypeKey(parentImplTypeKey), parentGraphInstanceProperty)

    // Also register under the source graph (interface) type if different
    val parentImplClass = parentGraphParam.type.rawTypeOrNull()
    val parentInterfaceClass = parentImplClass?.sourceGraphIfMetroGraph
    if (parentInterfaceClass != null && parentInterfaceClass != parentImplClass) {
      val parentInterfaceTypeKey = IrTypeKey(parentInterfaceClass)
      bindingPropertyContext.put(
        IrContextualTypeKey(parentInterfaceTypeKey),
        parentGraphInstanceProperty,
      )
    }
  }

  /**
   * Adds a bound instance property with both instance and provider variants.
   *
   * Creates properties for types that are bound as instances (e.g., @BindsInstance parameters,
   * binding containers). Both the instance property and a provider wrapper are created.
   */
  private fun IrClass.addBoundInstanceProperty(
    typeKey: IrTypeKey,
    name: Name,
    thisReceiverParameter: IrValueParameter,
    contextualTypeKey: IrContextualTypeKey = IrContextualTypeKey.create(typeKey),
    initializer:
      IrBuilderWithScope.(thisReceiver: IrValueParameter, typeKey: IrTypeKey) -> IrExpression,
  ) {
    // Don't add it if it's not used
    if (typeKey !in sealResult.reachableKeys) return

    val instanceProperty =
      createBindingProperty(
          contextualTypeKey,
          name.decapitalizeUS().suffixIfNot("Instance"),
          typeKey.type,
          PropertyKind.FIELD,
        )
        .initFinal { initializer(thisReceiverParameter, typeKey) }

    bindingPropertyContext.put(contextualTypeKey, instanceProperty)

    val providerType = metroSymbols.metroProvider.typeWith(typeKey.type)
    val providerContextKey = contextualTypeKey.wrapInProvider()
    val providerProperty =
      createBindingProperty(
          providerContextKey,
          instanceProperty.name.suffixIfNot("Provider"),
          providerType,
          PropertyKind.FIELD,
        )
        .initFinal {
          instanceFactory(
            typeKey.type,
            irGetProperty(irGet(thisReceiverParameter), instanceProperty),
          )
        }
    bindingPropertyContext.put(providerContextKey, providerProperty)
  }

  /**
   * Processes creator parameters and sets up bound instance properties.
   *
   * Handles @BindsInstance parameters, binding containers, dynamic parameters, and graph
   * dependencies from the creator's constructor parameters.
   */
  private fun IrClass.processCreatorParameters(
    ctor: IrConstructor,
    thisReceiverParameter: IrValueParameter,
  ) {
    val creator = node.creator ?: return

    for ((i, param) in creator.parameters.regularParameters.withIndex()) {
      // Find matching ctor param by name. Skip parent graph params - they're handled above.
      if (i == 0 && param.ir?.origin == Origins.ParentGraphParam) continue

      val isBindsInstance = param.isBindsInstance

      // TODO if we copy the annotations over in FIR we can skip this creator lookup all together
      val irParam = ctor.regularParameters[i]

      val isDynamic = irParam.origin == Origins.DynamicContainerParam
      val isBindingContainer = creator.bindingContainersParameterIndices.isSet(i)
      if (isBindsInstance || isBindingContainer || isDynamic) {
        if (!isDynamic && param.typeKey in node.dynamicTypeKeys) {
          // Don't add it if there's a dynamic replacement
          continue
        }
        addBoundInstanceProperty(
          param.typeKey,
          param.name,
          thisReceiverParameter,
          contextualTypeKey = param.contextualTypeKey,
        ) { _, _ ->
          irGet(irParam)
        }
      } else {
        // It's a graph dep. Add all its accessors as available keys and point them at
        // this constructor parameter for provider property initialization
        processGraphDependencyParameter(param, irParam, thisReceiverParameter)
      }
    }
  }

  /**
   * Processes a graph dependency parameter from the creator.
   *
   * Sets up instance and provider properties for included graph dependencies.
   */
  private fun IrClass.processGraphDependencyParameter(
    param: Parameter,
    irParam: IrValueParameter,
    thisReceiverParameter: IrValueParameter,
  ) {
    val graphDep =
      node.includedGraphNodes[param.typeKey]
        ?: reportCompilerBug("Undefined graph node ${param.typeKey}")

    // Don't add it if it's not used
    if (param.typeKey !in sealResult.reachableKeys) return

    val graphDepProperty =
      addSimpleInstanceProperty(
        propertyNameAllocator.newName(graphDep.sourceGraph.name.asString() + "Instance"),
        param.typeKey,
      ) {
        irGet(irParam)
      }
    // Link both the graph typekey and the (possibly-impl type)
    bindingPropertyContext.put(IrContextualTypeKey(param.typeKey), graphDepProperty)
    bindingPropertyContext.put(IrContextualTypeKey(graphDep.typeKey), graphDepProperty)

    // Expose the graph dep as a provider property only if it was reserved by a child graph
    val graphDepProviderType = metroSymbols.metroProvider.typeWith(param.typeKey.type)
    val graphDepProviderContextKey =
      IrContextualTypeKey.create(
        param.typeKey,
        isWrappedInProvider = true,
        rawType = graphDepProviderType,
      )
    // Only create the provider property if it was reserved (requested by a child graph)
    if (bindingGraph.isContextKeyReserved(graphDepProviderContextKey)) {
      val providerWrapperProperty =
        createBindingProperty(
          graphDepProviderContextKey,
          graphDepProperty.name.suffixIfNot("Provider"),
          graphDepProviderType,
          PropertyKind.FIELD,
        )

      // Link both the graph typekey and the (possibly-impl type)
      bindingPropertyContext.put(
        param.contextualTypeKey.stripOuterProviderOrLazy(),
        providerWrapperProperty.initFinal {
          instanceFactory(
            param.typeKey.type,
            irGetProperty(irGet(thisReceiverParameter), graphDepProperty),
          )
        },
      )
      bindingPropertyContext.put(IrContextualTypeKey(graphDep.typeKey), providerWrapperProperty)
    }

    if (graphDep is GraphNode.Local && graphDep.hasExtensions) {
      val depMetroGraph = graphDep.sourceGraph.metroGraphOrFail
      val paramName = depMetroGraph.sourceGraphIfMetroGraph.name
      addBoundInstanceProperty(param.typeKey, paramName, thisReceiverParameter) { _, _ ->
        irGet(irParam)
      }
    }
  }

  /**
   * Creates managed binding containers instance properties if used.
   *
   * Processes all binding containers from this node and extended nodes, creating instance
   * properties for each that isn't replaced by a dynamic instance.
   */
  private fun IrClass.processBindingContainers(thisReceiverParameter: IrValueParameter) {
    val allBindingContainers = buildSet {
      addAll(node.bindingContainers)
      addAll(
        node.allParentGraphs.values.flatMap {
          (it as? GraphNode.Local)?.bindingContainers.orEmpty()
        }
      )
    }
    allBindingContainers
      .sortedBy { it.kotlinFqName.asString() }
      .forEach { clazz ->
        val typeKey = IrTypeKey(clazz)
        if (typeKey !in node.dynamicTypeKeys) {
          // Only add if not replaced with a dynamic instance
          addBoundInstanceProperty(IrTypeKey(clazz), clazz.name, thisReceiverParameter) { _, _ ->
            // Can't use primaryConstructor here because it may be a Java dagger Module in interop
            val noArgConstructor = clazz.constructors.first { it.parameters.isEmpty() }
            irCallConstructor(noArgConstructor.symbol, emptyList())
          }
        }
      }
  }

  /**
   * Sets up this graph's self-binding property.
   *
   * Creates a property that allows the graph to provide itself as a dependency, along with a
   * provider wrapper if reserved by child graphs.
   */
  private fun IrClass.setupThisGraphProperty(thisReceiverParameter: IrValueParameter) {
    // Don't add it if it's not used
    if (node.typeKey !in sealResult.reachableKeys) return

    val thisGraphProperty =
      addSimpleInstanceProperty(propertyNameAllocator.newName("thisGraphInstance"), node.typeKey) {
        irGet(thisReceiverParameter)
      }

    bindingPropertyContext.put(IrContextualTypeKey(node.typeKey), thisGraphProperty)

    // Expose the graph as a provider property if it's used or reserved
    val thisGraphProviderType = metroSymbols.metroProvider.typeWith(node.typeKey.type)
    val thisGraphProviderContextKey =
      IrContextualTypeKey.create(
        node.typeKey,
        isWrappedInProvider = true,
        rawType = thisGraphProviderType,
      )
    if (bindingGraph.isContextKeyReserved(thisGraphProviderContextKey)) {
      val property =
        createBindingProperty(
          thisGraphProviderContextKey,
          "thisGraphInstanceProvider".asName(),
          thisGraphProviderType,
          PropertyKind.FIELD,
        )

      bindingPropertyContext.put(
        thisGraphProviderContextKey,
        property.initFinal {
          instanceFactory(
            node.typeKey.type,
            irGetProperty(irGet(thisReceiverParameter), thisGraphProperty),
          )
        },
      )
    }
  }

  /**
   * Collects bindings and their dependencies for provider property ordering.
   *
   * Uses [BindingPropertyCollector] to determine which bindings need properties and in what order
   * they should be initialized.
   */
  private fun collectBindingProperties(): List<BindingPropertyCollector.CollectedProperty> =
    trace("Collect binding properties") {
      // Injector roots are specifically from inject() functions - they don't create
      // MembersInjector instances, so their dependencies are scalar accesses
      val injectorRoots = mutableSetOf<IrContextualTypeKey>()

      // Collect roots (accessors + injectors) for refcount tracking
      val roots = buildList {
        node.accessors.mapTo(this) { it.contextKey }
        for (injector in node.injectors) {
          add(injector.contextKey)
          injectorRoots.add(injector.contextKey)
        }
      }
      BindingPropertyCollector(
          metroContext = metroContext,
          graph = bindingGraph,
          sortedKeys = sealResult.sortedKeys,
          roots = roots,
          injectorRoots = injectorRoots,
          extraKeeps = bindingGraph.keeps(),
          deferredTypes = sealResult.deferredTypes,
          reachableKeys = sealResult.reachableKeys,
        )
        .collect()
    }

  /**
   * Filters collected bindings to only those that need properties.
   *
   * Excludes bound instances, aliases, and parent graph bindings that don't need duplicated
   * properties.
   */
  private fun List<BindingPropertyCollector.CollectedProperty>.filterOnlyIrProperties():
    List<BindingPropertyCollector.CollectedProperty> =
    asSequence()
      .filterNot { (binding, _) ->
        // Don't generate properties for anything already provided in provider/instance
        // properties (i.e. bound instance types)
        binding.contextualTypeKey in bindingPropertyContext ||
          // We don't generate properties for these even though we do track them in dependencies
          // above, it's just for propagating their aliased type in sorting
          binding is IrBinding.Alias ||
          // BoundInstance bindings use receivers (thisReceiver for self, token for parents)
          binding is IrBinding.BoundInstance ||
          // Parent graph bindings don't need duplicated properties
          (binding is IrBinding.GraphDependency && binding.token != null)
      }
      .toList()
      .also { propertyBindings ->
        writeDiagnostic("keys-providerProperties", "${diagnosticTag}.txt") {
          propertyBindings.joinToString("\n") { it.binding.typeKey.toString() }
        }
        writeDiagnostic("keys-scopedProviderProperties", "${diagnosticTag}.txt") {
          propertyBindings
            .filter { it.binding.isScoped() }
            .joinToString("\n") { it.binding.typeKey.toString() }
        }
      }

  /** Converts collected bindings to [ShardBinding] for the shard generator. */
  private fun List<BindingPropertyCollector.CollectedProperty>.mapToShardBindings():
    List<ShardBinding> = map { collectedProperty ->
    val (binding, propertyType, collectedContextKey, collectedIsProviderType, switchingId) =
      collectedProperty
    val isDeferred = binding.typeKey in sealResult.deferredTypes
    val metadata =
      computeBindingMetadata(binding, propertyType, collectedContextKey, collectedIsProviderType)
    ShardBinding(
      binding = binding,
      typeKey = binding.typeKey,
      contextKey = metadata.contextKey,
      propertyKind = metadata.propertyKind,
      irType = metadata.irType,
      nameHint = metadata.nameHint,
      isScoped = metadata.isScoped,
      isDeferred = isDeferred,
      switchingId = switchingId,
    )
  }

  /**
   * Creates shard field properties on the main class for nested shards.
   *
   * Returns a map from shard index to the property used to access that shard. Returns empty map for
   * graph-as-shard mode.
   */
  private fun IrClass.createShardFieldProperties(
    shardResult: ShardResult
  ): IntObjectMap<IrProperty> =
    if (!shardResult.isGraphAsShard) {
      val result = MutableIntObjectMap<IrProperty>(shardResult.shards.size)
      shardResult.shards.forEach { shard ->
        val shardField =
          addProperty {
              name = propertyNameAllocator.newName("shard${shard.index + 1}").asName()
              visibility = DescriptorVisibilities.INTERNAL
            }
            .apply {
              addBackingFieldCompat {
                type = shard.shardClass.typeWith()
                visibility = DescriptorVisibilities.INTERNAL
              }
            }
        result[shard.index] = shardField
      }
      result
    } else {
      emptyIntObjectMap()
    }

  /** Adds shard instantiation statements to the constructor for nested shards. */
  private fun initShardFields(
    shardResult: ShardResult,
    shardFields: IntObjectMap<IrProperty>,
    constructorStatements: MutableList<InitStatement>,
  ) {
    if (shardResult.isGraphAsShard) return

    for (shardInfo in shardResult.shards) {
      val shardField = shardFields[shardInfo.index]!!
      constructorStatements.add { graphThisReceiver ->
        irSetField(
          irGet(graphThisReceiver),
          shardField.backingField!!,
          irCallConstructor(shardInfo.shardClass.primaryConstructor!!.symbol, emptyList()).apply {
            // Pass graph instance if shard needs it for cross-shard access
            if (shardInfo.graphParam != null) {
              arguments[0] = irGet(graphThisReceiver)
            }
          },
        )
      }
    }
  }

  /**
   * Processes all shards, generating property initializers and constructor code.
   *
   * For each shard:
   * - Creates shard expression context for property access
   * - Collects property initializers
   * - Handles deferred properties with setDelegate calls
   * - Applies chunking logic for large shards
   */
  private fun processShards(
    shardResult: ShardResult,
    shardFields: IntObjectMap<IrProperty>,
    ancestorGraphProperties: Map<IrTypeKey, List<IrProperty>>,
    expressionGeneratorFactory: GraphExpressionGenerator.Factory,
    thisReceiverParameter: IrValueParameter,
    constructorStatements: MutableList<InitStatement>,
  ) {
    for (shard in shardResult.shards) {
      processShard(
        shard = shard,
        shardFields = shardFields,
        ancestorGraphProperties = ancestorGraphProperties,
        expressionGeneratorFactory = expressionGeneratorFactory,
        thisReceiverParameter = thisReceiverParameter,
        constructorStatements = constructorStatements,
      )
    }
  }

  /** Processes a single shard, generating its property initializers and constructor code. */
  private fun processShard(
    shard: Shard,
    shardFields: IntObjectMap<IrProperty>,
    ancestorGraphProperties: Map<IrTypeKey, List<IrProperty>>,
    expressionGeneratorFactory: GraphExpressionGenerator.Factory,
    thisReceiverParameter: IrValueParameter,
    constructorStatements: MutableList<InitStatement>,
  ) {
    val targetThisReceiver = shard.shardClass.thisReceiverOrFail

    // Create shard expression context for property access (only for nested shards)
    val shardExprContext =
      if (!shard.isGraphAsShard) {
        ShardExpressionContext(
          graphProperty = shard.graphProperty,
          shardThisReceiver = targetThisReceiver,
          currentShardIndex = shard.index,
          shardFields = shardFields,
          ancestorGraphProperties = ancestorGraphProperties,
        )
      } else {
        null
      }

    // Generate SwitchingProvider class if switching providers enabled and there are eligible
    // bindings
    val switchingProvider =
      if (options.enableSwitchingProviders) {
        val switchingBindings =
          shard.properties.values
            .filter { it.shardBinding.switchingId != null }
            .map { propertyInfo ->
              val binding = bindingGraph.requireBinding(propertyInfo.shardBinding.typeKey)
              SwitchingProviderGenerator.SwitchingBinding(
                id = propertyInfo.shardBinding.switchingId!!,
                binding = binding,
                contextKey = propertyInfo.shardBinding.contextKey,
              )
            }
        if (switchingBindings.isNotEmpty()) {
          SwitchingProviderGenerator(
              metroContext = metroContext,
              graphOrShardClass = shard.shardClass,
              switchingBindings = switchingBindings,
              expressionGeneratorFactory = expressionGeneratorFactory,
              shardExprContext = shardExprContext,
              classNameAllocator = shard.classNameAllocator,
            )
            .generate()
        } else {
          null
        }
      } else {
        null
      }

    // Collect property initializers for this shard
    val shardPropertyInitializers = mutableListOf<Pair<IrProperty, PropertyInitializer>>()
    val shardPropertiesToTypeKeys = mutableMapOf<IrProperty, IrTypeKey>()
    val shardDeferredProperties = mutableListOf<DeferredPropertyInfo>()

    collectShardPropertyInitializers(
      shard = shard,
      shardExprContext = shardExprContext,
      expressionGeneratorFactory = expressionGeneratorFactory,
      shardPropertyInitializers = shardPropertyInitializers,
      shardPropertiesToTypeKeys = shardPropertiesToTypeKeys,
      shardDeferredProperties = shardDeferredProperties,
      switchingProvider = switchingProvider,
    )

    // Apply chunking logic to this shard's property initializers
    if (shardPropertyInitializers.isNotEmpty()) {
      generateShardChunking(
        shard = shard,
        shardExprContext = shardExprContext,
        expressionGeneratorFactory = expressionGeneratorFactory,
        shardPropertyInitializers = shardPropertyInitializers,
        shardPropertiesToTypeKeys = shardPropertiesToTypeKeys,
        shardDeferredProperties = shardDeferredProperties,
        switchingProvider = switchingProvider,
        thisReceiverParameter = thisReceiverParameter,
        constructorStatements = constructorStatements,
      )
    } else if (!shard.isGraphAsShard) {
      // For nested shards, we must always generate the constructor body even if there are no
      // field-backed property initializers (e.g., all getter-based properties), since the
      // constructor needs the delegating call to Any and graph field initialization.
      shard.shardClass.buildShardConstructor()
    }

    // For graph-as-shard, add deferred setDelegate calls after property inits
    if (shard.isGraphAsShard && shardDeferredProperties.isNotEmpty()) {
      addGraphAsShardDeferredStatements(
        shardDeferredProperties = shardDeferredProperties,
        switchingProvider = switchingProvider,
        expressionGeneratorFactory = expressionGeneratorFactory,
        constructorStatements = constructorStatements,
      )
    }
  }

  /** Collects property initializers for a single shard. */
  private fun collectShardPropertyInitializers(
    shard: Shard,
    shardExprContext: ShardExpressionContext?,
    expressionGeneratorFactory: GraphExpressionGenerator.Factory,
    shardPropertyInitializers: MutableList<Pair<IrProperty, PropertyInitializer>>,
    shardPropertiesToTypeKeys: MutableMap<IrProperty, IrTypeKey>,
    shardDeferredProperties: MutableList<DeferredPropertyInfo>,
    switchingProvider: SwitchingProviderGenerator.SwitchingProvider?,
  ) {
    for ((contextKey, propertyInfo) in shard.properties) {
      val shardBinding = propertyInfo.shardBinding
      val binding = shardBinding.binding
      val isProviderType = contextKey.isWrappedInProvider
      val isScoped = shardBinding.isScoped
      val isDeferred = shardBinding.isDeferred
      val switchingId = shardBinding.switchingId

      val isSuspendBinding = binding.isSuspend
      val requiresDoubleCheck = isScoped && (isProviderType || isSuspendBinding)

      context(scope: IrBuilderWithScope)
      fun IrExpression.applyScoping(): IrExpression {
        return if (requiresDoubleCheck) {
          if (isSuspendBinding) {
            suspendDoubleCheck(metroSymbols, binding.typeKey)
          } else {
            doubleCheck(metroSymbols, binding.typeKey)
          }
        } else {
          this
        }
      }

      val accessType =
        if (isSuspendBinding && shardBinding.propertyKind == PropertyKind.FIELD) {
          // Suspend bindings with FIELD properties use SuspendProvider<T>
          BindingExpressionGenerator.AccessType.SUSPEND_PROVIDER
        } else if (isProviderType) {
          BindingExpressionGenerator.AccessType.PROVIDER
        } else {
          BindingExpressionGenerator.AccessType.INSTANCE
        }

      val property = propertyInfo.property

      // Handle getter properties directly (no chunking needed)
      if (property.backingField == null) {
        property.getter!!.apply {
          body =
            createIrBuilder(symbol).run {
              val initExpr =
                expressionGeneratorFactory
                  .create(dispatchReceiverParameter!!, shardContext = shardExprContext)
                  .generateBindingCode(
                    binding = binding,
                    contextualTypeKey = contextKey,
                    accessType = accessType,
                    fieldInitKey = contextKey.typeKey,
                  )
                  .applyScoping()
              irExprBodySafe(initExpr)
            }
        }
        continue
      }

      // For field properties, add to initializers list for potential chunking
      shardPropertiesToTypeKeys[property] = contextKey.typeKey

      if (isDeferred) {
        // Deferred properties are initialized with empty DelegateFactory(),
        // then setDelegate is called after all properties in this shard are initialized
        shardDeferredProperties += DeferredPropertyInfo(contextKey.typeKey, property, switchingId)
        val deferredType = contextKey.typeKey.type
        val init: PropertyInitializer = { _, _ ->
          irInvoke(
            callee = metroSymbols.metroDelegateFactoryConstructor,
            typeArgs = listOf(deferredType),
          )
        }
        shardPropertyInitializers += property to init
      } else {
        val initExpression: PropertyInitializer =
          if (switchingId != null && switchingProvider != null) {
            val switchingProviderConstructor = switchingProvider.constructor
            { thisReceiver: IrValueParameter, _: IrTypeKey ->
              irCallConstructor(
                  switchingProviderConstructor.symbol,
                  listOf(contextKey.typeKey.type),
                )
                .apply {
                  arguments[0] = irGet(thisReceiver) // graph/shard reference
                  arguments[1] = irInt(switchingId) // switching ID
                }
                .applyScoping()
            }
          } else {
            { thisReceiver: IrValueParameter, fieldInitKey: IrTypeKey ->
              expressionGeneratorFactory
                .create(thisReceiver, shardContext = shardExprContext)
                .generateBindingCode(
                  binding,
                  contextualTypeKey = contextKey,
                  accessType = accessType,
                  fieldInitKey = fieldInitKey,
                )
                .applyScoping()
            }
          }

        shardPropertyInitializers += property to initExpression
      }
    }
  }

  /** Applies chunking logic to shard property initializers. */
  private fun generateShardChunking(
    shard: Shard,
    shardExprContext: ShardExpressionContext?,
    expressionGeneratorFactory: GraphExpressionGenerator.Factory,
    shardPropertyInitializers: List<Pair<IrProperty, PropertyInitializer>>,
    shardPropertiesToTypeKeys: Map<IrProperty, IrTypeKey>,
    shardDeferredProperties: List<DeferredPropertyInfo>,
    switchingProvider: SwitchingProviderGenerator.SwitchingProvider?,
    thisReceiverParameter: IrValueParameter,
    constructorStatements: MutableList<InitStatement>,
  ) {
    val mustChunkInits =
      options.chunkFieldInits && shardPropertyInitializers.size > options.statementsPerInitFun

    // Create name allocator for init functions on this shard
    val shardFunctionNameAllocator =
      if (shard.isGraphAsShard) {
        functionNameAllocator
      } else {
        NameAllocator(mode = NameAllocator.Mode.COUNT)
      }

    // Helper to generate setDelegate calls for deferred properties in this shard
    fun IrBuilderWithScope.generateDeferredSetDelegateCalls(
      thisReceiver: IrValueParameter,
      switchingProvider: SwitchingProviderGenerator.SwitchingProvider?,
    ): List<IrStatement> = buildList {
      for ((deferredTypeKey, deferredProperty, switchingId) in shardDeferredProperties) {
        val binding = bindingGraph.requireBinding(deferredTypeKey)
        add(
          irInvoke(
            dispatchReceiver = irGetObject(metroSymbols.metroDelegateFactoryCompanion),
            callee = metroSymbols.metroDelegateFactorySetDelegate,
            typeArgs = listOf(deferredTypeKey.type),
            args =
              listOf(
                irGetProperty(irGet(thisReceiver), deferredProperty),
                generateProviderExpression(
                  binding = binding,
                  contextKey = binding.contextualTypeKey.wrapInProvider(),
                  switchingId = switchingId,
                  switchingProvider = switchingProvider,
                  thisReceiver = thisReceiver,
                  shardExprContext = shardExprContext,
                  expressionGeneratorFactory = expressionGeneratorFactory,
                  fieldInitKey = deferredTypeKey,
                  applyScoping = binding.isScoped(),
                ),
              ),
          )
        )
      }
    }

    if (mustChunkInits) {
      generateChunkedInits(
        shard = shard,
        shardFunctionNameAllocator = shardFunctionNameAllocator,
        shardPropertyInitializers = shardPropertyInitializers,
        shardPropertiesToTypeKeys = shardPropertiesToTypeKeys,
        generateDeferredSetDelegateCalls = { thisReceiver ->
          generateDeferredSetDelegateCalls(thisReceiver, switchingProvider)
        },
        constructorStatements = constructorStatements,
      )
    } else {
      generateDirectInits(
        shard = shard,
        shardPropertyInitializers = shardPropertyInitializers,
        shardPropertiesToTypeKeys = shardPropertiesToTypeKeys,
        thisReceiverParameter = thisReceiverParameter,
        generateDeferredSetDelegateCalls = { thisReceiver ->
          generateDeferredSetDelegateCalls(thisReceiver, switchingProvider)
        },
      )
    }
  }

  /** Applies chunked initialization for large shards. */
  private fun generateChunkedInits(
    shard: Shard,
    shardFunctionNameAllocator: NameAllocator,
    shardPropertyInitializers: List<Pair<IrProperty, PropertyInitializer>>,
    shardPropertiesToTypeKeys: Map<IrProperty, IrTypeKey>,
    generateDeferredSetDelegateCalls: IrBuilderWithScope.(IrValueParameter) -> List<IrStatement>,
    constructorStatements: MutableList<InitStatement>,
  ) {
    val chunks =
      buildList<InitStatement> {
          shardPropertyInitializers.forEach { (property, init) ->
            val typeKey = shardPropertiesToTypeKeys.getValue(property)
            add { thisReceiver ->
              irSetField(irGet(thisReceiver), property.backingField!!, init(thisReceiver, typeKey))
            }
          }
        }
        .chunked(options.statementsPerInitFun)

    val targetThisReceiver = shard.shardClass.thisReceiverOrFail

    val initFunctionsToCall =
      chunks.map { statementsChunk ->
        val initName = shardFunctionNameAllocator.newName("init")
        shard.shardClass
          .addFunction(initName, irBuiltIns.unitType, visibility = DescriptorVisibilities.PRIVATE)
          .apply {
            val localReceiver = targetThisReceiver.copyTo(this)
            setDispatchReceiver(localReceiver)
            buildBlockBody {
              for (statement in statementsChunk) {
                +statement(localReceiver)
              }
            }
          }
      }

    if (shard.isGraphAsShard) {
      // For graph-as-shard, add init calls to main constructor
      constructorStatements += buildList {
        initFunctionsToCall.forEach { initFunction ->
          add { dispatchReceiver ->
            irInvoke(dispatchReceiver = irGet(dispatchReceiver), callee = initFunction.symbol)
          }
        }
      }
    } else {
      // For nested shard, add init calls to shard constructor
      shard.shardClass.buildShardConstructor {
        // Initialize graph property field from constructor parameter (if needed)
        shard.graphProperty?.backingField?.let { graphBackingField ->
          +irSetField(irGet(targetThisReceiver), graphBackingField, irGet(shard.graphParam!!))
        }
        initFunctionsToCall.forEach { initFunction ->
          +irInvoke(dispatchReceiver = irGet(targetThisReceiver), callee = initFunction.symbol)
        }
        // Add setDelegate calls for deferred properties in this shard
        generateDeferredSetDelegateCalls(targetThisReceiver).forEach { +it }
      }
    }
  }

  /** Applies direct initialization for small shards. */
  private fun generateDirectInits(
    shard: Shard,
    shardPropertyInitializers: List<Pair<IrProperty, PropertyInitializer>>,
    shardPropertiesToTypeKeys: Map<IrProperty, IrTypeKey>,
    thisReceiverParameter: IrValueParameter,
    generateDeferredSetDelegateCalls: IrBuilderWithScope.(IrValueParameter) -> List<IrStatement>,
  ) {
    if (shard.isGraphAsShard) {
      // For graph-as-shard, use initFinal (field initializer)
      shardPropertyInitializers.forEach { (property, init) ->
        property.initFinal {
          val typeKey = shardPropertiesToTypeKeys.getValue(property)
          init(thisReceiverParameter, typeKey)
        }
      }
    } else {
      // For nested shard, set fields in constructor body
      shard.shardClass.buildShardConstructor {
        val targetThisReceiver = shard.shardClass.thisReceiverOrFail

        // Initialize graph property field from constructor parameter (if needed)
        shard.graphProperty?.backingField?.let { graphBackingField ->
          +irSetField(irGet(targetThisReceiver), graphBackingField, irGet(shard.graphParam!!))
        }
        for ((property, init) in shardPropertyInitializers) {
          val typeKey = shardPropertiesToTypeKeys.getValue(property)
          +irSetField(
            irGet(targetThisReceiver),
            property.backingField!!,
            init(targetThisReceiver, typeKey),
          )
        }
        // Add setDelegate calls for deferred properties in this shard
        generateDeferredSetDelegateCalls(targetThisReceiver).forEach { +it }
      }
    }
  }

  private fun IrClass.buildShardConstructor(body: IrBlockBodyBuilder.() -> Unit = {}) {
    val shardConstructor = primaryConstructor!!
    shardConstructor.buildBlockBody {
      +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.primaryConstructor!!)
      body()
    }
  }

  /** Adds deferred setDelegate statements for graph-as-shard mode. */
  private fun addGraphAsShardDeferredStatements(
    shardDeferredProperties: List<DeferredPropertyInfo>,
    switchingProvider: SwitchingProviderGenerator.SwitchingProvider?,
    expressionGeneratorFactory: GraphExpressionGenerator.Factory,
    constructorStatements: MutableList<InitStatement>,
  ) {
    constructorStatements +=
      shardDeferredProperties.map { (deferredTypeKey, deferredProperty, switchingId) ->
        val initStatement: InitStatement = { thisReceiver ->
          val binding = bindingGraph.requireBinding(deferredTypeKey)
          irInvoke(
            dispatchReceiver = irGetObject(metroSymbols.metroDelegateFactoryCompanion),
            callee = metroSymbols.metroDelegateFactorySetDelegate,
            typeArgs = listOf(deferredTypeKey.type),
            args =
              listOf(
                irGetProperty(irGet(thisReceiver), deferredProperty),
                generateProviderExpression(
                  binding = binding,
                  contextKey = binding.contextualTypeKey.wrapInProvider(),
                  switchingId = switchingId,
                  switchingProvider = switchingProvider,
                  thisReceiver = thisReceiver,
                  shardExprContext = null,
                  expressionGeneratorFactory = expressionGeneratorFactory,
                  fieldInitKey = deferredTypeKey,
                  applyScoping = binding.isScoped(),
                ),
              ),
          )
        }
        initStatement
      }
  }

  /** Computes binding metadata for property generation. */
  private fun computeBindingMetadata(
    binding: IrBinding,
    propertyType: PropertyKind,
    collectedContextKey: IrContextualTypeKey,
    collectedIsProviderType: Boolean,
  ): BindingMetadata {
    val key = binding.typeKey
    var isProviderType = collectedIsProviderType
    val isSuspendBinding = binding.isSuspend
    val finalContextKey =
      if (isSuspendBinding && propertyType == PropertyKind.FIELD) {
        collectedContextKey.wrapInSuspendProvider()
      } else {
        collectedContextKey.letIf(isProviderType) { it.wrapInProvider() }
      }
    val suffix: String
    val irType =
      if (binding is IrBinding.ConstructorInjected && binding.isAssisted) {
        isProviderType = false
        suffix = "Factory"
        binding.classFactory.factoryClass.typeWith()
      } else if (propertyType == PropertyKind.GETTER) {
        suffix = if (isProviderType) "Provider" else ""
        finalContextKey.toIrType()
      } else if (isSuspendBinding) {
        // Suspend bindings use SuspendProvider<T> for field storage
        suffix = "SuspendProvider"
        metroSymbols.metroSuspendProvider.typeWith(key.type)
      } else {
        suffix = "Provider"
        metroSymbols.metroProvider.typeWith(key.type)
      }

    return BindingMetadata(
      binding = binding,
      propertyKind = propertyType,
      contextKey = finalContextKey,
      irType = irType,
      nameHint = binding.nameHint.decapitalizeUS().suffixIfNot(suffix).asName(),
      isProviderType = isProviderType,
      isScoped = binding.isScoped(),
      isSuspend = isSuspendBinding,
    )
  }

  // Helper to compute binding metadata
  data class BindingMetadata(
    val binding: IrBinding,
    val propertyKind: PropertyKind,
    val contextKey: IrContextualTypeKey,
    val irType: IrType,
    val nameHint: Name,
    val isProviderType: Boolean,
    val isScoped: Boolean,
    val isSuspend: Boolean = false,
  )

  /**
   * Info for deferred properties that need setDelegate calls. Includes the switchingId so that
   * deferred bindings can also use SwitchingProvider when switching providers are enabled.
   */
  data class DeferredPropertyInfo(
    val typeKey: IrTypeKey,
    val property: IrProperty,
    val switchingId: Int?,
  )

  /**
   * Generates a provider expression that either uses SwitchingProvider (when switching providers
   * are enabled and the binding is eligible) or falls back to direct provider generation.
   *
   * This is used for both regular property initialization and setDelegate calls for deferred
   * bindings, ensuring consistent behavior between the two paths.
   */
  context(scope: IrBuilderWithScope)
  private fun generateProviderExpression(
    binding: IrBinding,
    contextKey: IrContextualTypeKey,
    switchingId: Int?,
    switchingProvider: SwitchingProviderGenerator.SwitchingProvider?,
    thisReceiver: IrValueParameter,
    shardExprContext: ShardExpressionContext?,
    expressionGeneratorFactory: GraphExpressionGenerator.Factory,
    fieldInitKey: IrTypeKey,
    applyScoping: Boolean,
  ): IrExpression =
    with(scope) {
      val providerExpr =
        if (switchingId != null && switchingProvider != null) {
          irCallConstructor(switchingProvider.constructor.symbol, listOf(binding.typeKey.type))
            .apply {
              arguments[0] = irGet(thisReceiver)
              arguments[1] = irInt(switchingId)
            }
        } else {
          val accessType =
            if (binding.isSuspend) {
              BindingExpressionGenerator.AccessType.SUSPEND_PROVIDER
            } else {
              BindingExpressionGenerator.AccessType.PROVIDER
            }
          expressionGeneratorFactory
            .create(thisReceiver, shardContext = shardExprContext)
            .generateBindingCode(
              binding = binding,
              contextualTypeKey = contextKey,
              accessType = accessType,
              fieldInitKey = fieldInitKey,
            )
        }

      return if (applyScoping) {
        if (binding.isSuspend) {
          providerExpr.suspendDoubleCheck(metroSymbols, binding.typeKey)
        } else {
          providerExpr.doubleCheck(metroSymbols, binding.typeKey)
        }
      } else {
        providerExpr
      }
    }

  // TODO add asProvider support?
  private fun IrClass.addSimpleInstanceProperty(
    name: String,
    typeKey: IrTypeKey,
    initializerExpression: IrBuilderWithScope.() -> IrExpression,
  ): IrProperty =
    addProperty {
        this.name = name.decapitalizeUS().asName()
        this.visibility = DescriptorVisibilities.PRIVATE
      }
      .apply { this.addBackingFieldCompat { this.type = typeKey.type } }
      .initFinal { initializerExpression() }

  private fun GraphNode.Local.implementOverrides(
    expressionGeneratorFactory: GraphExpressionGenerator.Factory
  ) {
    // Implement abstract getters for accessors
    for ((contextualTypeKey, function, isOptionalDep) in accessors) {
      val binding = bindingGraph.findBinding(contextualTypeKey.typeKey)

      if (isOptionalDep && binding == null) {
        continue // Just use its default impl
      } else if (binding == null) {
        // Should never happen
        reportCompilerBug("No binding found for $contextualTypeKey")
      }

      val irFunction = function.ir
      irFunction.apply {
        val declarationToFinalize =
          irFunction.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
        }
        body =
          withIrBuilder(symbol) {
            irExprBodySafe(
              typeAsProviderArgument(
                contextualTypeKey,
                expressionGeneratorFactory
                  .create(irFunction.dispatchReceiverParameter!!)
                  .generateBindingCode(binding, contextualTypeKey = contextualTypeKey),
                isAssisted = false,
                isGraphInstance = false,
              )
            )
          }
      }
    }

    // Implement abstract injectors
    injectors.forEach { (contextKey, overriddenFunction) ->
      val typeKey = contextKey.typeKey
      overriddenFunction.ir.apply {
        finalizeFakeOverride(graphClass.thisReceiverOrFail)
        val targetParam = regularParameters[0]
        val binding = bindingGraph.requireBinding(contextKey) as IrBinding.MembersInjected

        // We don't get a MembersInjector instance/provider from the graph. Instead, we call
        // all the target inject functions directly
        body =
          createIrBuilder(symbol).irBlockBody {
            // TODO reuse, consolidate calling code with how we implement this in
            //  constructor inject code gen
            // val injectors =
            // metroDeclarations.findAllInjectorsFor(declaration)
            // val memberInjectParameters = injectors.flatMap { it.parameters.values.flatten()
            // }

            // Extract the type from MembersInjector<T>
            val wrappedType =
              typeKey.copy(typeKey.type.requireSimpleType(targetParam).arguments[0].typeOrFail)

            val targetClass = pluginContext.referenceClass(binding.targetClassId)!!.owner

            // Create a single deep remapper from the target class - this handles the entire
            // type hierarchy correctly (e.g., ExampleClass<Int> -> Parent<Int, String> ->
            // GrandParent<String, Int>)
            val remapper =
              if (typeKey.hasTypeArgs) {
                targetClass.deepRemapperFor(wrappedType.type)
              } else {
                null
              }

            for (type in
              targetClass.allSupertypesSequence(excludeSelf = false, excludeAny = true)) {

              val clazz = type.rawType()
              val generatedInjector = metroDeclarations.findInjector(clazz) ?: continue
              for ((function, unmappedParams) in generatedInjector.declaredInjectFunctions) {
                val parameters =
                  if (remapper != null) {
                    unmappedParams.remapTypes(remapper)
                  } else {
                    unmappedParams
                  }
                // Record for IC
                trackFunctionCall(this@apply, function)

                var isOptional = false

                val args = buildList {
                  add(irGet(targetParam))
                  for (parameter in parameters.regularParameters) {
                    val paramBinding = bindingGraph.requireBinding(parameter.contextualTypeKey)
                    if (paramBinding is IrBinding.Absent) {
                      isOptional = true
                      if (parameters.regularParameters.size > 1) {
                        reportCompilerBug(
                          "Unexpected multiple parameters for member injection: $contextKey"
                        )
                      }
                      break
                    } else {
                      add(
                        typeAsProviderArgument(
                          parameter.contextualTypeKey,
                          expressionGeneratorFactory
                            .create(overriddenFunction.ir.dispatchReceiverParameter!!)
                            .generateBindingCode(
                              paramBinding,
                              contextualTypeKey = parameter.contextualTypeKey,
                            ),
                          isAssisted = false,
                          isGraphInstance = false,
                        )
                      )
                    }
                  }
                }

                // If it's a simple property with a default value and absent, omit injecting it here
                if (isOptional) continue

                +irInvoke(
                  callee = function.symbol,
                  typeArgs =
                    targetParam.type.requireSimpleType(targetParam).arguments.map {
                      it.typeOrNullableAny
                    },
                  args = args,
                )
              }
            }
          }
      }
    }

    // Binds stub bodies are implemented in BindsMirrorClassTransformer on the original
    // declarations, so we don't need to implement fake overrides here
    // TODO EXCEPT in native compilations, which appear to complain if you don't implement fake
    //  overrides even if they have a default impl
    //  https://youtrack.jetbrains.com/issue/KT-83666
    if (metroContext.platform.isNative() && bindsFunctions.isNotEmpty()) {
      for (function in bindsFunctions) {
        // Note we can't source this from the node.bindsCallables as those are pointed at their
        // original declarations and we need to implement their fake overrides here
        val irFunction = function.ir
        irFunction.apply {
          val declarationToFinalize = propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
          if (declarationToFinalize.isFakeOverride) {
            declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
          }
          body = stubExpressionBody()
        }
      }
    }

    // Implement bodies for contributed graphs
    // Sort by keys when generating so they have deterministic ordering
    for ((typeKey, functions) in graphExtensions) {
      functions.forEach { extensionAccessor ->
        val function = extensionAccessor.accessor
        val irFunction = function.ir
        irFunction.apply {
          val declarationToFinalize =
            irFunction.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
          if (declarationToFinalize.isFakeOverride) {
            declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
          }

          if (extensionAccessor.isFactory) {
            // Handled in regular accessors
          } else {
            // Graph extension creator. Use regular binding code gen
            // Could be a factory SAM function or a direct accessor. SAMs won't have a binding, but
            // we can synthesize one here as needed
            val binding =
              bindingGraph.findBinding(typeKey)
                ?: IrBinding.GraphExtension(
                  typeKey = typeKey,
                  parent = metroGraphOrFail,
                  accessor = irFunction,
                  parentGraphKey = node.typeKey,
                )
            val contextKey = IrContextualTypeKey.from(irFunction)
            body =
              createIrBuilder(symbol).run {
                irExprBodySafe(
                  expressionGeneratorFactory
                    .create(irFunction.dispatchReceiverParameter!!)
                    .generateBindingCode(binding = binding, contextualTypeKey = contextKey)
                )
              }
          }
        }
      }
    }
  }
}

/**
 * Stores the property used to access the parent graph instance in extension graphs (inner classes).
 * This is used by child extensions to build the ancestor property chain for accessing grandparent
 * bindings.
 */
internal var IrClass.parentGraphInstanceProperty: IrProperty? by irAttribute(copyByDefault = false)

/**
 * Stores the pre-computed ancestor graph property chains for extension graphs. Maps ancestor graph
 * type key -> list of properties to chain through to access that ancestor. Child extensions copy
 * this map and prepend their own parentGraphInstanceProperty.
 */
internal var IrClass.ancestorGraphPropertiesMap: Map<IrTypeKey, List<IrProperty>>? by
  irAttribute(copyByDefault = false)
