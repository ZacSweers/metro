// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import kotlin.random.Random

/** Fixture-only binding shapes; scopes and aggregation labels are not shared graph semantics. */
internal enum class WorkloadBindingKind {
  PROVIDED,
  CONSTRUCTOR_INJECTED,
  ALIAS,
  SET_MULTIBINDING,
  MAP_MULTIBINDING,
  INSTANCE,
}

/**
 * Describes a reproducible, session-free graph workload.
 *
 * About one eighth of the nodes form an unreachable subtree, one fifth have qualifiers, and the
 * remaining nodes cover six binding shapes across nested application/session/feature scope labels.
 * Scopes are descriptive fixture metadata: the shared graph does not perform scope resolution.
 */
internal data class GraphWorkloadSpec(
  val size: Int,
  val seed: Int = 8_675_309,
  val rootCount: Int = (size / 32).coerceIn(4, 128),
  val unreachableCount: Int = size / 8,
) {
  init {
    require(size >= 32) { "A representative graph needs at least 32 bindings." }
    require(unreachableCount in 2 until size - 16) {
      "Keep at least sixteen reachable bindings and two unreachable bindings."
    }
    require(rootCount in 1..(size - unreachableCount)) {
      "Root count must fit within the reachable bindings."
    }
  }
}

/** One stable fixture node and descriptive metadata used by benchmarks and shape assertions. */
internal data class GraphWorkloadNode(
  val index: Int,
  val binding: StringBinding,
  val kind: WorkloadBindingKind,
  val scope: String,
  val lazy: Boolean,
)

/**
 * Immutable representative graph shared by unit tests and standalone graph benchmarks.
 *
 * The reachable partition contains a full-length descending dependency chain, a shared fan-in hub,
 * repeated diamonds, 16-to-64-input set/map aggregate nodes, lazily discovered constructor nodes,
 * and disjoint cycles whose only upward edges alternate between provider and lazy wrappers. A
 * separate unreachable partition lets seals exercise both pruning modes against the same inputs.
 */
internal class GraphWorkload
private constructor(
  val spec: GraphWorkloadSpec,
  val nodes: List<GraphWorkloadNode>,
  val eagerBindings: List<StringBinding>,
  val lazyBindingsByKey: Map<StringTypeKey, StringBinding>,
  val roots: Map<StringContextualTypeKey, StringBindingStack.Entry>,
  val reachableKeys: Set<StringTypeKey>,
  val unreachableKeys: Set<StringTypeKey>,
  val expectedDeferredKeys: Set<StringTypeKey>,
) {
  // Reuse singleton lookup results so benchmarks measure graph work, not callback allocations.
  private val lazyBindingResults: Map<StringTypeKey, Set<StringBinding>> =
    lazyBindingsByKey.mapValues { (_, binding) ->
      setOf(binding)
    }

  /**
   * Creates a fresh graph, seeds its eager bindings, and discovers constructor bindings on demand.
   */
  fun newGraph(onLazyLookup: (StringTypeKey) -> Unit = {}): StringGraph {
    val graph =
      StringGraph(
        newBindingStack = { StringBindingStack("RepresentativeGraph") },
        newBindingStackEntry = { key, _, _ -> StringBindingStack.Entry(key) },
        computeBinding = { contextKey, _, _ ->
          onLazyLookup(contextKey.typeKey)
          lazyBindingResults[contextKey.typeKey].orEmpty()
        },
      )
    val stack = StringBindingStack("RepresentativeGraph")
    for (binding in eagerBindings) graph.tryPut(binding, stack)
    return graph
  }

  companion object {
    fun generate(spec: GraphWorkloadSpec): GraphWorkload {
      val random = Random(spec.seed)
      val firstReachable = spec.unreachableCount
      val kinds = Array(spec.size) { WorkloadBindingKind.PROVIDED }
      val scopes = Array(spec.size) { scopeFor(it, random) }
      val keys =
        List(spec.size) { index ->
          val qualifier = if (index % 5 == 0) "@Named(name${random.nextInt(12)})" else null
          StringTypeKey("Node$index", qualifier)
        }
      val dependencies = Array(spec.size) { linkedMapOf<StringTypeKey, StringContextualTypeKey>() }

      // The disconnected prefix is preloaded but never reachable from an accessor.
      for (index in 1 until firstReachable) addDependency(dependencies, keys, index, index - 1)

      // Every eager edge descends. The final root therefore reaches the entire long backbone.
      for (index in firstReachable + 1 until spec.size) {
        addDependency(dependencies, keys, index, index - 1)
      }

      val sharedHub = firstReachable
      val aggregateWidth = ((spec.size - firstReachable) / 16).coerceIn(16, 64)
      for (index in firstReachable until spec.size) {
        val reachableOffset = index - firstReachable
        kinds[index] =
          when {
            reachableOffset > aggregateWidth && reachableOffset % 53 == 0 ->
              WorkloadBindingKind.SET_MULTIBINDING
            reachableOffset > aggregateWidth && reachableOffset % 67 == 0 ->
              WorkloadBindingKind.MAP_MULTIBINDING
            reachableOffset % 17 == 0 -> WorkloadBindingKind.INSTANCE
            reachableOffset % 11 == 0 -> WorkloadBindingKind.ALIAS
            reachableOffset % 7 == 0 -> WorkloadBindingKind.CONSTRUCTOR_INJECTED
            else -> WorkloadBindingKind.PROVIDED
          }

        // A shared lower-index singleton gives the graph realistic, very high incoming degree.
        if (reachableOffset > 1 && reachableOffset % 3 == 0) {
          addDependency(dependencies, keys, index, sharedHub)
        }

        // Nearby skip edges form repeated diamonds while preserving eager acyclicity.
        if (reachableOffset > 3 && reachableOffset % 9 == 0) {
          addDependency(dependencies, keys, index, index - 2)
          addDependency(dependencies, keys, index - 1, index - 3)
        }

        val kind = kinds[index]
        if (
          kind == WorkloadBindingKind.SET_MULTIBINDING ||
            kind == WorkloadBindingKind.MAP_MULTIBINDING
        ) {
          val count = minOf(aggregateWidth, reachableOffset)
          for (offset in 1..count) addDependency(dependencies, keys, index, index - offset)
        }
      }

      val expectedDeferred = linkedSetOf<StringTypeKey>()
      var cycleStart = firstReachable + 5
      var cycleNumber = 0
      while (cycleStart + 2 < spec.size) {
        val cycleEnd = cycleStart + 2
        // Instance bindings are implicitly deferrable. Keep generated SCCs focused on their
        // explicit provider/lazy back edge instead of introducing an unrelated soft target.
        val hasImplicitlyDeferredNode =
          (cycleStart..cycleEnd).any { kinds[it] == WorkloadBindingKind.INSTANCE }
        if (hasImplicitlyDeferredNode) {
          cycleStart += 41
          continue
        }
        val target = keys[cycleEnd]
        val wrapper = if (cycleNumber % 2 == 0) "() -> ${target.type}" else "Lazy<${target.type}>"
        val wrappedKey = StringTypeKey(wrapper, target.qualifier)
        dependencies[cycleStart][target] = StringContextualTypeKey.create(wrappedKey)
        // MetroSort records the source whose outgoing provider/lazy edge must be deferred.
        expectedDeferred += keys[cycleStart]
        cycleStart += 41
        cycleNumber++
      }

      val nodes =
        List(spec.size) { index ->
          val kind = kinds[index]
          val key = StringContextualTypeKey.create(keys[index])
          val binding = StringBinding(key, dependencies[index].values.toList(), kind, scopes[index])
          val lazilyDiscovered =
            index >= firstReachable && kind == WorkloadBindingKind.CONSTRUCTOR_INJECTED
          GraphWorkloadNode(index, binding, kind, scopes[index], lazilyDiscovered)
        }
      val eagerBindings = nodes.filterNot { it.lazy }.map { it.binding }
      val lazyBindings = nodes.filter { it.lazy }.associate { it.binding.typeKey to it.binding }
      val roots = linkedMapOf<StringContextualTypeKey, StringBindingStack.Entry>()
      val reachableSize = spec.size - firstReachable
      for (rootIndex in 0 until spec.rootCount) {
        val offset = reachableSize - 1 - rootIndex * reachableSize / spec.rootCount
        val key = nodes[firstReachable + offset].binding.contextualTypeKey
        roots[key] = StringBindingStack.Entry(key, usage = "accessor$rootIndex")
      }

      return GraphWorkload(
        spec = spec,
        nodes = nodes,
        eagerBindings = eagerBindings,
        lazyBindingsByKey = lazyBindings,
        roots = roots,
        reachableKeys = nodes.drop(firstReachable).mapTo(linkedSetOf()) { it.binding.typeKey },
        unreachableKeys = nodes.take(firstReachable).mapTo(linkedSetOf()) { it.binding.typeKey },
        expectedDeferredKeys = expectedDeferred,
      )
    }

    private fun addDependency(
      dependencies: Array<LinkedHashMap<StringTypeKey, StringContextualTypeKey>>,
      keys: List<StringTypeKey>,
      source: Int,
      target: Int,
    ) {
      val targetKey = keys[target]
      dependencies[source][targetKey] = StringContextualTypeKey.create(targetKey)
    }

    private fun scopeFor(index: Int, random: Random): String {
      return when (index % 8) {
        0 -> "App"
        1,
        2 -> "App/Session${random.nextInt(3)}"
        else -> "App/Session${random.nextInt(3)}/Feature${random.nextInt(4)}"
      }
    }
  }
}
