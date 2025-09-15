// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.METRO_VERSION
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.sharding.ShardFieldRegistry
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.MetroConstants.STATEMENTS_PER_METHOD
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.sharding.ShardingPlan
import dev.zacsweers.metro.compiler.suffixIfNot
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal typealias FieldInitializer =
  IrBuilderWithScope.(thisReceiver: IrValueParameter, key: IrTypeKey) -> IrExpression

internal class IrGraphGenerator(
  metroContext: IrMetroContext,
  private val dependencyGraphNodesByClass: (ClassId) -> DependencyGraphNode?,
  private val node: DependencyGraphNode,
  private val graphClass: IrClass,
  private val bindingGraph: IrBindingGraph,
  private val sealResult: IrBindingGraph.BindingGraphResult,
  private val fieldNameAllocator: NameAllocator,
  private val parentTracer: Tracer,
  // TODO move these accesses to irAttributes
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
  private val graphExtensionGenerator: IrGraphExtensionGenerator,
  private val shardingPlan: ShardingPlan? = null,
) : IrMetroContext by metroContext {

  private val bindingFieldContext = BindingFieldContext()

  /**
   * Registry for tracking which binding fields are in which shards.
   * This coordinates field naming between shard generation and cross-shard access.
   */
  private val shardFieldRegistry = ShardFieldRegistry()

  /**
   * To avoid `MethodTooLargeException`, we split field initializations up over multiple constructor
   * inits. Each class (main graph or shard) maintains its own initialization list.
   *
   * @see <a href="https://github.com/ZacSweers/metro/issues/645">#645</a>
   */
  private val fieldInitializersByClass = mutableMapOf<IrClass, MutableList<Triple<IrField, IrTypeKey, FieldInitializer>>>()
  private val expressionGeneratorFactory =
    IrGraphExpressionGenerator.Factory(
      context = this,
      node = node,
      bindingFieldContext = bindingFieldContext,
      bindingGraph = bindingGraph,
      bindingContainerTransformer = bindingContainerTransformer,
      membersInjectorTransformer = membersInjectorTransformer,
      assistedFactoryTransformer = assistedFactoryTransformer,
      graphExtensionGenerator = graphExtensionGenerator,
      parentTracer = parentTracer,
      shardFieldRegistry = shardFieldRegistry,
      shardingPlan = shardingPlan,
      currentShardIndex = null,
    )

  private var shardInfos: Map<Int, ShardGenerator.ShardInfo> = emptyMap()

  /** Owner IrClass for a binding key based on the sharding plan. */
  private fun ownerClassFor(key: IrTypeKey): IrClass {
    val idx = shardingPlan?.bindingToShard?.get(key)
    return if (idx != null && idx != 0) {
      checkNotNull(shardInfos[idx]?.shardClass) { "Shard class missing for index $idx" }
    } else {
      graphClass
    }
  }

  fun IrField.withInit(typeKey: IrTypeKey, init: FieldInitializer): IrField = apply {
    // The typeKey is already registered in shardFieldRegistry when the field is created
    // Determine which class this field belongs to (main graph or shard)
    val owningClass = parent as IrClass
    // Store the type key alongside the field and initializer to avoid reverse lookups later
    fieldInitializersByClass.getOrPut(owningClass) { mutableListOf() }.add(Triple(this, typeKey, init))
  }

  fun IrField.initFinal(body: IrBuilderWithScope.() -> IrExpression): IrField = apply {
    isFinal = true
    initializer = createIrBuilder(symbol).run { irExprBody(body()) }
  }

  /**
   * Graph extensions may reserve field names for their linking, so if they've done that we use the
   * precomputed field rather than generate a new one.
   */
  private inline fun IrClass.getOrCreateBindingField(
    key: IrTypeKey,
    name: () -> String,
    type: () -> IrType,
    visibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE,
  ): IrField {
    return bindingGraph.reservedField(key)?.field?.also { addChild(it) }
      ?: addField(fieldName = name().asName(), fieldType = type(), fieldVisibility = visibility)
  }

  fun generate() =
    with(graphClass) {
      val ctor = primaryConstructor!!

      // SwitchingProvider state tracking
      val switchingIds = mutableMapOf<IrTypeKey, Int>()
      var nextSwitchId = 0

      // Robust lookup of the FIR-declared SwitchingProvider class
      // Only look for it if the option is enabled
      val switchingProviderClass: IrClass? = if (options.fastInit) {
        declarations
          .filterIsInstance<IrClass>()
          .firstOrNull { it.name.asString() == "SwitchingProvider" }
      } else {
        if (options.debug) {
          log("SwitchingProvider disabled via compiler option")
        }
        null
      }

      // Validate SwitchingProvider presence when sharding is enabled and option is on
      if (shardingPlan?.requiresSharding() == true &&
          options.fastInit &&
          switchingProviderClass == null) {
        error("SwitchingProvider skeleton not found in ${graphClass.name}; FIR step missing or failed")
      } else if (switchingProviderClass == null && options.debug) {
        log("SwitchingProvider not found in ${graphClass.name}; using fallback provider generation")
      }

      val spCtor: IrConstructor? = switchingProviderClass?.primaryConstructor

      // Populate SwitchingProvider constructor body if it exists
      if (switchingProviderClass != null && spCtor != null) {
        // Add Provider<T> supertype if not already present
        if (switchingProviderClass.superTypes.isEmpty() ||
            switchingProviderClass.superTypes.all { !it.isProvider() }) {
          val typeParam = switchingProviderClass.typeParameters.firstOrNull()
            ?: error("SwitchingProvider must have type parameter T")
          // Create the type reference for the type parameter T
          val typeParamType = irBuiltIns.anyType  // Fallback to Any for now, as the actual type will be resolved at usage
          val providerType = symbols.metroProvider.typeWith(typeParamType)
          switchingProviderClass.superTypes = switchingProviderClass.superTypes + providerType
        }

        // First, create backing fields for graph and id if they don't exist
        val graphField = switchingProviderClass.declarations
          .filterIsInstance<IrField>()
          .firstOrNull { it.name == Symbols.Names.graph }
          ?: switchingProviderClass.addField {
            name = Symbols.Names.graph
            type = graphClass.defaultType
            visibility = DescriptorVisibilities.PRIVATE
            isFinal = true
          }

        val idField = switchingProviderClass.declarations
          .filterIsInstance<IrField>()
          .firstOrNull { it.name == Symbols.Names.id }
          ?: switchingProviderClass.addField {
            name = Symbols.Names.id
            type = irBuiltIns.intType
            visibility = DescriptorVisibilities.PRIVATE
            isFinal = true
          }

        // Build constructor body: super(), field assignments, instance initializer
        spCtor.body = irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
          val builder = createIrBuilder(spCtor.symbol)
          val thisParam = switchingProviderClass.thisReceiver
            ?: error("SwitchingProvider must have thisReceiver")

          // Find constructor parameters - use nonDispatchParameters to avoid deprecated API
          val params = spCtor.nonDispatchParameters
          require(params.size >= 2) {
            "SwitchingProvider constructor must have at least 2 parameters but found ${params.size}"
          }
          val graphParam = params[0]  // First param should be graph
          val idParam = params[1]     // Second param should be id

          // Validate parameter names
          require(graphParam.name == Symbols.Names.graph) {
            "Expected first parameter to be 'graph' but got '${graphParam.name}'"
          }
          require(idParam.name == Symbols.Names.id) {
            "Expected second parameter to be 'id' but got '${idParam.name}'"
          }

          // Call super constructor (Any)
          statements += builder.irDelegatingConstructorCall(
            irBuiltIns.anyClass.owner.primaryConstructor!!
          )

          // Assign fields
          statements += builder.irSetField(
            receiver = builder.irGet(thisParam),
            field = graphField,
            value = builder.irGet(graphParam)
          )

          statements += builder.irSetField(
            receiver = builder.irGet(thisParam),
            field = idField,
            value = builder.irGet(idParam)
          )

          // Call instance initializer if needed
          statements += IrInstanceInitializerCallImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            switchingProviderClass.symbol,
            irBuiltIns.unitType
          )
        }

        if (options.debug) {
          log("Populated SwitchingProvider constructor body with field assignments")
        }
      }

      // Resolve caching wrapper symbols once for efficiency
      val doubleCheckProvider = symbols.doubleCheckProvider // Thread-safe for scoped bindings
      // SingleCheck is lighter weight than DoubleCheck (no synchronization needed for unscoped)
      // Use SingleCheck for unscoped bindings when available, fallback to DoubleCheck
      val singleCheckProvider = symbols.metroProviderSymbols.singleCheckProviderOrNull() ?: doubleCheckProvider

      val constructorStatements =
        mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()

      val initStatements =
        mutableListOf<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement>()

      val thisReceiverParameter = thisReceiverOrFail

      // Generate shard class shells early if sharding is enabled
      // This must happen BEFORE field creation so ownerClassFor() can find the shard classes
      if (shardingPlan?.requiresSharding() == true) {
        parentTracer.traceNested("Generate shard shells") {
          generateShardShells()
        }
      }

      // Shard field initializers will be collected later
      val shardInitializers = mutableListOf<IrBuilderWithScope.(IrValueParameter) -> IrStatement>()

      fun addBoundInstanceField(
        typeKey: IrTypeKey,
        name: Name,
        initializer:
          IrBuilderWithScope.(thisReceiver: IrValueParameter, typeKey: IrTypeKey) -> IrExpression,
      ) {
        // Don't add it if it's not used
        if (typeKey !in sealResult.reachableKeys) return

        val target = ownerClassFor(typeKey)

        // 1. Create the instance field first
        val instanceFieldName = fieldNameAllocator.newName(
          name
            .asString()
            .removePrefix("$")
            .decapitalizeUS()
            .suffixIfNot("Instance")
        )

        val instanceField = target.addSimpleInstanceField(
          name = instanceFieldName,
          typeKey = typeKey
        ) {
          initializer(thisReceiverParameter, typeKey)
        }

        // Register the instance field
        bindingFieldContext.putInstanceField(typeKey, instanceField)

        // 2. Create the provider field that wraps the instance
        val providerFieldName = fieldNameAllocator.newName(
          name
            .asString()
            .removePrefix("$")
            .decapitalizeUS()
            .suffixIfNot("Instance")
            .suffixIfNot("Provider")
        )

        val providerField = target.getOrCreateBindingField(
          typeKey,
          { providerFieldName },
          { symbols.metroProvider.typeWith(typeKey.type) },
        ).initFinal {
          if (switchingProviderClass != null && spCtor != null) {
            // Use SwitchingProvider wrapped in caching
            val id = switchingIds.getOrPut(typeKey) {
              val newId = nextSwitchId++
              if (debug) {
                log("IrGraphGenerator: Registering BoundInstance ${typeKey.render(short = true)} with SwitchingProvider id=$newId")
              }
              newId
            }
            val spNew = irCall(spCtor.symbol).also { call ->
              // Set type arguments - the SwitchingProvider has one type parameter
              call.typeArguments[0] = typeKey.type
              // Set value arguments
              call.arguments[0] = irGet(thisReceiverParameter) // graph
              call.arguments[1] = irInt(id) // id
            }

            // Wrap in the appropriate caching mechanism
            val binding = bindingGraph.requireBinding(typeKey, IrBindingStack.empty())
            val wrapped = if (binding.isScoped()) {
              irInvoke(
                callee = symbols.doubleCheckProvider,
                args = listOf(spNew)
              )
            } else {
              // Use DoubleCheck for unscoped bindings (SingleCheck not yet available)
              irInvoke(
                callee = symbols.doubleCheckProvider,
                args = listOf(spNew)
              )
            }
            wrapped
          } else {
            // Fallback to original instanceFactory wrapping the instance field
            instanceFactory(typeKey.type, irGetField(irGet(thisReceiverParameter), instanceField))
          }
        }

        // Register the provider field
        bindingFieldContext.putProviderField(typeKey, providerField)

        // Register both fields in the shard field registry if sharding is enabled
        if (shardingPlan != null) {
          val shardIndex = shardingPlan.bindingToShard[typeKey] ?: 0
          // We need to get the binding for this typeKey
          val binding = bindingGraph.requireBinding(typeKey, IrBindingStack.empty())

          // Register instance field
          shardFieldRegistry.registerField(
            typeKey = typeKey,
            shardIndex = shardIndex,
            field = instanceField,
            fieldName = instanceFieldName,
            binding = binding
          )

          // Register provider field
          shardFieldRegistry.registerField(
            typeKey = typeKey,
            shardIndex = shardIndex,
            field = providerField,
            fieldName = providerFieldName,
            binding = binding
          )
        }
      }

      node.creator?.let { creator ->
        for ((i, param) in creator.parameters.regularParameters.withIndex()) {
          val isBindsInstance = param.isBindsInstance

          // TODO if we copy the annotations over in FIR we can skip this creator lookup all
          //  together
          val irParam = ctor.regularParameters[i]

          if (isBindsInstance) {
            // 1) Create instance field to back true @BindsInstance reads
            val instFieldName = fieldNameAllocator.newName(
              param.name.asString().removePrefix("$").decapitalizeUS().suffixIfNot("Instance")
            )
            val instanceField = graphClass.addSimpleInstanceField(
              name = instFieldName,
              typeKey = param.typeKey
            ) { irGet(irParam) }
            bindingFieldContext.putInstanceField(param.typeKey, instanceField)

            // 2) Create provider field that wraps the instance (SwitchingProvider or instanceFactory path)
            addBoundInstanceField(param.typeKey, param.name) { _, _ -> irGet(irParam) }
          } else if (creator.bindingContainersParameterIndices.isSet(i)) {
            addBoundInstanceField(param.typeKey, param.name) { _, _ -> irGet(irParam) }
          } else {
            // It's a graph dep. Add all its accessors as available keys and point them at
            // this constructor parameter for provider field initialization
            val graphDep =
              node.includedGraphNodes[param.typeKey]
                ?: reportCompilerBug("Undefined graph node ${param.typeKey}")

            // Don't add it if it's not used
            if (param.typeKey !in sealResult.reachableKeys) continue

            val graphDepField =
              graphClass.addSimpleInstanceField(
                fieldNameAllocator.newName(graphDep.sourceGraph.name.asString() + "Instance"),
                param.typeKey,
              ) {
                irGet(irParam)
              }
            // Link both the graph typekey and the (possibly-impl type)
            bindingFieldContext.putInstanceField(param.typeKey, graphDepField)
            bindingFieldContext.putInstanceField(graphDep.typeKey, graphDepField)

            if (graphDep.hasExtensions) {
              val depMetroGraph = graphDep.sourceGraph.metroGraphOrFail
              val paramName = depMetroGraph.sourceGraphIfMetroGraph.name
              addBoundInstanceField(param.typeKey, paramName) { _, _ -> irGet(irParam) }
            }
          }
        }
      }

      // Create managed binding containers instance fields if used
      val allBindingContainers = buildSet {
        addAll(node.bindingContainers)
        addAll(node.allExtendedNodes.values.flatMap { it.bindingContainers })
      }
      allBindingContainers
        .sortedBy { it.kotlinFqName.asString() }
        .forEach { clazz ->
          addBoundInstanceField(IrTypeKey(clazz), clazz.name) { _, _ ->
            irCall(clazz.primaryConstructor!!.symbol)
          }
        }

      // Don't add it if it's not used
      if (node.typeKey in sealResult.reachableKeys) {
        val thisGraphField =
          graphClass.addSimpleInstanceField(fieldNameAllocator.newName("thisGraphInstance"), node.typeKey) {
            irGet(thisReceiverParameter)
          }

        bindingFieldContext.putInstanceField(node.typeKey, thisGraphField)

        // Expose the graph as a provider field
        // TODO this isn't always actually needed but different than the instance field above
        //  would be nice if we could determine if this field is unneeded
        val target = ownerClassFor(node.typeKey)
        val field =
          target.getOrCreateBindingField(
            node.typeKey,
            { fieldNameAllocator.newName("thisGraphInstanceProvider") },
            { symbols.metroProvider.typeWith(node.typeKey.type) },
          ).initFinal {
            if (switchingProviderClass != null && spCtor != null) {
              // Use SwitchingProvider wrapped in caching for graph provider too
              val id = switchingIds.getOrPut(node.typeKey) {
                val newId = nextSwitchId++
                if (debug) {
                  log("IrGraphGenerator: Registering Graph ${node.typeKey.render(short = true)} with SwitchingProvider id=$newId")
                }
                newId
              }
              val spNew = irCall(spCtor.symbol).also { call ->
                // Set type arguments - the SwitchingProvider has one type parameter
                call.typeArguments[0] = node.typeKey.type
                // Set value arguments
                call.arguments[0] = irGet(thisReceiverParameter) // graph
                call.arguments[1] = irInt(id) // id
              }

              // Graph bindings are typically unscoped, use DoubleCheck (SingleCheck not yet available)
              irInvoke(
                callee = symbols.doubleCheckProvider,
                args = listOf(spNew)
              )
            } else {
              // Fallback to original instanceFactory
              instanceFactory(
                node.typeKey.type,
                irGetField(irGet(thisReceiverParameter), thisGraphField),
              )
            }
          }

        bindingFieldContext.putProviderField(node.typeKey, field)

        // Register the field in the shard field registry if sharding is enabled
        if (shardingPlan != null) {
          val shardIndex = shardingPlan.bindingToShard[node.typeKey] ?: 0
          shardFieldRegistry.registerField(
            typeKey = node.typeKey,
            shardIndex = shardIndex,
            field = field,
            fieldName = field.name.asString(),
            binding = bindingGraph.requireBinding(node.typeKey, IrBindingStack.empty())
          )
        }
      }

      // Collect bindings and their dependencies for provider field ordering
      val initOrder =
        parentTracer.traceNested("Collect bindings") {
          val collector = ProviderFieldCollector(bindingGraph)
          // Mark all accessor bindings for field generation to ensure consistent property behavior
          node.accessors.forEach { (_, contextualTypeKey) ->
            collector.markForField(contextualTypeKey)
            // Mark the binding as a graph interface return for PROMPT 7
            val binding = bindingGraph.requireBinding(contextualTypeKey, IrBindingStack.empty())
            binding.isGraphInterfaceReturn = true
          }
          val providerFieldBindings = collector.collect()
          buildList(providerFieldBindings.size) {
            for (key in sealResult.sortedKeys) {
              if (key in sealResult.reachableKeys) {
                providerFieldBindings[key]?.let(::add)
              }
            }
          }
        }

      // For all deferred types, assign them first as factories
      // Deferred types need special handling to break cycles - they use DelegateFactory
      // which allows setting the actual provider later, after all dependencies are initialized.
      // If a type depends on a deferred type, it will also need to be provided via a provider
      // to ensure proper initialization order.
      @Suppress("UNCHECKED_CAST")
      val deferredFields: Map<IrTypeKey, IrField> =
        sealResult.deferredTypes.associateWith { deferredTypeKey ->
          val binding = bindingGraph.requireBinding(deferredTypeKey, IrBindingStack.empty())
          val target = ownerClassFor(binding.typeKey)
          val fieldName = fieldNameAllocator.newName(binding.nameHint.decapitalizeUS() + "Provider")
          val field =
            target.getOrCreateBindingField(binding.typeKey,
                { fieldName },
                { deferredTypeKey.type.wrapInProvider(symbols.metroProvider) },
              )
              .withInit(binding.typeKey) { thisReceiver, _ ->
                // Deferred types use DelegateFactory for cycle breaking
                // Note: We cannot use SwitchingProvider here because we need the ability
                // to set the delegate after construction to break cycles
                irInvoke(
                  callee = symbols.metroDelegateFactoryConstructor,
                  typeArgs = listOf(deferredTypeKey.type),
                )
              }

          bindingFieldContext.putProviderField(deferredTypeKey, field)

          // Register the field in the shard field registry if sharding is enabled
          if (shardingPlan != null) {
            val shardIndex = shardingPlan.bindingToShard[binding.typeKey] ?: 0
            shardFieldRegistry.registerField(
              typeKey = binding.typeKey,
              shardIndex = shardIndex,
              field = field,
              fieldName = fieldName,
              binding = binding
            )
          }

          field
        }

      // Create fields in dependency-order
      initOrder
        .asSequence()
        .filterNot {
          // Don't generate deferred types here, we'll generate them last
          it.typeKey in deferredFields ||
            // Don't generate fields for anything already provided in provider/instance fields (i.e.
            // bound instance types)
            it.typeKey in bindingFieldContext ||
            // We don't generate fields for these even though we do track them in dependencies
            // above, it's just for propagating their aliased type in sorting
            it is IrBinding.Alias ||
            // For implicit outer class receivers we don't need to generate a field for them
            (it is IrBinding.BoundInstance && it.classReceiverParameter != null) ||
            // Parent graph bindings don't need duplicated fields
            (it is IrBinding.GraphDependency && it.fieldAccess != null)
        }
        .toList()
        .also { fieldBindings ->
          writeDiagnostic("keys-providerFields-${parentTracer.tag}.txt") {
            fieldBindings.joinToString("\n") { it.typeKey.toString() }
          }
          writeDiagnostic("keys-scopedProviderFields-${parentTracer.tag}.txt") {
            fieldBindings.filter { it.isScoped() }.joinToString("\n") { it.typeKey.toString() }
          }
        }
        .forEach { binding ->
          val key = binding.typeKey

          // Check if this binding should go into a shard
          val targetShardIndex = shardingPlan?.bindingToShard?.get(key)
          val targetShard = targetShardIndex?.let { shardInfos[it] }

          // Since assisted and member injections don't implement Factory, we can't just type these
          // as Provider<*> fields
          var isProviderType = true
          val suffix: String
          val fieldType =
            when (binding) {
              is IrBinding.ConstructorInjected if binding.isAssisted -> {
                isProviderType = false
                suffix = "Factory"
                binding.classFactory.factoryClass.typeWith() // TODO generic factories?
              }
              else -> {
                suffix = "Provider"
                symbols.metroProvider.typeWith(key.type)
              }
            }

          // Determine where to create the field (main class or shard)
          val targetClass = if (targetShard != null && !targetShard.shard.isComponentShard) {
            if (options.debug) {
              log("[MetroSharding] Distributing field for ${binding.typeKey.render(short = true)} to Shard${targetShard.shard.index}")
            }
            targetShard.shardClass
          } else {
            this // Main graph class
          }

          // If we've reserved a field for this key here, pull it out and use that
          val fieldName = fieldNameAllocator.newName(binding.nameHint.decapitalizeUS().suffixIfNot(suffix))
          val field = targetClass.getOrCreateBindingField(
              binding.typeKey,
              { fieldName },
              { fieldType },
            )

          // Register the field in the shard field registry
          // Always register fields when sharding is available, even if not actively sharding
          // This ensures singleton behavior is maintained
          if (shardingPlan != null) {
            val shardIndex = targetShardIndex ?: 0 // 0 represents the main component
            if (options.debug) {
              log("[MetroSharding] Registering field '$fieldName' for ${binding.typeKey.render(short = true)} in shard $shardIndex")
            }
            shardFieldRegistry.registerField(
              typeKey = binding.typeKey,
              shardIndex = shardIndex,
              field = field,
              fieldName = fieldName,
              binding = binding
            )
          }

          val accessType =
            if (isProviderType) {
              IrGraphExpressionGenerator.AccessType.PROVIDER
            } else {
              IrGraphExpressionGenerator.AccessType.INSTANCE
            }

          field.withInit(key) { thisReceiver, typeKey ->
            if (switchingProviderClass != null && spCtor != null && isProviderType) {
              // Use SwitchingProvider wrapped in caching
              val id = switchingIds.getOrPut(binding.typeKey) {
                val newId = nextSwitchId++
                if (debug) {
                  log("IrGraphGenerator: Registering binding ${binding.typeKey.render(short = true)} (${binding::class.simpleName}) with SwitchingProvider id=$newId")
                }
                newId
              }
              // Create the properly parameterized type: SwitchingProvider<ConcreteType>
              val spType = switchingProviderClass.symbol.typeWith(binding.typeKey.type)

              // Use irCallConstructor with the parameterized type and type arguments list
              val spNew = irCallConstructor(spCtor.symbol, listOf(binding.typeKey.type)).also { call ->
                // Set value arguments using direct array syntax
                call.arguments[0] = irGet(thisReceiver) // graph
                call.arguments[1] = irInt(id) // id
              }

              // CRITICAL: Caching wrappers (DoubleCheck/SingleCheck) are applied HERE at field
              // initialization, NOT in SwitchingProvider's invoke() method. This ensures proper
              // singleton semantics while keeping the dispatch logic simple and efficient.
              //
              // Wrap in appropriate caching mechanism based on scoping
              if (binding.isScoped()) {
                // DoubleCheck.provider(<provider>) - Thread-safe for scoped bindings
                irInvoke(
                  callee = symbols.doubleCheckProvider,
                  args = listOf(spNew)
                )
              } else {
                // For unscoped bindings, use DoubleCheck (SingleCheck not yet available)
                irInvoke(
                  callee = symbols.doubleCheckProvider,
                  args = listOf(spNew)
                )
              }
            } else {
              // Use original implementation (SwitchingProvider disabled or not provider type)
              expressionGeneratorFactory
                .create(thisReceiver)
                .generateBindingCode(binding, accessType = accessType, fieldInitKey = typeKey)
                .letIf(binding.isScoped() && isProviderType) {
                  // If it's scoped, wrap it in double-check
                  // DoubleCheck.provider(<provider>)
                  it.doubleCheck(this@withInit, symbols, binding.typeKey)
                }
            }
          }

          bindingFieldContext.putProviderField(key, field)
        }

      // Add statements to our constructor's deferred fields _after_ we've added all provider
      // fields for everything else. This is important in case they reference each other
      for ((deferredTypeKey, field) in deferredFields) {
        val binding = bindingGraph.requireBinding(deferredTypeKey, IrBindingStack.empty())
        initStatements.add { thisReceiver ->
          val delegateProvider = createIrBuilder(symbol).run {
            // Check if we can use SwitchingProvider for the delegate
            if (switchingProviderClass != null && spCtor != null) {
              // Register this deferred binding with SwitchingProvider
              val id = switchingIds.getOrPut(binding.typeKey) {
                val newId = nextSwitchId++
                if (debug) {
                  log("IrGraphGenerator: Registering deferred binding ${binding.typeKey.render(short = true)} with SwitchingProvider id=$newId")
                }
                newId
              }

              // Create SwitchingProvider instance for the deferred type
              val spNew = irCallConstructor(spCtor.symbol, listOf(binding.typeKey.type)).also { call ->
                call.arguments[0] = irGet(thisReceiver) // graph
                call.arguments[1] = irInt(id) // id
              }

              // Apply caching if needed
              if (binding.isScoped()) {
                irInvoke(
                  callee = symbols.doubleCheckProvider,
                  args = listOf(spNew)
                )
              } else {
                spNew // Unscoped deferred types don't need caching at delegate level
              }
            } else {
              // Fallback to original implementation
              expressionGeneratorFactory
                .create(thisReceiver)
                .generateBindingCode(
                  binding,
                  accessType = IrGraphExpressionGenerator.AccessType.PROVIDER,
                  fieldInitKey = deferredTypeKey,
                )
                .letIf(binding.isScoped()) {
                  // If it's scoped, wrap it in double-check
                  // DoubleCheck.provider(<provider>)
                  it.doubleCheck(this@run, symbols, binding.typeKey)
                }
            }
          }

          // Set the delegate for the DelegateFactory
          irInvoke(
            dispatchReceiver = irGetObject(symbols.metroDelegateFactoryCompanion),
            callee = symbols.metroDelegateFactorySetDelegate,
            typeArgs = listOf(deferredTypeKey.type),
            args = listOf(
              irGetField(irGet(thisReceiver), field),
              delegateProvider
            ),
          )
        }
      }

      // Complete shard initialization after all fields are created
      if (shardingPlan?.requiresSharding() == true) {
        parentTracer.traceNested("Complete shard initialization") {
          completeShardInitialization(shardInitializers)
        }
      }

      // Add shard initializations to constructor statements (BEFORE other field initializations)
      constructorStatements.addAll(shardInitializers)

      // Get field initializers for the main graph class
      val mainGraphInitializers = fieldInitializersByClass[this@with] ?: emptyList()

      if (
        options.chunkFieldInits &&
          mainGraphInitializers.size + initStatements.size > STATEMENTS_PER_METHOD
      ) {
        // Larger graph, split statements
        // Chunk our constructor statements and split across multiple init functions
        val chunks =
          buildList<IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrStatement> {
              // Add field initializers first
              for ((field, typeKey, init) in mainGraphInitializers) {
                add { thisReceiver ->
                  // Use the stored type key directly, no registry lookup needed
                  irSetField(
                    irGet(thisReceiver),
                    field,
                    init(thisReceiver, typeKey),
                  )
                }
              }
              for (statement in initStatements) {
                add { thisReceiver -> statement(thisReceiver) }
              }
            }
            .chunked(STATEMENTS_PER_METHOD)

        val initAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
        val initFunctionsToCall =
          chunks.map { statementsChunk ->
            val initName = initAllocator.newName("init")
            addFunction(initName, irBuiltIns.unitType, visibility = DescriptorVisibilities.PRIVATE)
              .apply {
                val localReceiver = thisReceiverParameter.copyTo(this)
                setDispatchReceiver(localReceiver)
                buildBlockBody {
                  for (statement in statementsChunk) {
                    +statement(localReceiver)
                  }
                }
              }
          }
        constructorStatements += buildList {
          for (initFunction in initFunctionsToCall) {
            add { dispatchReceiver ->
              irInvoke(dispatchReceiver = irGet(dispatchReceiver), callee = initFunction.symbol)
            }
          }
        }
      } else {
        // Small graph, just do it in the constructor
        // Assign those initializers directly to their fields and mark them as final
        for ((field, typeKey, init) in mainGraphInitializers) {
          field.initFinal {
            // Use the stored type key directly, no registry lookup needed
            init(thisReceiverParameter, typeKey)
          }
        }
        constructorStatements += initStatements
      }

      // Add extra constructor statements
      with(ctor) {
        val originalBody = checkNotNull(body)
        buildBlockBody {
          +originalBody.statements
          for (statement in constructorStatements) {
            +statement(thisReceiverParameter)
          }
        }
      }

      // Populate SwitchingProvider if it exists
      parentTracer.traceNested("Populate SwitchingProvider") {
        populateSwitchingProviderIfExists(switchingProviderClass, switchingIds)
      }

      parentTracer.traceNested("Implement overrides") { node.implementOverrides() }

      if (graphClass.origin != Origins.GeneratedGraphExtension) {
        parentTracer.traceNested("Generate Metro metadata") {
          // Finally, generate metadata
          val graphProto = node.toProto(bindingGraph = bindingGraph)
          val metroMetadata = MetroMetadata(METRO_VERSION, dependency_graph = graphProto)

          writeDiagnostic({
            "graph-metadata-${node.sourceGraph.kotlinFqName.asString().replace(".", "-")}.kt"
          }) {
            metroMetadata.toString()
          }

          // Write the metadata to the metroGraph class, as that's what downstream readers are
          // looking at and is the most complete view
          graphClass.metroMetadata = metroMetadata
          dependencyGraphNodesByClass(node.sourceGraph.classIdOrFail)?.let { it.proto = graphProto }
        }
      }
    }

  // TODO add asProvider support?
  private fun IrClass.addSimpleInstanceField(
    name: String,
    typeKey: IrTypeKey,
    initializerExpression: IrBuilderWithScope.() -> IrExpression,
  ): IrField {
    // Determine the correct owner class based on sharding
    val owner = ownerClassFor(typeKey)
    val fieldName = name.removePrefix("$$").decapitalizeUS()

    val field = owner.addField(
        fieldName = fieldName,
        fieldType = typeKey.type,
        fieldVisibility = DescriptorVisibilities.PRIVATE,
      )
      .initFinal { initializerExpression() }

    // Register the field in the shard field registry if sharding is enabled
    if (shardingPlan != null) {
      val shardIndex = shardingPlan.bindingToShard[typeKey] ?: 0
      // Try to get the binding if available
      val binding = try {
        bindingGraph.requireBinding(typeKey, IrBindingStack.empty())
      } catch (e: Exception) {
        // For non-binding fields like thisGraphInstance, create a placeholder
        null
      }

      if (binding != null) {
        shardFieldRegistry.registerField(
          typeKey = typeKey,
          shardIndex = shardIndex,
          field = field,
          fieldName = fieldName,
          binding = binding
        )
      }
    }

    return field
  }

  private fun DependencyGraphNode.implementOverrides() {
    // Implement abstract getters for accessors
    accessors.forEach { (function, contextualTypeKey) ->
      function.ir.apply {
        val declarationToFinalize =
          function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
        }
        val irFunction = this
        val binding = bindingGraph.requireBinding(contextualTypeKey, IrBindingStack.empty())
        body =
          createIrBuilder(symbol).run {
            if (binding is IrBinding.Multibinding) {
              // TODO if we have multiple accessors pointing at the same type, implement
              //  one and make the rest call that one. Not multibinding specific. Maybe
              //  groupBy { typekey }?
            }
            // Determine the access type based on whether the property returns a Provider
            val accessType = if (contextualTypeKey.requiresProviderInstance) {
              IrGraphExpressionGenerator.AccessType.PROVIDER
            } else {
              IrGraphExpressionGenerator.AccessType.INSTANCE
            }
            irExprBodySafe(
              symbol,
              typeAsProviderArgument(
                contextualTypeKey,
                expressionGeneratorFactory
                  .create(irFunction.dispatchReceiverParameter!!)
                  .generateBindingCode(binding, contextualTypeKey = contextualTypeKey, accessType = accessType),
                isAssisted = false,
                isGraphInstance = false,
              ),
            )
          }
      }
    }

    // Implement abstract injectors
    injectors.forEach { (overriddenFunction, contextKey) ->
      val typeKey = contextKey.typeKey
      overriddenFunction.ir.apply {
        finalizeFakeOverride(graphClass.thisReceiverOrFail)
        val targetParam = regularParameters[0]
        val binding =
          bindingGraph.requireBinding(contextKey, IrBindingStack.empty())
            as IrBinding.MembersInjected

        // We don't get a MembersInjector instance/provider from the graph. Instead, we call
        // all the target inject functions directly
        body =
          createIrBuilder(symbol).irBlockBody {
            // TODO reuse, consolidate calling code with how we implement this in
            //  constructor inject code gen
            // val injectors =
            // membersInjectorTransformer.getOrGenerateAllInjectorsFor(declaration)
            // val memberInjectParameters = injectors.flatMap { it.parameters.values.flatten()
            // }

            // Extract the type from MembersInjector<T>
            val wrappedType =
              typeKey.copy(typeKey.type.expectAs<IrSimpleType>().arguments[0].typeOrFail)

            for (type in
              pluginContext
                .referenceClass(binding.targetClassId)!!
                .owner
                .getAllSuperTypes(excludeSelf = false, excludeAny = true)) {
              val clazz = type.rawType()
              val generatedInjector =
                membersInjectorTransformer.getOrGenerateInjector(clazz) ?: continue
              for ((function, unmappedParams) in generatedInjector.declaredInjectFunctions) {
                val parameters =
                  if (typeKey.hasTypeArgs) {
                    val remapper = function.typeRemapperFor(wrappedType.type)
                    function.parameters(remapper)
                  } else {
                    unmappedParams
                  }
                // Record for IC
                trackFunctionCall(this@apply, function)
                +irInvoke(
                  dispatchReceiver = irGetObject(function.parentAsClass.symbol),
                  callee = function.symbol,
                  args =
                    buildList {
                      add(irGet(targetParam))
                      // Always drop the first parameter when calling inject, as the first is the
                      // instance param
                      for (parameter in parameters.regularParameters.drop(1)) {
                        val paramBinding =
                          bindingGraph.requireBinding(
                            parameter.contextualTypeKey,
                            IrBindingStack.empty(),
                          )
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
                    },
                )
              }
            }
          }
      }
    }

    // Implement no-op bodies for Binds providers
    // Note we can't source this from the node.bindsCallables as those are pointed at their original
    // declarations and we need to implement their fake overrides here
    bindsFunctions.forEach { function ->
      function.ir.apply {
        val declarationToFinalize = propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
        if (declarationToFinalize.isFakeOverride) {
          declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
        }
        body = stubExpressionBody()
      }
    }

    // Implement bodies for contributed graphs
    // Sort by keys when generating so they have deterministic ordering
    // TODO make the value types something more strongly typed
    for ((typeKey, functions) in graphExtensions) {
      for (extensionAccessor in functions) {
        val function = extensionAccessor.accessor
        function.ir.apply {
          val declarationToFinalize =
            function.ir.propertyIfAccessor.expectAs<IrOverridableDeclaration<*>>()
          if (declarationToFinalize.isFakeOverride) {
            declarationToFinalize.finalizeFakeOverride(graphClass.thisReceiverOrFail)
          }
          val irFunction = this

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
                  accessor = function.ir,
                  // Implementing a factory SAM, no scoping or dependencies here,
                  extensionScopes = emptySet(),
                  dependencies = emptyList(),
                )
            val contextKey = IrContextualTypeKey.from(function.ir)
            body =
              createIrBuilder(symbol).run {
                irExprBodySafe(
                  symbol,
                  expressionGeneratorFactory
                    .create(irFunction.dispatchReceiverParameter!!)
                    .generateBindingCode(binding = binding, contextualTypeKey = contextKey),
                )
              }
          }
        }
      }
    }
  }

  /**
   * Populates the SwitchingProvider invoke() method if it exists.
   * This should be called after all fields are created and initialized.
   */
  private fun IrClass.populateSwitchingProviderIfExists(
    switchingProviderClass: IrClass?,
    switchingIds: Map<IrTypeKey, Int>
  ) {
    if (switchingProviderClass == null) {
      // If we have bindings to handle but no SwitchingProvider class, that's an error
      if (switchingIds.isNotEmpty()) {
        error("Missing SwitchingProvider class in ${this.name} with ${switchingIds.size} bindings to handle – did FIR generation run?")
      }
      // No SwitchingProvider and no bindings, nothing to do
      return
    }

    // Find the invoke function
    val invokeFun = switchingProviderClass.declarations
      .filterIsInstance<IrSimpleFunction>()
      .firstOrNull { it.name.asString() == "invoke" }
      ?: error("SwitchingProvider must have invoke() function")

    // If we have SwitchingProvider but no bindings, provide a fake implementation
    if (switchingIds.isEmpty()) {
      if (debug) {
        log("IrGraphGenerator: No bindings registered for SwitchingProvider - generating error body")
      }
      invokeFun.body = irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
        val builder = createIrBuilder(invokeFun.symbol)
        statements += builder.irReturn(
          builder.irInvoke(
            callee = symbols.stdlibErrorFunction,
            args = listOf(builder.irString("SwitchingProvider not implemented - no bindings registered"))
          )
        )
      }
      return
    }

    if (debug) {
      log("IrGraphGenerator: Populating SwitchingProvider with ${switchingIds.size} bindings")
    }

    // Build the ordered list of bindings based on their IDs
    val idToBinding = switchingIds.entries
      .sortedBy { it.value }
      .mapNotNull { (typeKey, _) ->
        this@IrGraphGenerator.bindingGraph.bindingsSnapshot()[typeKey]
      }

    // Get the dispatch receiver of invoke (the SwitchingProvider instance)
    val spThis = requireNotNull(invokeFun.dispatchReceiverParameter) {
      "invoke() must have dispatch receiver"
    }

    // Find graph and id fields in SwitchingProvider
    val graphField = switchingProviderClass.declarations.filterIsInstance<IrField>()
      .firstOrNull { it.name == Symbols.Names.graph }
      ?: error("SwitchingProvider must have field: graph")
    val idField = switchingProviderClass.declarations.filterIsInstance<IrField>()
      .firstOrNull { it.name == Symbols.Names.id }
      ?: error("SwitchingProvider must have field: id")

    // Build the invoke body
    invokeFun.body = irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
      val builder = createIrBuilder(invokeFun.symbol)

      // Get graph from SwitchingProvider field (created once, can be reused)
      val graphExpr = builder.irGetField(builder.irGet(spThis), graphField)

      // Create a lambda to generate fresh ID field access expressions
      // This avoids the "duplicate IR node" validation error
      val idExprFactory: () -> IrExpression = {
        builder.irGetField(builder.irGet(spThis), idField)
      }

      // CRITICAL: Build expression generator with the SwitchingProvider's dispatch receiver
      // The expression generator will use the graph field from SwitchingProvider for field access
      // This ensures correct receiver context when generating binding code inside invoke()
      val expressionGenerator = expressionGeneratorFactory.create(spThis)

      // Call the SwitchingProviderGenerator with the new API
      val switchingGenerator = SwitchingProviderGenerator(
        context = this@IrGraphGenerator,
        bindingFieldContext = bindingFieldContext,
        shardFieldRegistry = shardFieldRegistry,
        expressionGenerator = expressionGenerator
      )

      // Populate the body with graph-aware expressions
      // Note: We pass idExprFactory() to create a fresh expression for the method
      val invokeStatements = switchingGenerator.populateInvokeBody(
        builder = builder,
        graphClass = this@populateSwitchingProviderIfExists,
        switchingProviderClass = switchingProviderClass,
        idToBinding = idToBinding,
        graphExpr = graphExpr,
        idExpr = idExprFactory(),  // Create one fresh expression for this call
        returnType = invokeFun.returnType
      )
      statements.addAll(invokeStatements)
    }
  }

  /**
   * Generates shard class shells early so that ownerClassFor() can find them during field creation.
   * This creates the shard classes but doesn't initialize their fields yet.
   */
  private fun IrClass.generateShardShells() {
    requireNotNull(shardingPlan) { "generateShardShells called without sharding plan" }

    if (options.debug) {
      log("[MetroSharding] Generating ${shardingPlan.additionalShards().size} shard class shells")
    }

    val infos = mutableMapOf<Int, ShardGenerator.ShardInfo>()

    // Process each shard (skipping shard 0 which is the main component)
    for (shard in shardingPlan.additionalShards()) {
      if (options.debug) {
        log("[MetroSharding] Generating shell for Shard${shard.index} with ${shard.bindings.size} bindings")
      }

      val generator = ShardGenerator(
        context = this@IrGraphGenerator,
        parentClass = this,
        shard = shard,
        bindingGraph = bindingGraph,
        fieldNameAllocator = fieldNameAllocator,
        shardingPlan = shardingPlan,
        fieldRegistry = shardFieldRegistry
      )

      // Generate the shard class shell (without field initialization)
      val shardClass = generator.generateShardClass(null)

      // Generate the field in the parent class to hold this shard instance
      val shardField = generator.generateShardField(shardClass)

      // Create ShardInfo
      val shardInfo = ShardGenerator.ShardInfo(
        shard = shard,
        shardClass = shardClass,
        shardField = shardField,
        generator = generator
      )

      infos[shard.index] = shardInfo
    }

    // Store for later use in binding distribution and initialization
    shardInfos = infos
  }

  /**
   * Detects which module parameters are required by a shard based on its bindings.
   * This follows Dagger's pattern where each shard only receives the modules it actually needs.
   *
   * @param shard The shard to analyze
   * @param bindingGraph The binding graph containing all bindings
   * @return List of module parameters needed by this shard
   */
  private fun detectRequiredModulesForShard(
    shard: ShardingPlan.Shard,
    bindingGraph: IrBindingGraph
  ): List<IrValueParameter> {
    val requiredModules = mutableSetOf<IrClass>()

    // Analyze each binding in the shard to find module dependencies
    for (typeKey in shard.bindings) {
      val binding = bindingGraph.bindingsSnapshot()[typeKey] ?: continue
      if (binding is IrBinding.Provided) {
        // This binding comes from a @Provides method, track its module
        val providerFactory = binding.providerFactory
        val moduleClass = providerFactory.clazz.parentAsClass

        // Check if this is actually a binding container class (has @BindingContainer annotation)
        // For now, we'll assume all provider factories come from binding containers
        // TODO: Add proper annotation checking when class metadata is accessible
        requiredModules.add(moduleClass)
      }
    }

    // Convert module classes to constructor parameters
    // For now, return empty list as we need the actual parameter references from the constructor
    // This will be properly implemented when we have access to the constructor parameters
    return emptyList()
  }

  /**
   * Completes shard initialization after all fields have been created.
   * This adds the field initializers and shard instantiation to the constructor.
   */
  private fun IrClass.completeShardInitialization(
    shardInitializers: MutableList<IrBuilderWithScope.(IrValueParameter) -> IrStatement>
  ) {
    requireNotNull(shardingPlan) { "completeShardInitialization called without sharding plan" }

    if (options.debug) {
      log("[MetroSharding] Completing initialization for ${shardInfos.size} shards")
    }

    // Process each shard to generate initialization methods
    for ((shardIndex, shardInfo) in shardInfos) {
      val shardClass = shardInfo.shardClass
      val shardInitializersForClass = fieldInitializersByClass[shardClass] ?: emptyList()

      if (shardInitializersForClass.isNotEmpty()) {
        if (options.debug) {
          log("[MetroSharding] Shard${shardIndex} has ${shardInitializersForClass.size} field initializers")
        }

        // Generate the initializeFields method for this shard
        val initMethod = shardInfo.generator.generateShardFieldInitialization(shardInfo, shardInitializersForClass)

        // Update the constructor body to call initializeFields
        val constructor = shardClass.primaryConstructor
          ?: error("Shard class missing primary constructor")

        val graphField = shardClass.declarations
          .filterIsInstance<IrField>()
          .first { it.name == Symbols.Names.graph }

        val graphParameter = constructor.nonDispatchParameters.first()

        constructor.body = createIrBuilder(constructor.symbol).irBlockBody {
          val thisRef = requireNotNull(shardClass.thisReceiver) {
            "Shard class ${shardClass.name} missing this receiver"
          }

          // 1. Call super constructor (Any.<init>())
          +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.primaryConstructor!!)

          // 2. Set the graph field: this.graph = graph
          +irSetField(
            receiver = irGet(thisRef),
            field = graphField,
            value = irGet(graphParameter)
          )

          // 3. Call instance initializer
          +IrInstanceInitializerCallImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            shardClass.symbol,
            shardClass.defaultType
          )

          // 4. Call this.initializeFields()
          +irCall(initMethod.symbol).also { call ->
            call.dispatchReceiver = irGet(thisRef)
          }
        }
      }

      // Add field initialization in the main graph constructor
      val shardField = shardInfo.shardField
      shardField?.let { field ->
        shardInitializers.add { thisReceiver ->
          // Initialize the shard field with a new instance
          val initialization = shardInfo.generator.generateShardInitialization(
            shardClass = shardClass,
            thisReceiver = thisReceiver,
            moduleParameters = detectRequiredModulesForShard(shardInfo.shard, bindingGraph)
          )

          // Set the field value
          irSetField(irGet(thisReceiver), field, initialization)
        }
      }
    }
  }

  /**
   * Generates shard classes and their initialization.
   * Following Dagger's pattern, shards are initialized in the constructor before other bindings.
   * @deprecated Use generateShardShells() and completeShardInitialization() instead
   */
  private fun IrClass.generateShards(): Pair<Map<Int, ShardGenerator.ShardInfo>, List<IrBuilderWithScope.(IrValueParameter) -> IrStatement>> {
    requireNotNull(shardingPlan) { "generateShards called without sharding plan" }

    if (options.debug) {
      log("[MetroSharding] Generating ${shardingPlan.additionalShards().size} shard classes")
    }

    // First, generate shard classes with their field initialization methods
    val infos = mutableMapOf<Int, ShardGenerator.ShardInfo>()

    // Process each shard (skipping shard 0 which is the main component)
    for (shard in shardingPlan.additionalShards()) {
      if (options.debug) {
        log("[MetroSharding] Generating Shard${shard.index} for ${shard.bindings.size} bindings")
        val sampleBindings = shard.bindings.take(3).joinToString { it.render(short = true) }
        log("[MetroSharding]   Sample bindings: $sampleBindings")
      }

      val generator = ShardGenerator(
        context = this@IrGraphGenerator,
        parentClass = this,
        shard = shard,
        bindingGraph = bindingGraph,
        fieldNameAllocator = fieldNameAllocator,
        shardingPlan = shardingPlan,
        fieldRegistry = shardFieldRegistry
      )

      // Generate the shard class (initially without initialization)
      // The constructor will be properly set up by generateShardClass
      val shardClass = generator.generateShardClass(null)

      // Get the field initializers for this shard class
      val shardInitializers = fieldInitializersByClass[shardClass] ?: emptyList()

      // Create ShardInfo
      val shardInfo = ShardGenerator.ShardInfo(
        shard = shard,
        shardClass = shardClass,
        shardField = null, // Will be set below
        generator = generator
      )

      // If there are initializers, generate the initializeFields method
      // and update the constructor body to call it
      if (shardInitializers.isNotEmpty()) {
        // Generate the initializeFields method
        val initMethod = generator.generateShardFieldInitialization(shardInfo, shardInitializers)

        // The constructor already has proper structure from generateShardClass,
        // we just need to update it to call initializeFields
        val constructor = shardClass.primaryConstructor
          ?: error("Shard class missing primary constructor")

        // Rebuild constructor body to include initializeFields call
        val graphField = shardClass.declarations
          .filterIsInstance<IrField>()
          .first { it.name == Symbols.Names.graph }

        val graphParameter = constructor.nonDispatchParameters.first()

        constructor.body = createIrBuilder(constructor.symbol).irBlockBody {
          val thisRef = requireNotNull(shardClass.thisReceiver) {
            "Shard class ${shardClass.name} missing this receiver"
          }

          // 1. Call super constructor (Any.<init>())
          +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.primaryConstructor!!)

          // 2. Set the graph field: this.graph = graph
          +irSetField(
            receiver = irGet(thisRef),
            field = graphField,
            value = irGet(graphParameter)
          )

          // 3. Call instance initializer
          +IrInstanceInitializerCallImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            shardClass.symbol,
            shardClass.defaultType
          )

          // 4. Call this.initializeFields()
          +irCall(initMethod.symbol).also { call ->
            call.dispatchReceiver = irGet(thisRef)
          }
        }
      }

      // Generate the field in the parent class to hold this shard instance
      val shardField = generator.generateShardField(shardClass)

      // Update the ShardInfo with the field
      val updatedShardInfo = shardInfo.copy(shardField = shardField)
      infos[shard.index] = updatedShardInfo
    }

    // Store for later use in binding distribution
    shardInfos = infos

    // Collect field initializations to add to constructor
    val shardInitializers = mutableListOf<IrBuilderWithScope.(IrValueParameter) -> IrStatement>()

    // Initialize shard fields in constructor
    // This must happen BEFORE other binding fields are initialized
    for ((shardIndex, shardInfo) in infos) {
      val shard = shardInfo.shard
      val shardField = shardInfo.shardField
      val shardClass = shardInfo.shardClass

      // Determine which modules this shard needs based on its bindings
      val moduleParams = detectRequiredModulesForShard(shard, bindingGraph)

      // Add field initialization in the constructor
      shardField?.let { field ->
        shardInitializers.add { thisReceiver ->
          // Initialize the shard field with a new instance
          val initialization = shardInfo.generator.generateShardInitialization(
            shardClass = shardClass,
            thisReceiver = thisReceiver,
            moduleParameters = moduleParams
          )

          // Set the field value
          irSetField(irGet(thisReceiver), field, initialization)
        }
      }
    }

    // Return both the infos and their initializations
    return infos to shardInitializers
  }

  // TODO: Add SwitchingProvider implementation in a follow-up PR
  // The SwitchingProvider pattern requires more careful integration with the
  // existing provider field generation logic
  /*
  private fun IrClass.generateSwitchingProvider(
    providerCases: Map<Int, IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrExpression>,
    typeParameter: IrType = context.irBuiltIns.anyNType
  ): IrClass {
    val switchingProviderClass = context.pluginContext.irFactory.buildClass {
      name = "SwitchingProvider".asName()
      kind = ClassKind.CLASS
      visibility = DescriptorVisibilities.PRIVATE
      modality = Modality.FINAL
      origin = Origins.Default
    }

    // Make it a nested class of the component
    addChild(switchingProviderClass)
    switchingProviderClass.createThisReceiverParameter()

    // Add type parameter <T>
    val typeParam = switchingProviderClass.addTypeParameter {
      name = "T".asName()
      variance = Variance.OUT_VARIANCE
    }

    // Add parent and id fields
    val parentField = switchingProviderClass.addField {
      name = "parent".asName()
      type = this@generateSwitchingProvider.defaultType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
    }

    val idField = switchingProviderClass.addField {
      name = "id".asName()
      type = irBuiltIns.intType
      visibility = DescriptorVisibilities.PRIVATE
      isFinal = true
    }

    // Add constructor
    val constructor = switchingProviderClass.addConstructor {
      visibility = DescriptorVisibilities.PUBLIC
      isPrimary = true
    }

    val parentParam = constructor.addValueParameter("parent", this@generateSwitchingProvider.defaultType)
    val idParam = constructor.addValueParameter("id", irBuiltIns.intType)

    constructor.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
      val builder = context.createIrBuilder(constructor.symbol)
      val thisRef = requireNotNull(switchingProviderClass.thisReceiver)

      // Call super constructor
      statements += builder.irDelegatingConstructorCall(context.irBuiltIns.anyClass.owner.constructors.single())

      // Set fields
      statements += builder.irSetField(
        receiver = builder.irGet(thisRef),
        field = parentField,
        value = builder.irGet(parentParam)
      )

      statements += builder.irSetField(
        receiver = builder.irGet(thisRef),
        field = idField,
        value = builder.irGet(idParam)
      )
    }

    // Add Provider<T> supertype
    val providerType = symbols.metroProvider.typeWith(typeParam.defaultType)
    switchingProviderClass.superTypes = listOf(providerType)

    // Generate invoke() method with when expression
    val invokeMethod = switchingProviderClass.addFunction {
      name = "invoke".asName()
      visibility = DescriptorVisibilities.PUBLIC
      returnType = typeParam.defaultType
      modality = Modality.OPEN
    }

    invokeMethod.overriddenSymbols = listOf(
      symbols.metroProvider.owner.declarations
        .filterIsInstance<IrSimpleFunction>()
        .first { it.name.asString() == "invoke" }
        .symbol
    )

    invokeMethod.dispatchReceiverParameter = invokeMethod.addValueParameter(
      "<this>",
      switchingProviderClass.defaultType,
      IrDeclarationOrigin.INSTANCE_RECEIVER
    ).apply {
      index = -1
    }

    // Split cases into chunks if needed (max 100 per method)
    val CASES_PER_METHOD = 100
    if (providerCases.size <= CASES_PER_METHOD) {
      // Single method with all cases
      invokeMethod.body = generateSwitchingProviderBody(
        switchingProviderClass,
        parentField,
        idField,
        providerCases,
        typeParam.defaultType
      )
    } else {
      // Split into multiple methods
      val chunks = providerCases.entries.chunked(CASES_PER_METHOD)
      val helperMethods = chunks.mapIndexed { index, chunk ->
        val helperMethod = switchingProviderClass.addFunction {
          name = "invoke${index + 1}".asName()
          visibility = DescriptorVisibilities.PRIVATE
          returnType = typeParam.defaultType
        }

        helperMethod.buildReceiverParameter { type = switchingProviderClass.defaultType }

        helperMethod.body = generateSwitchingProviderBody(
          switchingProviderClass,
          parentField,
          idField,
          chunk.associate { it.key to it.value },
          typeParam.defaultType
        )

        helperMethod to chunk.first().key..chunk.last().key
      }

      // Main invoke method delegates to helpers based on ID range
      invokeMethod.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
        val builder = context.createIrBuilder(invokeMethod.symbol)
        val thisReceiver = requireNotNull(invokeMethod.dispatchReceiverParameter)
        val idValue = builder.irGetField(builder.irGet(thisReceiver), idField)

        statements += builder.irReturn(
          builder.irWhen(
            typeParam.defaultType,
            helperMethods.map { (method, range) ->
              val condition = if (range.first == range.last) {
                builder.irEquals(idValue, builder.irInt(range.first))
              } else {
                builder.irCall(context.irBuiltIns.intClass.owner.declarations
                  .filterIsInstance<IrSimpleFunction>()
                  .first { it.name.asString() == "rangeTo" }
                  .symbol
                ).apply {
                  dispatchReceiver = builder.irInt(range.first)
                  arguments[0] = builder.irInt(range.last)
                }.let { rangeExpr ->
                  // Call IntRange.contains(value)
                  builder.irCall(
                    rangeExpr.type.getClass()!!.declarations
                      .filterIsInstance<IrSimpleFunction>()
                      .first { it.name.asString() == "contains" }
                      .symbol
                  ).apply {
                    dispatchReceiver = rangeExpr
                    arguments[0] = idValue
                  }
                }
              }

              builder.irBranch(
                condition,
                builder.irCall(method.symbol).apply {
                  dispatchReceiver = builder.irGet(thisReceiver)
                }
              )
            } + builder.irElseBranch(
              builder.irCall(context.irBuiltIns.errorFunction).apply {
                arguments[0] = builder.irString("Unknown provider id")
              }
            )
          )
        )
      }
    }

    return switchingProviderClass
  }

  /**
   * Generates the body of a SwitchingProvider method with when expression.
   */
  private fun generateSwitchingProviderBody(
    switchingProviderClass: IrClass,
    parentField: IrField,
    idField: IrField,
    cases: Map<Int, IrBuilderWithScope.(thisReceiver: IrValueParameter) -> IrExpression>,
    returnType: IrType
  ): IrBlockBody {
    return context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
      val builder = context.createIrBuilder(switchingProviderClass.symbol)
      val thisReceiver = requireNotNull(switchingProviderClass.thisReceiver)

      val parentAccess = builder.irGetField(builder.irGet(thisReceiver), parentField)
      val idValue = builder.irGetField(builder.irGet(thisReceiver), idField)

      // Generate when expression
      val whenExpr = builder.irWhen(
        returnType,
        cases.map { (id, expression) ->
          builder.irBranch(
            builder.irEquals(idValue, builder.irInt(id)),
            expression(builder, parentAccess as IrValueParameter)
          )
        } + builder.irElseBranch(
          builder.irCall(context.irBuiltIns.errorFunction).apply {
            arguments[0] = builder.irString("Unknown provider id")
          }
        )
      )

      statements += builder.irReturn(whenExpr)
    }
  }
  */
}

// Extension function to check if a type is a Provider type
private fun IrType.isProvider(): Boolean {
  val classifier = (this as? IrSimpleType)?.classifier?.owner as? IrClass
  return classifier?.name?.asString()?.contains("Provider") == true
}
