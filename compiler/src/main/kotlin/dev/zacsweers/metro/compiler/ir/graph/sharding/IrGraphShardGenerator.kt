// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.DEFAULT_KEYS_PER_GRAPH_SHARD
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.buildBlockBody
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.generateDefaultConstructorBody
import dev.zacsweers.metro.compiler.ir.graph.InitStatement
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.graph.PropertyInitializer
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.isJvm

/**
 * Generates IR for shard classes when graph sharding is enabled.
 *
 * When a dependency graph has many bindings, initialization code can exceed JVM method size limits.
 * Sharding moves initialization logic into inner shard classes while keeping provider fields on the
 * graph. Each shard exposes `initialize()` to set up a subset of providers. If a shard would exceed
 * the statement limit, its work is split into private `init1()`, `init2()`, etc.
 *
 * Example generated structure:
 * ```kotlin
 * class AppGraph$Impl : AppGraph {
 *   // Provider fields for all bindings
 *   private val repoProvider: Provider<Repository>
 *   private val apiProvider: Provider<ApiService>
 *   // ... more providers ...
 *
 *   // Shard instance fields
 *   private val shard1: Shard1
 *   private val shard2: Shard2
 *
 *   private inner class Shard1 {
 *     // When statements fit in one method:
 *     fun initialize(graph: Impl) {
 *       graph.repoProvider = provider { Repository.MetroFactory.create() }
 *       graph.apiProvider = provider { ApiService.MetroFactory.create(graph.repoProvider) }
 *       // ... more initializations ...
 *     }
 *   }
 *
 *   private inner class Shard2 {
 *     // When statements exceed statementsPerInitFun, they're chunked:
 *     private fun init1(graph: Impl) { ... }
 *     private fun init2(graph: Impl) { ... }
 *
 *     fun initialize(graph: Impl) {
 *       init1(graph)
 *       init2(graph)
 *     }
 *   }
 *
 *   init {
 *     shard1 = Shard1()
 *     shard2 = Shard2()
 *     shard1.initialize(this)
 *     shard2.initialize(this)
 *   }
 * }
 * ```
 */
internal class IrGraphShardGenerator(context: IrMetroContext) : IrMetroContext by context {

  /**
   * Generates shard classes and initialization logic if sharding is needed.
   *
   * @param deferredInit A callback to append deferred initialization logic (e.g. setDelegate calls)
   *   to the end of the initialization sequence.
   * @return A list of initialization statements, or null if no sharding is needed.
   */
  fun generateShards(
    graphClass: IrClass,
    propertyBindings: List<PropertyBinding>,
    plannedGroups: List<List<IrTypeKey>>?,
    bindingGraph: IrBindingGraph,
    diagnosticTag: String,
    deferredInit: (MutableList<InitStatement>) -> Unit,
  ): List<InitStatement>? {
    val shardGroups = planShardGroups(propertyBindings, plannedGroups)
    if (shardGroups.size <= 1) {
      // Only warn if user explicitly customized keysPerGraphShard, as this suggests
      // they expected sharding to occur but the graph is too small
      if (options.keysPerGraphShard != DEFAULT_KEYS_PER_GRAPH_SHARD) {
        reportCompat(
          graphClass,
          MetroDiagnostics.METRO_WARNING,
          "Graph sharding is configured with keysPerGraphShard=${options.keysPerGraphShard}, " +
            "but graph '${graphClass.name.asString()}' has only ${propertyBindings.size} bindings " +
            "(threshold is ${options.keysPerGraphShard}), so sharding is not applied.",
        )
      }
      return null
    }

    // On JVM, shard classes write to the outer graph's backing fields via irSetField().
    // This generates direct field access bytecode, which JVM doesn't allow for private fields
    // across class boundaries (inner classes are separate classes in JVM bytecode).
    // We use protected (package-private + subclass in JVM) rather than internal (public in
    // bytecode) to minimize exposure while still allowing inner class access within the same
    // package.
    // The alternative is we could generate synthetic accessors (which kotlinc somewhat surprisingly
    // doens't do here), but in this case it's kind of unnecessary.
    if (pluginContext.platform.isJvm()) {
      for (binding in propertyBindings) {
        binding.property.backingField?.visibility = DescriptorVisibilities.PROTECTED
      }
    }

    val shardInfos = generateShards(graphClass, shardGroups)

    writeDiagnostic("sharding-plan-${diagnosticTag}.txt") {
      ShardingDiagnostics.generateShardingPlanReport(
        graphClass = graphClass,
        shardInfos = shardInfos,
        initOrder = shardGroups.indices.toList(),
        totalBindings = propertyBindings.size,
        options = options,
        bindingGraph = bindingGraph,
      )
    }

    return buildList {
      // First instantiate all shards
      for (info in shardInfos) {
        add { dispatchReceiver ->
          irSetField(
            irGet(dispatchReceiver),
            info.instanceProperty.backingField!!,
            irCallConstructor(info.shardClass.primaryConstructor!!.symbol, emptyList()).apply {
              this.dispatchReceiver = irGet(dispatchReceiver)
            },
          )
        }
      }

      // Call initializer functions in order
      for (shardIndex in shardGroups.indices) {
        val info = shardInfos[shardIndex]
        add { dispatchReceiver ->
          irInvoke(
            dispatchReceiver =
              irGetField(irGet(dispatchReceiver), info.instanceProperty.backingField!!),
            callee = info.initializeFunction.symbol,
            args =
              buildList {
                add(irGet(dispatchReceiver))
                for (outerParam in info.outerReceiverParams) {
                  add(irGet(outerParam))
                }
              },
          )
        }
      }

      // Add deferred initialization (e.g., setDelegate calls) at the end
      deferredInit(this)
    }
  }

  private fun planShardGroups(
    propertyBindings: List<PropertyBinding>,
    plannedGroups: List<List<IrTypeKey>>?,
  ): List<List<PropertyBinding>> {
    if (propertyBindings.isEmpty() || plannedGroups.isNullOrEmpty()) {
      return listOf(propertyBindings)
    }

    val bindingsByKey = propertyBindings.associateBy { it.typeKey }.toMutableMap()
    val groups =
      plannedGroups.mapNotNull { group ->
        group.mapNotNull(bindingsByKey::remove).takeIf { it.isNotEmpty() }
      }

    return when {
      groups.isEmpty() -> listOf(propertyBindings)
      bindingsByKey.isEmpty() -> groups
      else -> groups + listOf(bindingsByKey.values.toList())
    }
  }

  private fun generateShards(
    graphClass: IrClass,
    shardGroups: List<List<PropertyBinding>>,
  ): List<ShardInfo> {
    val graphReceiver = graphClass.thisReceiverOrFail
    return shardGroups.mapIndexed { index, bindings ->
      val shardName = "Shard${index + 1}"
      val shardClass =
        irFactory
          .buildClass {
            name = Name.identifier(shardName)
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            isInner = true
          }
          .apply {
            superTypes = listOf(irBuiltIns.anyType)
            createThisReceiverParameter()
            parent = graphClass
            graphClass.addChild(this)
          }

      shardClass
        .addConstructor {
          isPrimary = true
          origin = Origins.Default
        }
        .apply {
          setDispatchReceiver(graphReceiver.copyTo(this, type = graphClass.defaultType))
          body = generateDefaultConstructorBody()
        }

      val shardInstanceProperty =
        graphClass
          .addProperty {
            this.name = Name.identifier(shardName.replaceFirstChar { it.lowercase() })
            visibility = DescriptorVisibilities.PRIVATE
          }
          .apply {
            addBackingField { type = shardClass.defaultType }

            addGetter { returnType = shardClass.defaultType }
              .apply {
                val getterReceiver = graphReceiver.copyTo(this)
                setDispatchReceiver(getterReceiver)
                body =
                  createIrBuilder(symbol).irBlockBody {
                    +irReturn(irGetField(irGet(getterReceiver), backingField!!))
                  }
              }
          }

      val initializeFunction =
        shardClass.addFunction("initialize", irBuiltIns.unitType).apply {
          val shardReceiver = shardClass.thisReceiverOrFail.copyTo(this)
          setDispatchReceiver(shardReceiver)
        }

      val graphInstanceParam =
        initializeFunction.addValueParameter("graphInstance", graphClass.defaultType)

      // First pass: generate expressions and collect out-of-scope parameters
      val inScopeParams = setOf(graphInstanceParam)
      val collector = OuterReceiverCollector(inScopeParams)
      val generatedExpressions = mutableListOf<Pair<PropertyBinding, IrExpression>>()

      createIrBuilder(initializeFunction.symbol).run {
        for (binding in bindings) {
          val backingField = binding.property.backingField
          if (backingField != null) {
            val initValue =
              binding.initializer.invoke(this@run, graphInstanceParam, binding.typeKey)
            initValue.acceptChildrenVoid(collector)
            generatedExpressions.add(binding to initValue)
          }
        }
      }

      val outerReceiverParams = mutableListOf<IrValueParameter>()
      val paramMapping = mutableMapOf<IrValueParameter, IrValueParameter>()

      for (outerParam in collector.outOfScopeParams) {
        val newParam =
          initializeFunction.addValueParameter(
            "outer_${outerParam.name.asString()}",
            outerParam.type,
          )
        paramMapping[outerParam] = newParam
        outerReceiverParams.add(outerParam)
      }

      val remapper = if (paramMapping.isNotEmpty()) ParameterRemapper(paramMapping) else null

      // Prepare data for statements (backing field + remapped init value)
      val statementData =
        generatedExpressions.map { (binding, initValue) ->
          binding.property.backingField!! to (remapper?.remap(initValue) ?: initValue)
        }

      // Chunk statements if needed to avoid method size limits
      val mustChunk = options.chunkFieldInits && statementData.size > options.statementsPerInitFun

      if (mustChunk) {
        val functionNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
        val chunks = statementData.chunked(options.statementsPerInitFun)

        val chunkedFunctions =
          chunks.map { chunk ->
            val initName = functionNameAllocator.newName("init")
            shardClass
              .addFunction(
                initName,
                irBuiltIns.unitType,
                visibility = DescriptorVisibilities.PRIVATE,
              )
              .apply {
                val localShardReceiver = shardClass.thisReceiverOrFail.copyTo(this)
                setDispatchReceiver(localShardReceiver)
                val localGraphParam = addValueParameter("graphInstance", graphClass.defaultType)
                // Remap graphInstanceParam to localGraphParam in the expressions
                val paramRemapper = ParameterRemapper(mapOf(graphInstanceParam to localGraphParam))
                buildBlockBody {
                  for ((backingField, initValue) in chunk) {
                    val remappedValue = paramRemapper.remap(initValue)
                    +irSetField(irGet(localGraphParam), backingField, remappedValue)
                  }
                }
              }
          }

        initializeFunction.buildBlockBody {
          for (chunkedFn in chunkedFunctions) {
            +irInvoke(
              dispatchReceiver = irGet(initializeFunction.dispatchReceiverParameter!!),
              callee = chunkedFn.symbol,
              args = listOf(irGet(graphInstanceParam)),
            )
          }
        }
      } else {
        initializeFunction.body =
          createIrBuilder(initializeFunction.symbol).irBlockBody {
            for ((backingField, initValue) in statementData) {
              +irSetField(irGet(graphInstanceParam), backingField, initValue)
            }
          }
      }

      ShardInfo(
        index = index,
        shardClass = shardClass,
        instanceProperty = shardInstanceProperty,
        initializeFunction = initializeFunction,
        bindings = bindings,
        outerReceiverParams = outerReceiverParams,
      )
    }
  }
}

/** Property with its type key and initializer. */
internal data class PropertyBinding(
  val property: IrProperty,
  val typeKey: IrTypeKey,
  val initializer: PropertyInitializer,
)

/** Generated shard class info, with its parent graph property and the initialize function. */
internal data class ShardInfo(
  val index: Int,
  val shardClass: IrClass,
  val instanceProperty: IrProperty,
  val initializeFunction: IrSimpleFunction,
  val bindings: List<PropertyBinding>,
  val outerReceiverParams: List<IrValueParameter>,
)

/**
 * Collects all [IrValueParameter] references from an expression that are not in the given scope.
 * Used to detect when binding code references parameters from outer class constructors.
 */
private class OuterReceiverCollector(private val inScopeParams: Set<IrValueParameter>) :
  IrVisitorVoid() {
  val outOfScopeParams = mutableSetOf<IrValueParameter>()

  override fun visitElement(element: IrElement) {
    element.acceptChildrenVoid(this)
  }

  override fun visitGetValue(expression: IrGetValue) {
    val owner = expression.symbol.owner
    if (owner is IrValueParameter && owner !in inScopeParams) {
      outOfScopeParams.add(owner)
    }
    super.visitGetValue(expression)
  }
}

/** Remaps [IrGetValue] nodes to use substituted parameters. */
private class ParameterRemapper(private val mapping: Map<IrValueParameter, IrValueParameter>) :
  IrElementTransformerVoid() {

  fun remap(expression: IrExpression): IrExpression {
    return expression.transform(this, null)
  }

  override fun visitGetValue(expression: IrGetValue): IrExpression {
    val owner = expression.symbol.owner
    if (owner is IrValueParameter && owner in mapping) {
      return IrGetValueImpl(
        expression.startOffset,
        expression.endOffset,
        mapping.getValue(owner).symbol,
      )
    }
    return super.visitGetValue(expression)
  }
}
