// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.DEFAULT_KEYS_PER_GRAPH_SHARD
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.GraphPropertyData
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import dev.zacsweers.metro.compiler.ir.graph.ensureInitialized
import dev.zacsweers.metro.compiler.ir.graph.graphPropertyData
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.newName
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.Name

/**
 * Generates IR for shard classes when graph sharding is enabled.
 *
 * When a dependency graph has many bindings, the generated class can exceed JVM class size limits.
 * Sharding distributes provider properties across static nested shard classes. Each shard owns a
 * subset of providers, the main graph class keeps field instances of each shard.
 *
 * There are two modes of operation:
 * 1. **Single shard (graph-as-shard)**: When the graph is small enough, properties are placed
 *    directly on the graph class. The graph class acts as its own "shard" with no nested classes.
 * 2. **Multiple shards**: When the graph exceeds the shard threshold, properties are distributed
 *    across static nested shard classes. Each shard receives a reference to the main graph for
 *    accessing bound instances and cross-shard dependencies.
 *
 * Example generated structure (multiple shards):
 * ```kotlin
 * class AppGraph$Impl : AppGraph {
 *   private val shard1 = Shard1(this)
 *   private val shard2 = Shard2(this)
 *
 *   private class Shard1(private val graph: AppGraph$Impl) {
 *     val repoProvider: Provider<Repository> = Repository_Factory.create()
 *     val apiProvider: Provider<ApiService> = ApiService_Factory.create(repoProvider)
 *   }
 *
 *   private class Shard2(private val graph: AppGraph$Impl) {
 *     val serviceProvider: Provider<Service> = Service_Factory.create(graph.shard1.repoProvider)
 *   }
 * }
 * ```
 *
 * @param graphClass The main graph implementation class
 * @param shardBindings Bindings to be distributed across shards
 * @param plannedGroups Pre-computed shard groups from topological sort (respects SCC boundaries)
 * @param bindingGraph The binding graph for dependency lookups
 * @param propertyNameAllocator Name allocator for properties on the graph class
 */
internal class IrGraphShardGenerator(
  context: IrMetroContext,
  private val graphClass: IrClass,
  private val bindingGraph: IrBindingGraph,
  private val shardBindings: List<ShardBinding>,
  private val plannedGroups: List<List<IrTypeKey>>?,
  private val propertyNameAllocator: NameAllocator,
  private val classNameAllocator: NameAllocator,
) : IrMetroContext by context {

  /**
   * Generates shard classes (or graph-as-shard) and returns the result for the graph generator.
   *
   * @param diagnosticTag Tag for diagnostic output files
   * @return ShardResult with either multiple shards or graph-as-shard, null if no bindings
   */
  context(context: IrMetroContext)
  fun generateShards(diagnosticTag: String): ShardResult? {
    if (shardBindings.isEmpty()) {
      return null
    }

    // If sharding is disabled, use graph-as-shard directly without computing shard groups
    if (!context.options.enableGraphSharding) {
      val shardLookup = ShardLookup()
      return ShardResult(
        shards = listOf(generateGraphAsShard(shardLookup)),
        shardLookup = shardLookup,
        isGraphAsShard = true,
      )
    }

    val shardGroups = planShardGroups()

    // Determine if we need actual shard classes or just use the graph class
    val useNestedShards = shardGroups.size > 1

    // Only warn if user explicitly customized keysPerGraphShard, as this suggests
    // they expected sharding to occur but the graph is too small
    if (!useNestedShards && context.options.keysPerGraphShard != DEFAULT_KEYS_PER_GRAPH_SHARD) {
      context.reportCompat(
        graphClass,
        MetroDiagnostics.METRO_WARNING,
        "Graph sharding is configured with keysPerGraphShard=${context.options.keysPerGraphShard}, " +
          "but graph '${graphClass.name.asString()}' has only ${shardBindings.size} bindings " +
          "(threshold is ${context.options.keysPerGraphShard}), so sharding is not applied.",
      )
    }

    // Track which shard owns each binding for cross-shard dependency analysis
    val shardLookup = ShardLookup()

    val shards =
      if (useNestedShards) {
        // Create nested shard classes
        val shards = generateNestedShardClasses(shardGroups, shardLookup)
        computeShardDependencies(shards, shardLookup)
        generateShardConstructors(shards, shardLookup)
        shards
      } else {
        // Use graph class as single shard
        listOf(generateGraphAsShard(shardLookup))
      }

    if (useNestedShards) {
      writeDiagnostic("sharding-plan-${diagnosticTag}.txt") {
        ShardingDiagnostics.generateShardingPlanReport(
          graphClass = graphClass,
          shards = shards,
          initOrder = shardGroups.indices.toList(),
          totalBindings = shardBindings.size,
          options = context.options,
          bindingGraph = bindingGraph,
        )
      }
    }

    return ShardResult(
      shards = shards,
      shardLookup = shardLookup,
      isGraphAsShard = !useNestedShards,
    )
  }

  private fun planShardGroups(): List<List<ShardBinding>> {
    if (shardBindings.isEmpty() || plannedGroups.isNullOrEmpty()) {
      return listOf(shardBindings)
    }

    // Use remove() to both lookup and track which bindings have been assigned to groups
    val bindingsByKey = shardBindings.associateByTo(mutableMapOf()) { it.typeKey }
    val filteredGroups =
      plannedGroups.mapNotNull { group ->
        group.mapNotNull(bindingsByKey::remove).takeIf { it.isNotEmpty() }
      }

    // Add any remaining bindings not in planned groups
    val allGroups =
      if (bindingsByKey.isEmpty()) {
        filteredGroups
      } else {
        filteredGroups + listOf(bindingsByKey.values.toList())
      }

    if (allGroups.isEmpty()) {
      return listOf(shardBindings)
    }

    // Rebalance: the original partitioning was based on all bindings, but after filtering
    // to only ShardBindings, groups may be very uneven (e.g., 901/1/1 from 2000/2000/1529).
    // Merge small groups together while preserving topological order and SCC constraints.
    // Since original groups already respect SCC boundaries, merging preserves that constraint.
    return rebalanceGroups(allGroups)
  }

  /**
   * Rebalances groups by merging small adjacent groups together.
   *
   * This preserves:
   * - Topological order (groups are processed in order, bindings concatenated)
   * - SCC constraints (original groups already keep SCCs together, we only merge, never split)
   */
  private fun rebalanceGroups(
    groups: List<List<ShardBinding>>,
    targetSize: Int = metroContext.options.keysPerGraphShard,
  ): List<List<ShardBinding>> {
    if (groups.size <= 1) return groups

    // Pre-size to worst-case (no merging) to avoid resizing
    val rebalanced = ArrayList<List<ShardBinding>>(groups.size)
    var currentBatch: ArrayList<ShardBinding>? = null
    var pending: List<ShardBinding>? = null

    for (group in groups) {
      val p = pending
      if (p == null) {
        pending = group
        continue
      }

      if (p.size + group.size <= targetSize) {
        // Merge needed.
        // If we don't have a mutable batch yet, create one and copy the pending group into it.
        var batch = currentBatch
        if (batch == null) {
          batch = ArrayList(targetSize)
          batch.addAll(p)
          currentBatch = batch
          pending = batch
        }
        batch.addAll(group)
      } else {
        // Cannot merge, flush the pending group
        rebalanced.add(p)
        pending = group
        currentBatch = null
      }
    }

    if (pending != null) {
      rebalanced.add(pending)
    }

    return rebalanced
  }

  /**
   * Creates a [Shard] representing the graph class itself as a single shard. Properties are created
   * directly on the graph class.
   */
  context(context: IrMetroContext)
  private fun generateGraphAsShard(shardLookup: ShardLookup): Shard {
    val properties = mutableMapOf<IrContextualTypeKey, ShardProperty>()

    for (shardBinding in shardBindings) {
      shardLookup.assignToShard(shardBinding.typeKey, 0)

      val property =
        graphClass
          .addProperty {
            name = propertyNameAllocator.newName(shardBinding.nameHint)
            visibility = DescriptorVisibilities.PRIVATE
          }
          .apply {
            graphPropertyData = GraphPropertyData(shardBinding.contextKey, shardBinding.irType)
            shardBinding.contextKey.typeKey.qualifier?.ir?.let {
              annotations += it.deepCopyWithSymbols()
            }
            ensureInitialized(shardBinding.propertyKind, shardBinding.irType)
          }

      properties[shardBinding.contextKey] =
        ShardProperty(
          property = property,
          contextKey = shardBinding.contextKey,
          shardBinding = shardBinding,
        )
    }

    return Shard(
      index = 0,
      shardClass = graphClass,
      bindings = shardBindings,
      properties = properties,
      graphParam = null, // Not needed for graph-as-shard
      graphProperty = null, // Not needed for graph-as-shard
      isGraphAsShard = true,
      nameAllocator = propertyNameAllocator, // Use the graph's allocator for graph-as-shard
      classNameAllocator = classNameAllocator, // Use the graph's allocator for graph-as-shard
    )
  }

  context(context: IrMetroContext)
  private fun generateNestedShardClasses(
    shardGroups: List<List<ShardBinding>>,
    shardLookup: ShardLookup,
  ): List<Shard> {
    // Use a single shared allocator for all binding properties across all shards.
    // Pre-allocate "graph" so binding properties can't collide with the graph field in shard
    // classes.
    val sharedNameAllocator =
      NameAllocator(mode = NameAllocator.Mode.COUNT).apply {
        reserveName(Symbols.StringNames.GRAPH)
      }

    return shardGroups.mapIndexed { index, bindings ->
      val shardName = classNameAllocator.newName("Shard${index + 1}").asName()
      val shardClass =
        context.irFactory
          .buildClass {
            name = shardName
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
          }
          .apply {
            superTypes = listOf(context.irBuiltIns.anyType)
            createThisReceiverParameter()
            parent = graphClass
            graphClass.addChild(this)
          }

      // Create properties inside the shard class
      val properties = mutableMapOf<IrContextualTypeKey, ShardProperty>()

      for (shardBinding in bindings) {
        shardLookup.assignToShard(shardBinding.typeKey, index)

        val property =
          shardClass
            .addProperty {
              // Use internal visibility so other shards can access these fields
              name = sharedNameAllocator.newName(shardBinding.nameHint)
              visibility = DescriptorVisibilities.INTERNAL
            }
            .apply {
              graphPropertyData = GraphPropertyData(shardBinding.contextKey, shardBinding.irType)
              shardBinding.contextKey.typeKey.qualifier?.ir?.let {
                annotations += it.deepCopyWithSymbols()
              }
              ensureInitialized(shardBinding.propertyKind, shardBinding.irType)
            }

        properties[shardBinding.contextKey] =
          ShardProperty(
            property = property,
            contextKey = shardBinding.contextKey,
            shardBinding = shardBinding,
          )
      }

      Shard(
        index = index,
        shardClass = shardClass,
        bindings = bindings,
        properties = properties,
        // Will be set in generateShardConstructors
        graphParam = null,
        graphProperty = null,
        isGraphAsShard = false,
        nameAllocator = sharedNameAllocator,
        // Each shard gets its own class name allocator for nested classes (e.g., SwitchingProvider)
        classNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT),
      )
    }
  }

  private fun computeShardDependencies(shards: List<Shard>, shardLookup: ShardLookup) {
    for (shard in shards) {
      val currentShardIndex = shard.index

      for (contextKey in shard.properties.keys) {
        val binding = bindingGraph.findBinding(contextKey.typeKey) ?: continue
        val dependencies = binding.dependencies

        for (dep in dependencies) {
          val depShardIndex = shardLookup.getShardIndex(dep.typeKey)
          when {
            depShardIndex == -1 -> {
              // Dependency is a bound instance on main graph (not in any shard)
              // Shard needs graph access to reach it
              shardLookup.markNeedsGraphAccess(currentShardIndex)
            }
            depShardIndex != currentShardIndex -> {
              // Cross-shard dependency - needs graph access to reach other shard
              shardLookup.addShardDependency(currentShardIndex, depShardIndex)
              shardLookup.markNeedsGraphAccess(currentShardIndex)
            }
          }
        }
      }
    }
  }

  context(compatContext: CompatContext)
  private fun generateShardConstructors(shards: List<Shard>, shardLookup: ShardLookup) =
    with(compatContext) {
      for (shard in shards) {
        val shardClass = shard.shardClass
        val needsGraphAccess = shardLookup.needsGraphAccess(shard.index)

        val constructor =
          shardClass.addConstructor {
            isPrimary = true
            origin = Origins.Default
          }

        if (needsGraphAccess) {
          // Add graph property field to store the graph reference (needed for cross-shard access)
          // Use INTERNAL visibility so other shards can access it
          val graphProperty =
            shardClass
              .addProperty {
                name = Name.identifier("graph")
                visibility = DescriptorVisibilities.INTERNAL
              }
              .apply {
                addBackingFieldCompat {
                  type = graphClass.defaultType
                  visibility = DescriptorVisibilities.INTERNAL
                }
              }
          shard.graphProperty = graphProperty

          // Add graph parameter (for accessing bound instances and cross-shard dependencies)
          val graphParam = constructor.addValueParameter(graphProperty.name, graphClass.defaultType)
          shard.graphParam = graphParam
        }

        // Constructor body will be generated by IrGraphGenerator after property expressions are
        // ready
      }
    }
}
