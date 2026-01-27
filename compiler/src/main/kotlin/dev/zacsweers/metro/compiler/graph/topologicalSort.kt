/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.zacsweers.metro.compiler.graph

import androidx.collection.MutableScatterMap
import androidx.collection.ScatterMap
import dev.zacsweers.metro.compiler.filterToSet
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.getValue
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.traceNested
import java.util.PriorityQueue
import java.util.SortedMap
import java.util.SortedSet
import org.jetbrains.annotations.TestOnly

/**
 * Returns a new list where each element is preceded by its results in [sourceToTarget]. The first
 * element will return no values in [sourceToTarget].
 *
 * Modifications from Zipline
 * - Add [onMissing] check
 * - Add [onCycle] for customizing how cycle errors are handled
 * - Add [isDeferrable] for indicating deferrable dependencies
 * - Implementation modified to instead use a Tarjan-processed SCC DAG
 *
 * @param sourceToTarget a function that returns nodes that should precede the argument in the
 *   result.
 * @see <a href="Adapted from
 *   https://github.com/cashapp/zipline/blob/30ca7c9d782758737e9d20e8d9505930178d1992/zipline/src/hostMain/kotlin/app/cash/zipline/internal/topologicalSort.kt">Adapted
 *   from Zipline's implementation</a>
 */
@TestOnly
context(traceScope: TraceScope)
internal fun <T : Comparable<T>> Iterable<T>.topologicalSort(
  sourceToTarget: (T) -> Iterable<T>,
  onCycle: (List<T>) -> Nothing = { cycle ->
    val message = buildString {
      append("No topological ordering is possible for these items:")

      for (unorderedItem in cycle.reversed()) {
        append("\n  ")
        append(unorderedItem)
        val unsatisfiedDeps = sourceToTarget(unorderedItem).toSet()
        unsatisfiedDeps.joinTo(this, separator = ", ", prefix = " (", postfix = ")")
      }
    }
    throw IllegalArgumentException(message)
  },
  isDeferrable: (from: T, to: T) -> Boolean = { _, _ -> false },
  onMissing: (source: T, missing: T) -> Unit = { source, missing ->
    throw IllegalArgumentException("No element for $missing found for $source")
  },
): List<T> {
  // TODO this is really just here for tests
  val fakeMap =
    MutableScatterMap<T, Any>().apply {
      for (key in this@topologicalSort) {
        put(key, Any())
      }
    }
  val adjacencyResult = fakeMap.buildFullAdjacency(sourceToTarget, onMissing)
  val topology = topologicalSort(adjacencyResult.forward, isDeferrable, onCycle)
  return topology.sortedKeys
}

internal fun <T> List<T>.isTopologicallySorted(sourceToTarget: (T) -> Iterable<T>): Boolean {
  val seenNodes = mutableSetOf<T>()
  for (node in this) {
    if (sourceToTarget(node).any { it !in seenNodes }) return false
    seenNodes.add(node)
  }
  return true
}

@JvmName("buildFullAdjacencyReceiver")
internal fun <T : Comparable<T>> ScatterMap<T, *>.buildFullAdjacency(
  sourceToTarget: (T) -> Iterable<T>,
  onMissing: (source: T, missing: T) -> Unit,
): GraphAdjacency<T> {
  val map = this

  /**
   * Sort our map keys and list values here for better performance later (avoiding needing to
   * defensively sort in [computeStronglyConnectedComponents]).
   */
  val forward = sortedMapOf<T, SortedSet<T>>()
  val reverse = mutableMapOf<T, MutableSet<T>>()

  forEachKey { key ->
    val dependencies = forward.getOrPut(key, ::sortedSetOf)

    for (targetKey in sourceToTarget(key)) {
      if (targetKey !in map) {
        // may throw, or silently allow
        onMissing(key, targetKey)
        // If we got here, this missing target is allowable (i.e. a default value). Just ignore it
        continue
      }
      dependencies += targetKey

      // Build reverse adjacency: targetKey is depended on by key
      reverse.getAndAdd(targetKey, key)
    }
  }
  return GraphAdjacency(forward, reverse)
}

/**
 * Builds the full adjacency list (both forward and reverse).
 * * Keeps all edges (strict _and_ deferrable).
 * * Prunes edges whose target isn't in [bindings], delegating the decision to [onMissing].
 */
internal fun <TypeKey : Comparable<TypeKey>, Binding : Any> buildFullAdjacency(
  bindings: ScatterMap<TypeKey, Binding>,
  dependenciesOf: (Binding) -> Iterable<TypeKey>,
  onMissing: (source: TypeKey, missing: TypeKey) -> Unit,
): GraphAdjacency<TypeKey> {
  return bindings.buildFullAdjacency(
    sourceToTarget = { key -> dependenciesOf(bindings.getValue(key)) },
    onMissing = onMissing,
  )
}

/**
 * @param sortedKeys Topologically sorted list of keys.
 * @param deferredTypes Vertices that sit inside breakable cycles.
 * @param reachableKeys Vertices that were deemed reachable by any input roots.
 * @param adjacency The reachable adjacency map used for topological sorting.
 * @param components The strongly connected components computed during sorting.
 * @param componentOf Mapping from vertex to component ID.
 * @param componentDag The DAG of components (edges between component IDs).
 */
internal data class GraphTopology<T>(
  val sortedKeys: List<T>,
  val deferredTypes: Set<T>,
  val reachableKeys: Set<T>,
  val adjacency: SortedMap<T, SortedSet<T>>,
  val components: List<Component<T>>,
  val componentOf: Map<T, Int>,
  val componentDag: Map<Int, Set<Int>>,
)

/**
 * Result of building adjacency maps, containing both forward and reverse mappings.
 *
 * @property forward Maps each vertex to its dependencies (outgoing edges).
 * @property reverse Maps each vertex to its dependents (incoming edges).
 */
internal data class GraphAdjacency<T>(
  val forward: SortedMap<T, SortedSet<T>>,
  val reverse: Map<T, Set<T>>,
)

/**
 * Returns the vertices in a valid topological order. Every edge in [fullAdjacency] is respected;
 * strict cycles throw, breakable cycles (those containing a deferrable edge) are deferred.
 *
 * Two-phase binding graph validation pipeline:
 * ```
 * Binding Graph
 *      │
 *      ▼
 * ┌─────────────────────┐
 * │  Phase 1: Tarjan    │
 * │  ┌─────────────────┐│
 * │  │ Find SCCs       ││  ◄─── Detects cycles
 * │  │ Classify cycles ││  ◄─── Hard vs Soft
 * │  │ Build comp DAG  ││  ◄─── collapse the SCCs → nodes
 * │  └─────────────────┘│
 * └─────────────────────┘
 *      │
 *      ▼
 * ┌──────────────────────┐
 * │  Phase 2: Kahn       │
 * │  ┌──────────────────┐│
 * │  │ Topo sort DAG    ││  ◄─── Deterministic order
 * │  │ Expand components││  ◄─── Components → vertices
 * │  └──────────────────┘│
 * └──────────────────────┘
 *      │
 *      ▼
 * TopoSortResult
 * ├─ sortedKeys (dependency order)
 * └─ deferredTypes (Lazy/Provider)
 * ```
 *
 * @param fullAdjacency outgoing‑edge map (every vertex key must be present)
 * @param isDeferrable predicate for "edge may break a cycle"
 * @param onCycle called with the offending cycle if no deferrable edge
 * @param roots optional set of source roots for computing reachability. If null, all keys will be
 *   kept.
 * @param onSortedCycle optional callback reporting (sorted) cycles.
 */
context(traceScope: TraceScope)
internal fun <V : Comparable<V>> topologicalSort(
  fullAdjacency: SortedMap<V, SortedSet<V>>,
  isDeferrable: (from: V, to: V) -> Boolean,
  onCycle: (List<V>) -> Unit,
  roots: SortedSet<V>? = null,
  isImplicitlyDeferrable: (V) -> Boolean = { false },
  onSortedCycle: (List<V>) -> Unit = {},
): GraphTopology<V> {
  val deferredTypes = mutableSetOf<V>()

  // Collapse the graph into strongly‑connected components
  // Also builds reachable adjacency in the same pass (avoiding separate filter)
  val (components, componentOf, reachableKeys) =
    traceNested("Compute SCCs") { fullAdjacency.computeStronglyConnectedComponents(roots) }

  // Check for cycles
  traceNested("Check for cycles") {
    for (component in components) {
      val vertices = component.vertices

      if (vertices.size == 1) {
        val isSelfLoop = fullAdjacency[vertices[0]].orEmpty().any { it == vertices[0] }
        if (!isSelfLoop) {
          // trivial acyclic
          continue
        }
      }

      // Look for cycles - find minimal set of nodes to defer
      val contributorsToCycle =
        findMinimalDeferralSet(
          vertices = vertices,
          fullAdjacency = reachableKeys,
          componentOf = componentOf,
          componentId = component.id,
          isDeferrable = isDeferrable,
          isImplicitlyDeferrable = isImplicitlyDeferrable,
        )

      if (contributorsToCycle.isEmpty()) {
        // no deferrable -> hard cycle
        onCycle(vertices)
      } else {
        deferredTypes += contributorsToCycle
      }
    }
  }

  val componentDag =
    traceNested("Build component DAG") { buildComponentDag(reachableKeys, componentOf) }
  val componentOrder =
    traceNested("Topo sort component DAG") {
      topologicallySortComponentDag(componentDag, components.size)
    }

  // Expand each component back to its original vertices
  val sortedKeys =
    traceNested("Expand components") {
      componentOrder.flatMap { id ->
        val component = components[id]
        if (component.vertices.size == 1) {
          // Single vertex - no cycle
          component.vertices
        } else {
          // Multiple vertices in a cycle - sort them respecting non-deferrable dependencies
          val deferredInScc = component.vertices.filterToSet { it in deferredTypes }
          sortVerticesInSCC(component.vertices, reachableKeys, isDeferrable, deferredInScc).also {
            onSortedCycle(it)
          }
        }
      }
    }

  return GraphTopology(
    // Expand each component back to its original vertices
    sortedKeys,
    deferredTypes,
    reachableKeys.keys,
    reachableKeys,
    components,
    componentOf,
    componentDag,
  )
}

/** Finds the minimal set of nodes that need to be deferred to break all cycles in the SCC. */
private fun <V : Comparable<V>> findMinimalDeferralSet(
  vertices: List<V>,
  fullAdjacency: SortedMap<V, SortedSet<V>>,
  componentOf: Map<V, Int>,
  componentId: Int,
  isDeferrable: (V, V) -> Boolean,
  isImplicitlyDeferrable: (V) -> Boolean,
): Set<V> {
  // Build the SCC-internal adjacency and deferrable edges ONCE upfront
  // This avoids rebuilding these structures for each candidate test
  val sccAdjacency = mutableMapOf<V, MutableSet<V>>()
  val deferrableEdgesFrom = mutableMapOf<V, MutableSet<V>>()
  val potentialCandidates = mutableSetOf<V>()

  for (from in vertices) {
    val targets = mutableSetOf<V>()
    for (to in fullAdjacency[from].orEmpty()) {
      // Only consider edges that stay inside the SCC
      if (componentOf[to] == componentId) {
        targets.add(to)
        if (isDeferrable(from, to)) {
          // Track deferrable edges and candidates
          deferrableEdgesFrom.getOrPut(from) { mutableSetOf() }.add(to)
          potentialCandidates.add(from)
        }
      }
    }
    sccAdjacency[from] = targets
  }

  if (potentialCandidates.isEmpty()) {
    return emptySet()
  }

  // Create reusable cycle checker that can mask edges dynamically
  val cycleChecker = ReusableCycleChecker(vertices, sccAdjacency, deferrableEdgesFrom)

  // TODO this is... ugly? It's like we want a hierarchy of deferrable types (whole-node or just
  //  edge)
  // Prefer implicitly deferrable types (i.e. assisted factories) over regular types
  // Sort candidates once upfront instead of sorting in each loop
  val sortedCandidates =
    potentialCandidates.sortedWith(
      compareBy(
        { !isImplicitlyDeferrable(it) }, // implicitly deferrable first (false < true)
        { it }, // then by natural order
      )
    )

  // Try each candidate
  for (candidate in sortedCandidates) {
    if (cycleChecker.isAcyclicWith(setOf(candidate))) {
      return setOf(candidate)
    }
  }

  // If no single candidate works, try all candidates together
  if (cycleChecker.isAcyclicWith(potentialCandidates)) {
    return potentialCandidates
  }

  // No combination of deferrable edges can break the cycle
  return emptySet()
}

/**
 * Reusable cycle checker that avoids rebuilding adjacency maps for each candidate test. Instead, it
 * masks deferrable edges dynamically during DFS traversal.
 */
private class ReusableCycleChecker<V>(
  private val vertices: List<V>,
  private val sccAdjacency: Map<V, Set<V>>,
  private val deferrableEdgesFrom: Map<V, Set<V>>,
) {
  // Reuse these sets across checks to reduce allocations
  private val visited = mutableSetOf<V>()
  private val inStack = mutableSetOf<V>()

  /**
   * Checks if the graph would be acyclic if we defer the given nodes. When a node is deferred, its
   * deferrable outgoing edges are skipped.
   */
  fun isAcyclicWith(deferredNodes: Set<V>): Boolean {
    visited.clear()
    inStack.clear()

    for (node in vertices) {
      if (node !in visited && !dfs(node, deferredNodes)) {
        return false
      }
    }
    return true
  }

  private fun dfs(node: V, deferredNodes: Set<V>): Boolean {
    if (node in inStack) {
      // Cycle found
      return false
    }
    if (node in visited) {
      return true
    }

    visited.add(node)
    inStack.add(node)

    val neighbors = sccAdjacency[node].orEmpty()
    val deferrableFromThis =
      if (node in deferredNodes) {
        deferrableEdgesFrom[node]
      } else {
        null
      }

    for (neighbor in neighbors) {
      // Skip deferrable edges from deferred nodes (this matches what sortVerticesInSCC will do)
      if (deferrableFromThis != null && neighbor in deferrableFromThis) continue
      if (!dfs(neighbor, deferredNodes)) return false
    }

    inStack.remove(node)
    return true
  }
}

/**
 * Sorts vertices within an SCC by respecting non-deferrable dependencies. For cycles broken by
 * deferrable edges, we can still maintain a meaningful order.
 */
private fun <V : Comparable<V>> sortVerticesInSCC(
  vertices: List<V>,
  fullAdjacency: SortedMap<V, SortedSet<V>>,
  isDeferrable: (V, V) -> Boolean,
  deferredInScc: Set<V>,
): List<V> {
  if (vertices.size <= 1) return vertices
  val inScc = vertices.toSet()

  // An edge is "soft" inside this SCC only if it's deferrable and the source is deferred
  fun isSoftEdge(from: V, to: V): Boolean {
    return isDeferrable(from, to) && from in deferredInScc
  }

  // v -> hard prereqs (non-soft edges)
  val hardDeps = mutableMapOf<V, MutableSet<V>>()
  // prereq -> dependents (via hard edges)
  val revHard = mutableMapOf<V, MutableSet<V>>()

  for (v in vertices) {
    for (dep in fullAdjacency[v].orEmpty()) {
      if (dep !in inScc) continue
      if (isSoftEdge(v, dep)) {
        // ignore only these edges when ordering
        continue
      }
      hardDeps.getAndAdd(v, dep)
      revHard.getAndAdd(dep, v)
    }
  }

  val hardIn = vertices.associateWithTo(mutableMapOf()) { hardDeps[it]?.size ?: 0 }

  // Sort ready by:
  // 1 - nodes that are in deferredInScc (i.e., emit DelegateFactory before its users)
  // 2 - more hard dependents (unlocks more)
  // 3 - natural order for determinism
  val ready =
    PriorityQueue<V> { a, b ->
      val aDef = a in deferredInScc
      val bDef = b in deferredInScc
      if (aDef != bDef) return@PriorityQueue if (aDef) -1 else 1

      val aFanOut = revHard[a]?.size ?: 0
      val bFanOut = revHard[b]?.size ?: 0
      if (aFanOut != bFanOut) return@PriorityQueue bFanOut - aFanOut

      a.compareTo(b)
    }

  // Seed with nodes that have no hard deps
  vertices.filterTo(ready) { hardIn.getValue(it) == 0 }

  val result = ArrayDeque<V>(vertices.size)
  while (ready.isNotEmpty()) {
    val v = ready.remove()
    result += v
    for (depender in revHard[v].orEmpty()) {
      val degree = hardIn.getValue(depender) - 1
      hardIn[depender] = degree
      if (degree == 0) {
        ready += depender
      }
    }
  }

  check(result.size == vertices.size) {
    "Hard cycle remained inside SCC after removing selected soft edges"
  }
  return result
}

internal data class Component<V>(val id: Int, val vertices: MutableList<V> = mutableListOf())

internal data class TarjanResult<V : Comparable<V>>(
  val components: List<Component<V>>,
  val componentOf: Map<V, Int>,
  /** Adjacency map filtered to only reachable vertices (built during SCC traversal). */
  val reachableAdjacency: SortedMap<V, SortedSet<V>>,
)

/**
 * Computes the strongly connected components (SCCs) of a directed graph using Tarjan's algorithm.
 *
 * NOTE: For performance and determinism, this implementation assumes [this] adjacency is already
 * sorted (both keys and each set of values).
 *
 * @param this A map representing the directed graph where the keys are vertices of type [V] and the
 *   values are sets of vertices to which each key vertex has outgoing edges.
 * @param roots An optional input of source roots to walk from. Defaults to this map's keys. This
 *   can be useful to only return accessible nodes.
 * @return A pair where the first element is a list of components (each containing an ID and its
 *   associated vertices) and the second element is a map that associates each vertex with the ID of
 *   its component.
 * @see <a
 *   href="https://en.wikipedia.org/wiki/Tarjan%27s_strongly_connected_components_algorithm">Tarjan's
 *   algorithm</a>
 */
internal fun <V : Comparable<V>> SortedMap<V, SortedSet<V>>.computeStronglyConnectedComponents(
  roots: SortedSet<V>? = null
): TarjanResult<V> {
  var nextIndex = 0
  var nextComponentId = 0

  // vertices of the *current* DFS branch
  val stack = ArrayDeque<V>()
  val onStack = mutableSetOf<V>()

  // DFS discovery time of each vertex
  // Analogous to "v.index" refs in the linked algo
  val indexMap = mutableMapOf<V, Int>()
  // The lowest discovery index that v can reach without
  // leaving the current DFS stack.
  // Analogous to "v.lowlink" refs in the linked algo
  val lowLinkMap = mutableMapOf<V, Int>()
  // Mapping of V to the id of the SCC that v ends up in
  val componentOf = mutableMapOf<V, Int>()
  val components = mutableListOf<Component<V>>()

  // Build reachable adjacency during traversal (avoids separate filtering pass)
  val reachableAdjacency = sortedMapOf<V, SortedSet<V>>()

  fun strongConnect(v: V) {
    // Set the depth index for v to the smallest unused index
    indexMap[v] = nextIndex
    lowLinkMap[v] = nextIndex
    nextIndex++

    stack += v
    onStack += v

    // Get the edges for this vertex
    val edges = this[v].orEmpty()

    for (w in edges) {
      if (w !in indexMap) {
        // Successor w has not yet been visited; recurse on it
        strongConnect(w)
        lowLinkMap[v] = minOf(lowLinkMap.getValue(v), lowLinkMap.getValue(w))
      } else if (w in onStack) {
        // Successor w is in stack S and hence in the current SCC
        // If w is not on stack, then (v, w) is an edge pointing to an SCC already found and must be
        // ignored
        // See below regarding the next line
        lowLinkMap[v] = minOf(lowLinkMap.getValue(v), indexMap.getValue(w))
      }
    }

    // If v is a root node, pop the stack and generate an SCC
    if (lowLinkMap.getValue(v) == indexMap.getValue(v)) {
      val component = Component<V>(nextComponentId++)
      while (true) {
        val popped = stack.removeLast()
        onStack -= popped
        component.vertices += popped
        componentOf[popped] = component.id
        if (popped == v) {
          break
        }
      }
      components += component
    }
  }

  val startVertices = roots ?: keys

  for (v in startVertices) {
    if (v !in indexMap) {
      strongConnect(v)
    }
  }

  // Build reachable adjacency after traversal (we now know which vertices are reachable)
  // This is done after traversal because we need to filter edges to only reachable targets
  for (v in indexMap.keys) {
    val edges = this[v]
    if (edges != null) {
      // Filter edges to only include reachable targets
      val reachableEdges = edges.filterTo(sortedSetOf()) { it in indexMap }
      reachableAdjacency[v] = reachableEdges
    } else {
      reachableAdjacency[v] = sortedSetOf()
    }
  }

  return TarjanResult(components, componentOf, reachableAdjacency)
}

/**
 * Builds a DAG of SCCs from the original graph edges.
 *
 * In this DAG, nodes represent SCCs of the input graph, and edges represent dependencies between
 * SCCs. The graph is constructed such that arrows are reversed for dependency tracking (Kahn's
 * algorithm compatibility).
 *
 * @param originalEdges A map representing the edges of the original graph, where the key is a
 *   vertex and the value is a list of vertices it points to.
 * @param componentOf A map associating each vertex with its corresponding SCC number.
 * @return A map representing the DAG, where the key is the SCC number, and the value is a set of
 *   SCCs it depends on.
 */
private fun <V> buildComponentDag(
  originalEdges: Map<V, Set<V>>,
  componentOf: Map<V, Int>,
): Map<Int, Set<Int>> {
  val dag = mutableMapOf<Int, MutableSet<Int>>()

  for ((fromVertex, outs) in originalEdges) {
    // prerequisite side
    val prereqComp = componentOf.getValue(fromVertex)
    for (toVertex in outs) {
      // dependent side
      val dependentComp = componentOf.getValue(toVertex)
      if (prereqComp != dependentComp) {
        // Reverse the arrow so Kahn sees "prereq → dependent"
        dag.getAndAdd(dependentComp, prereqComp)
      }
    }
  }
  return dag
}

/**
 * Performs a Kahn topological sort on the [dag] and returns the sorted order.
 *
 * @param dag A map representing the DAG, where keys are node identifiers and values are sets of
 *   child node identifiers (edges).
 * @param componentCount The total number of components (nodes) in the graph.
 * @return A list of integers representing the topologically sorted order of the nodes. Throws an
 *   exception if a cycle remains in the graph, which should be impossible after a proper SCC
 *   collapse.
 * @see <a href="https://en.wikipedia.org/wiki/Topological_sorting">Topological sorting</a>
 * @see <a href="https://www.interviewcake.com/concept/java/topological-sort">Topological sort</a>
 */
private fun topologicallySortComponentDag(dag: Map<Int, Set<Int>>, componentCount: Int): List<Int> {
  val inDegree = IntArray(componentCount)
  // Avoid temporary list allocation from flatten()
  for (children in dag.values) {
    for (child in children) {
      inDegree[child]++
    }
  }

  /**
   * Why a [PriorityQueue] instead of a FIFO queue like [ArrayDeque]?
   *
   * ```
   * (0)──▶(2)
   *  │
   *  └───▶(1)
   * ```
   *
   * After we process component 0, both 1 and 2 are "ready". A plain ArrayDeque would enqueue them
   * in whatever order the [dag]'s keys are, which isn't deterministic.
   *
   * Using a PriorityQueue means we *always* dequeue the lowest id first (1 before 2 in this
   * example). That keeps generated code consistent across builds.
   */
  val queue =
    PriorityQueue<Int>().apply {
      // Seed the work‑queue with every component whose in‑degree is 0.
      for (id in 0 until componentCount) {
        if (inDegree[id] == 0) {
          add(id)
        }
      }
    }

  val order = mutableListOf<Int>()
  while (queue.isNotEmpty()) {
    val c = queue.remove()
    order += c
    for (n in dag[c].orEmpty()) {
      if (--inDegree[n] == 0) {
        queue += n
      }
    }
  }
  check(order.size == componentCount) { "Cycle remained after SCC collapse (should be impossible)" }
  return order
}
