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
import dev.zacsweers.metro.compiler.ir.graph.BindingPropertyContext
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.graph.PropertyInitializer
import dev.zacsweers.metro.compiler.ir.graph.PropertyLocation
import dev.zacsweers.metro.compiler.ir.graph.ShardContext
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name

/**
 * Generates IR for shard classes when graph sharding is enabled.
 *
 * When a dependency graph has many bindings, the generated class can exceed JVM class size limits.
 * Sharding distributes provider fields and their initialization across multiple shard classes. Each
 * shard is a protected static nested class that owns its provider fields directly.
 *
 * Unlike inner classes, static nested classes don't have implicit access to the outer `this`, so
 * shards receive the graph instance as an explicit constructor parameter. This allows shards to
 * access the graph's other shards for cross-shard dependencies.
 *
 * Example generated structure:
 * ```kotlin
 * class AppGraph$Impl : AppGraph {
 *   // Shard instance fields
 *   protected val shard1: Shard1
 *
 *   init {
 *     // Shards initialized at end (after non-sharded properties)
 *     shard1 = Shard1(this)
 *   }
 *
 *   // Accessors use shard fields
 *   override fun getRepo(): Repository = shard1.repoProvider.get()
 *
 *   protected class Shard1(graph: AppGraph$Impl) {
 *     protected val graph: AppGraph$Impl = graph
 *     // Provider fields owned by this shard, initialized in constructor
 *     protected val repoProvider: Provider<Repository> = provider { Repository.MetroFactory.create() }
 *     protected val apiProvider: Provider<ApiService> = provider { ApiService.MetroFactory.create(repoProvider) }
 *   }
 * }
 * ```
 */
internal class IrGraphShardGenerator(context: IrMetroContext) : IrMetroContext by context {

  /**
   * Generates shard classes for the given bindings.
   *
   * This creates static nested classes that own provider fields. The returned [ShardResult]
   * contains:
   * - [ShardInfo] for each generated shard (class, field in graph, property map)
   * - Initialization statements to create shard instances in the graph's constructor
   *
   * @param graphClass The graph implementation class
   * @param bindingInputs Input bindings with type keys and initializer lambdas
   * @param plannedGroups Pre-planned shard groups from topological analysis (preserving SCCs)
   * @param bindingGraph The binding graph for diagnostics
   * @param bindingPropertyContext The context for registering shard properties before init
   * @param diagnosticTag Tag for diagnostic output files
   * @return Shard generation result, or null if sharding is not needed
   */
  fun generateShards(
    graphClass: IrClass,
    bindingInputs: List<BindingInput>,
    plannedGroups: List<List<IrTypeKey>>?,
    bindingGraph: IrBindingGraph,
    bindingPropertyContext: BindingPropertyContext,
    diagnosticTag: String,
  ): ShardResult? {
    val shardGroups = planShardGroups(bindingInputs, plannedGroups)
    if (shardGroups.size <= 1) {
      // Only warn if user explicitly customized keysPerGraphShard, as this suggests
      // they expected sharding to occur but the graph is too small
      if (options.keysPerGraphShard != DEFAULT_KEYS_PER_GRAPH_SHARD) {
        reportCompat(
          graphClass,
          MetroDiagnostics.METRO_WARNING,
          "Graph sharding is configured with keysPerGraphShard=${options.keysPerGraphShard}, " +
            "but graph '${graphClass.name.asString()}' has only ${bindingInputs.size} bindings " +
            "(threshold is ${options.keysPerGraphShard}), so sharding is not applied.",
        )
      }
      return null
    }

    val shardInfos = generateShardClasses(graphClass, shardGroups, bindingPropertyContext)

    writeDiagnostic("sharding-plan-${diagnosticTag}.txt") {
      ShardingDiagnostics.generateShardingPlanReport(
        graphClass = graphClass,
        shardInfos = shardInfos,
        initOrder = shardGroups.indices.toList(),
        totalBindings = bindingInputs.size,
        options = options,
        bindingGraph = bindingGraph,
      )
    }

    // Create initialization statements that instantiate each shard.
    // Shard constructors initialize all their provider fields directly.
    val initStatements =
      shardInfos.map { info ->
        { thisReceiver: IrValueParameter ->
          createIrBuilder(graphClass.primaryConstructor!!.symbol).run {
            irSetField(
              irGet(thisReceiver),
              info.shardFieldInGraph.backingField!!,
              irCallConstructor(info.shardClass.primaryConstructor!!.symbol, emptyList()).apply {
                arguments[0] = irGet(thisReceiver)
              },
            )
          }
        }
      }

    return ShardResult(shardInfos, initStatements)
  }

  private fun planShardGroups(
    bindingInputs: List<BindingInput>,
    plannedGroups: List<List<IrTypeKey>>?,
  ): List<List<BindingInput>> {
    if (bindingInputs.isEmpty() || plannedGroups.isNullOrEmpty()) {
      return listOf(bindingInputs)
    }

    // Use remove() to both lookup and track which bindings have been assigned to groups.
    // Any bindings remaining in the map after processing all planned groups are collected
    // into a final "overflow" group.
    val bindingsByKey = bindingInputs.associateBy { it.typeKey }.toMutableMap()
    val groups =
      plannedGroups.mapNotNull { group ->
        group.mapNotNull(bindingsByKey::remove).takeIf { it.isNotEmpty() }
      }

    return when {
      groups.isEmpty() -> listOf(bindingInputs)
      bindingsByKey.isEmpty() -> groups
      else -> groups + listOf(bindingsByKey.values.toList())
    }
  }

  /** Intermediate data for shard generation, used between Phase 1 and Phase 2. */
  private data class PendingShardInit(
    val shardClass: IrClass,
    val graphParam: IrValueParameter,
    val graphBackingField: org.jetbrains.kotlin.ir.declarations.IrField,
    val propertyMap: Map<IrTypeKey, IrProperty>,
    val initStatementData: List<Triple<IrProperty, IrTypeKey, PropertyInitializer>>,
    val shardFieldInGraph: IrProperty,
    val bindingInputs: List<BindingInput>,
  )

  private fun generateShardClasses(
    graphClass: IrClass,
    shardGroups: List<List<BindingInput>>,
    bindingPropertyContext: BindingPropertyContext,
  ): List<ShardInfo> {
    // Phase 1: Create all shard classes with properties (but defer init code)
    val pendingInits =
      shardGroups.mapIndexed { index, bindingInputs ->
        createShardClassWithProperties(graphClass, index, bindingInputs)
      }

    // Register all shard properties in bindingPropertyContext BEFORE generating init code.
    // This is critical because init code generation uses expression generators that query
    // bindingPropertyContext to find provider properties.
    for (pending in pendingInits) {
      for ((typeKey, property) in pending.propertyMap) {
        bindingPropertyContext.putProviderProperty(
          typeKey,
          property,
          PropertyLocation.InShard(pending.shardFieldInGraph, pending.shardClass),
        )
      }
    }

    // Phase 2: Generate init code for each shard
    return pendingInits.map { pending -> generateShardInitCode(pending) }
  }

  /** Phase 1: Create shard class structure with properties but without init code. */
  private fun createShardClassWithProperties(
    graphClass: IrClass,
    index: Int,
    bindingInputs: List<BindingInput>,
  ): PendingShardInit {
    val shardName = "Shard${index + 1}"
    val shardFieldName = "shard${index + 1}"

    // Create the shard class as a static nested class (not inner)
    // Protected visibility allows extension graphs (inner classes) to access shards
    val shardClass =
      irFactory
        .buildClass {
          name = Name.identifier(shardName)
          visibility = DescriptorVisibilities.PROTECTED
          modality = Modality.FINAL
          isInner = false // Static nested class
        }
        .apply {
          superTypes = listOf(irBuiltIns.anyType)
          createThisReceiverParameter()
          parent = graphClass
          graphClass.addChild(this)
        }

    // Add constructor with explicit graph parameter
    val graphParam: IrValueParameter
    shardClass
      .addConstructor {
        isPrimary = true
        origin = Origins.Default
      }
      .apply {
        graphParam = addValueParameter {
          name = Name.identifier("graph")
          type = graphClass.defaultType
        }
      }

    // Create a backing field to hold the graph reference (no getter needed for private field)
    val graphBackingField =
      irFactory
        .buildField {
          name = Name.identifier("graph")
          type = graphClass.defaultType
          visibility = DescriptorVisibilities.PRIVATE
          isFinal = true
          origin = IrDeclarationOrigin.PROPERTY_BACKING_FIELD
        }
        .apply { parent = shardClass }
    shardClass.declarations.add(graphBackingField)

    // Create provider properties in the shard class
    val propertyMap = mutableMapOf<IrTypeKey, IrProperty>()
    val initStatementData = mutableListOf<Triple<IrProperty, IrTypeKey, PropertyInitializer>>()

    for (input in bindingInputs) {
      // Create property in shard class with backing field only (no getter needed)
      val property =
        shardClass.addProperty {
          name = Name.identifier(input.propertyName)
          // Private visibility - accessed via backing field directly
          visibility = DescriptorVisibilities.PRIVATE
          origin = Origins.Default
        }

      val backingField =
        irFactory
          .buildField {
            name = Name.identifier(input.propertyName)
            type = input.propertyType
            // Private - graph accesses these via the shard field
            visibility = DescriptorVisibilities.PRIVATE
            isFinal = false // Will be set in constructor
            origin = IrDeclarationOrigin.PROPERTY_BACKING_FIELD
          }
          .apply {
            parent = shardClass
            correspondingPropertySymbol = property.symbol
          }
      property.backingField = backingField

      propertyMap[input.typeKey] = property
      initStatementData.add(Triple(property, input.typeKey, input.initializer))
    }

    // Create the shard field in the graph class (backing field only, no getter needed)
    val shardFieldInGraph =
      graphClass.addProperty {
        name = Name.identifier(shardFieldName)
        visibility = DescriptorVisibilities.PROTECTED
        origin = Origins.Default
      }

    val shardBackingField =
      irFactory
        .buildField {
          name = Name.identifier(shardFieldName)
          type = shardClass.defaultType
          visibility = DescriptorVisibilities.PROTECTED
          isFinal = true
          origin = IrDeclarationOrigin.PROPERTY_BACKING_FIELD
        }
        .apply {
          parent = graphClass
          correspondingPropertySymbol = shardFieldInGraph.symbol
        }
    shardFieldInGraph.backingField = shardBackingField

    return PendingShardInit(
      shardClass = shardClass,
      graphParam = graphParam,
      graphBackingField = graphBackingField,
      propertyMap = propertyMap,
      initStatementData = initStatementData,
      shardFieldInGraph = shardFieldInGraph,
      bindingInputs = bindingInputs,
    )
  }

  /**
   * Phase 2: Generate init code for a shard.
   *
   * All provider fields are initialized in the shard's constructor. The expression generator uses
   * ShardContext to generate correct property access expressions from the start:
   * - Same-shard access: `this.property`
   * - Cross-shard access: `this.graph.otherShard.property`
   * - Graph-direct access: `this.graph.property`
   */
  private fun generateShardInitCode(pending: PendingShardInit): ShardInfo {
    val shardClass = pending.shardClass
    val graphParam = pending.graphParam
    val graphBackingField = pending.graphBackingField
    val propertyMap = pending.propertyMap
    val initStatementData = pending.initStatementData

    val ctor = shardClass.primaryConstructor!!
    val shardThisReceiver = shardClass.thisReceiverOrFail

    // Build the constructor body with all initialization.
    // If chunking is needed, create private init functions called from the constructor.
    val mustChunk = options.chunkFieldInits && initStatementData.size > options.statementsPerInitFun

    if (mustChunk) {
      val functionNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
      val chunks = initStatementData.chunked(options.statementsPerInitFun)

      val chunkedFunctions =
        chunks.map { chunk ->
          val initName = functionNameAllocator.newName("init")
          shardClass
            .addFunction(initName, irBuiltIns.unitType, visibility = DescriptorVisibilities.PRIVATE)
            .apply {
              val localShardReceiver = shardClass.thisReceiverOrFail.copyTo(this)
              setDispatchReceiver(localShardReceiver)

              // Create shard context with the function's receiver
              val shardContext =
                ShardContext(
                  currentClass = shardClass,
                  thisReceiver = localShardReceiver,
                  graphBackingField = graphBackingField,
                )

              buildBlockBody {
                for ((property, typeKey, initializer) in chunk) {
                  val initValue = initializer.invoke(this@buildBlockBody, typeKey, shardContext)
                  +irSetField(irGet(localShardReceiver), property.backingField!!, initValue)
                }
              }
            }
        }

      // Constructor calls super(), sets graph field, then calls all init chunk functions
      ctor.body =
        createIrBuilder(ctor.symbol).irBlockBody {
          +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.primaryConstructor!!)
          +irSetField(irGet(shardThisReceiver), graphBackingField, irGet(graphParam))
          for (chunkedFn in chunkedFunctions) {
            +irInvoke(dispatchReceiver = irGet(shardThisReceiver), callee = chunkedFn.symbol)
          }
        }
    } else {
      // Create shard context with the constructor's receiver
      val shardContext =
        ShardContext(
          currentClass = shardClass,
          thisReceiver = shardThisReceiver,
          graphBackingField = graphBackingField,
        )

      // Constructor initializes all provider fields directly
      ctor.body =
        createIrBuilder(ctor.symbol).irBlockBody {
          +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.primaryConstructor!!)
          +irSetField(irGet(shardThisReceiver), graphBackingField, irGet(graphParam))
          for ((property, typeKey, initializer) in initStatementData) {
            val initValue = initializer.invoke(this@irBlockBody, typeKey, shardContext)
            +irSetField(irGet(shardThisReceiver), property.backingField!!, initValue)
          }
        }
    }

    return ShardInfo(
      index = pending.shardFieldInGraph.name.asString().removePrefix("shard").toInt() - 1,
      shardClass = shardClass,
      shardFieldInGraph = pending.shardFieldInGraph,
      propertyMap = propertyMap,
      bindings =
        pending.bindingInputs.map { input ->
          PropertyBinding(property = propertyMap.getValue(input.typeKey), typeKey = input.typeKey)
        },
    )
  }
}

/**
 * Input for shard generation. Unlike [PropertyBinding], this doesn't have the property yet since
 * the shard generator will create properties in the shard class.
 */
internal data class BindingInput(
  val typeKey: IrTypeKey,
  val propertyName: String,
  val propertyType: org.jetbrains.kotlin.ir.types.IrType,
  val initializer: PropertyInitializer,
)

/** Property with its type key (property is now in shard class). */
internal data class PropertyBinding(val property: IrProperty, val typeKey: IrTypeKey)

/** Result of shard generation. */
internal data class ShardResult(
  /** Info for each generated shard. */
  val shardInfos: List<ShardInfo>,
  /**
   * Initialization statements to add to graph constructor. These create shard instances and should
   * be executed after non-sharded property initialization.
   */
  val initStatements: List<(IrValueParameter) -> IrExpression>,
)

/** Generated shard class info. */
internal data class ShardInfo(
  val index: Int,
  val shardClass: IrClass,
  /** The property in the graph class that holds this shard instance. */
  val shardFieldInGraph: IrProperty,
  /** Map from type key to provider property in this shard. */
  val propertyMap: Map<IrTypeKey, IrProperty>,
  /** Bindings in this shard (for diagnostics). */
  val bindings: List<PropertyBinding>,
)
