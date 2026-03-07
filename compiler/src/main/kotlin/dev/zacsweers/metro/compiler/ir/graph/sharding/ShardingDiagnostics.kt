// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import androidx.collection.MutableObjectIntMap
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.graph.IrBindingGraph
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.kotlinFqName

/**
 * Generates diagnostic reports about graph sharding decisions.
 *
 * When a dependency graph has too many bindings to fit in a single class (exceeding the JVM method
 * size limit), Metro partitions bindings across multiple "shard" classes. This utility generates
 * reports showing how bindings are distributed, shard initialization order, and cross-shard
 * dependencies.
 *
 * The report includes:
 * - Graph name and configuration (total bindings, keys per shard, shard count)
 * - Shard initialization order
 * - Per-shard details (class name, binding count, cross-shard edges, binding keys)
 * - Cross-shard dependency list and total count
 *
 * Cross-shard edges represent dependencies from bindings in one shard to bindings in another. High
 * edge counts may indicate heavy coupling between shards.
 *
 * Reports are written to `sharding-plan-{GraphName}.txt` in [MetroOptions.reportsDir].
 */
internal object ShardingDiagnostics {
  private const val MAX_CROSS_SHARD_DEPS = 100

  fun generateShardingPlanReport(
    graphClass: IrClass,
    shards: List<Shard>,
    initOrder: List<Int>,
    totalBindings: Int,
    options: MetroOptions,
    bindingGraph: IrBindingGraph,
  ): String = buildString {
    appendLine("=== Metro Graph Sharding Plan ===")
    appendLine()
    appendLine("Graph: ${graphClass.kotlinFqName}")
    appendLine("Total bindings: $totalBindings")
    appendLine("Keys per shard limit: ${options.keysPerGraphShard}")
    appendLine("Shard count: ${shards.size}")
    appendLine("Sharding enabled: ${options.enableGraphSharding}")
    appendLine()

    appendLine("Initialization order: ${initOrder.joinToString(" → ") { "Shard${it + 1}" }}")
    appendLine()

    // First compute cross-shard dependencies to get per-shard counts
    val bindingToShard = MutableObjectIntMap<IrTypeKey>()
    for (shard in shards) {
      for (binding in shard.bindings) {
        bindingToShard[binding.typeKey] = shard.index
      }
    }

    // Track outgoing cross-shard edges per shard to identify hotspots
    val crossShardEdgeCounts = IntArray(shards.size)
    for (shard in shards) {
      for (binding in shard.bindings.filterOutAssistedInjectConstructors()) {
        val deps = bindingGraph.requireBinding(binding.typeKey).dependencies
        for (dep in deps) {
          val depShard = bindingToShard.getOrDefault(dep.typeKey, -1)
          if (depShard != -1 && depShard != shard.index) {
            crossShardEdgeCounts[shard.index]++
          }
        }
      }
    }

    for (shard in shards) {
      appendLine("Shard ${shard.index + 1}:")
      appendLine("  Class: ${shard.shardClass.name}")
      val bindingCount = shard.bindings.size
      val limit = options.keysPerGraphShard
      if (bindingCount > limit) {
        appendLine("  Bindings: $bindingCount (exceeds limit of $limit due to large SCC)")
      } else {
        appendLine("  Bindings: $bindingCount")
      }
      appendLine("  Outgoing cross-shard edges: ${crossShardEdgeCounts[shard.index]}")

      if (shard.bindings.size <= 10) {
        // Show all bindings for small shards
        appendLine("  Binding keys:")
        shard.bindings.forEach { binding -> appendLine("    - ${binding.typeKey}") }
      } else {
        // Show first and last for large shards
        appendLine("  Binding keys (first 5):")
        shard.bindings.take(5).forEach { binding -> appendLine("    - ${binding.typeKey}") }
        appendLine("    ... (${shard.bindings.size - 10} more)")
        appendLine("  Binding keys (last 5):")
        shard.bindings.takeLast(5).forEach { binding -> appendLine("    - ${binding.typeKey}") }
      }
      appendLine()
    }

    // Compute and display detailed cross-shard dependencies (truncated for large graphs)
    appendLine("Cross-shard dependencies:")
    var crossShardDepCount = 0
    var reportedCount = 0
    for (shard in shards) {
      for (binding in shard.bindings.filterOutAssistedInjectConstructors()) {
        val deps = bindingGraph.requireBinding(binding.typeKey).dependencies
        deps.forEach { dep ->
          val depShard = bindingToShard.getOrDefault(dep.typeKey, -1)
          if (depShard != -1 && depShard != shard.index) {
            if (reportedCount < MAX_CROSS_SHARD_DEPS) {
              appendLine(
                "  Shard${shard.index + 1}.${binding.typeKey} → Shard${depShard + 1}.${dep.typeKey}"
              )
              reportedCount++
            }
            crossShardDepCount++
          }
        }
      }
    }

    if (crossShardDepCount == 0) {
      appendLine("  (none)")
    } else if (crossShardDepCount > MAX_CROSS_SHARD_DEPS) {
      appendLine("  ... (${crossShardDepCount - MAX_CROSS_SHARD_DEPS} more, truncated)")
    }
    appendLine()
    appendLine("Total cross-shard dependencies: $crossShardDepCount")
  }

  // We track @AssistedInject constructors from @AssistedFactory targets, but they are not
  // actually on the graph
  private fun List<ShardBinding>.filterOutAssistedInjectConstructors(): List<ShardBinding> {
    return filter { binding ->
      (binding.binding as? IrBinding.ConstructorInjected)?.isAssisted != true
    }
  }
}
