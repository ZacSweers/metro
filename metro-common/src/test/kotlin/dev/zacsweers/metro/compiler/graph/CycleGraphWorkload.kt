// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

/** Cycle families that exercise singleton selection, pruning, and hard-cycle diagnostics. */
internal enum class CycleGraphScenario {
  /** One deferred closing edge gives a single candidate. */
  PROVIDER_RING,

  /** Every vertex has a deferred chord. The eager ring remains a hard cycle. */
  EAGER_RING_WITH_DEFERRED_CHORDS,

  /** The last candidate can break both provider rings. */
  SHARED_HUB,

  /** Every vertex depends on every other vertex through a provider. */
  COMPLETE_DEFERRED,

  /** A small eager cycle shares its component with a long sparse tail. */
  SPARSE_HARD_TAIL,

  /** Each tail vertex depends on the eager cycle and every earlier tail vertex. */
  DENSE_HARD_TAIL,
}

/**
 * A deterministic cycle workload shared by unit tests and JMH.
 *
 * [size] always counts every vertex. Shared-hub rings divide the other vertices evenly around one
 * hub. Their lengths differ by at most one. Hard-tail graphs reserve two vertices for an eager
 * cycle. All bindings are ordinary bindings. Candidate priority follows their padded names.
 * [expectedDeferredKeys] is null when the graph contains a hard cycle.
 */
internal class CycleGraphWorkload
private constructor(
  val scenario: CycleGraphScenario,
  val size: Int,
  val bindings: List<StringBinding>,
  val roots: Map<StringContextualTypeKey, StringBindingStack.Entry>,
  val hasHardCycle: Boolean,
  val expectedDeferredKeys: Set<StringTypeKey>?,
) {
  /** Seeds a fresh mutable graph with this workload's immutable bindings. */
  fun newGraph(): StringGraph {
    val graph =
      StringGraph(
        newBindingStack = { StringBindingStack("CycleGraph") },
        newBindingStackEntry = { key, _, _ -> StringBindingStack.Entry(key) },
      )
    val stack = StringBindingStack("CycleGraph")
    for (binding in bindings) {
      graph.tryPut(binding, stack)
    }
    return graph
  }

  companion object {
    /** Builds one strongly connected component with at least three vertices. */
    fun generate(scenario: CycleGraphScenario, size: Int): CycleGraphWorkload {
      require(size >= 3) { "A cycle workload needs at least three vertices." }
      val width = (size - 1).toString().length
      val ringSplit = (size - 1) / 2
      val keys =
        List(size) { index ->
          val prefix =
            when (scenario) {
              CycleGraphScenario.SHARED_HUB -> {
                when {
                  index == size - 1 -> "Z"
                  index < ringSplit -> "A"
                  else -> "B"
                }
              }
              CycleGraphScenario.SPARSE_HARD_TAIL,
              CycleGraphScenario.DENSE_HARD_TAIL -> {
                if (index < 2) {
                  "C"
                } else {
                  "T"
                }
              }
              else -> "Node"
            }
          StringTypeKey(prefix + index.toString().padStart(width, '0'))
        }
      val contextKeys = keys.map { StringContextualTypeKey.create(it) }
      val providerKeys = keys.map {
        StringContextualTypeKey.create(StringTypeKey("() -> ${it.type}"))
      }
      val dependencies = Array(size) { mutableListOf<StringContextualTypeKey>() }

      fun addEdge(from: Int, to: Int, deferred: Boolean = false) {
        val dependency =
          if (deferred) {
            providerKeys[to]
          } else {
            contextKeys[to]
          }
        dependencies[from].add(dependency)
      }

      when (scenario) {
        CycleGraphScenario.PROVIDER_RING -> {
          for (index in keys.indices) {
            addEdge(index, (index + 1) % size, deferred = index == keys.lastIndex)
          }
        }
        CycleGraphScenario.EAGER_RING_WITH_DEFERRED_CHORDS -> {
          for (index in keys.indices) {
            addEdge(index, (index + 1) % size)
            addEdge(index, (index + 2) % size, deferred = true)
          }
        }
        CycleGraphScenario.SHARED_HUB -> {
          val hub = keys.lastIndex
          addEdge(hub, 0, deferred = true)
          addEdge(hub, ringSplit, deferred = true)
          for (index in 0 until hub) {
            val closesRing = index == ringSplit - 1 || index == hub - 1
            val target =
              if (closesRing) {
                hub
              } else {
                index + 1
              }
            addEdge(index, target, deferred = true)
          }
        }
        CycleGraphScenario.COMPLETE_DEFERRED -> {
          for (from in keys.indices) {
            for (to in keys.indices) {
              if (from != to) {
                addEdge(from, to, deferred = true)
              }
            }
          }
        }
        CycleGraphScenario.SPARSE_HARD_TAIL,
        CycleGraphScenario.DENSE_HARD_TAIL -> {
          addEdge(0, 1)
          addEdge(1, 0)
          addEdge(1, keys.lastIndex, deferred = true)
          for (index in 2 until size) {
            if (scenario == CycleGraphScenario.DENSE_HARD_TAIL) {
              addEdge(index, 0)
              for (predecessor in 2 until index) {
                addEdge(index, predecessor)
              }
            } else {
              val target =
                if (index == 2) {
                  0
                } else {
                  index - 1
                }
              addEdge(index, target)
            }
          }
        }
      }

      val expectedDeferredKeys =
        when (scenario) {
          CycleGraphScenario.PROVIDER_RING,
          CycleGraphScenario.SHARED_HUB -> setOf(keys.last())
          CycleGraphScenario.COMPLETE_DEFERRED -> keys.dropLast(1).toSet()
          else -> null
        }
      val bindings = keys.mapIndexed { index, key ->
        StringBinding(key, dependencies[index].toList())
      }
      val root = contextKeys.first()
      return CycleGraphWorkload(
        scenario = scenario,
        size = size,
        bindings = bindings,
        roots = linkedMapOf(root to StringBindingStack.Entry(root)),
        hasHardCycle = expectedDeferredKeys == null,
        expectedDeferredKeys = expectedDeferredKeys,
      )
    }
  }
}
