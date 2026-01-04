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

import androidx.collection.IntObjectMap
import androidx.collection.IntSet
import androidx.collection.MutableIntObjectMap
import androidx.collection.MutableIntSet
import androidx.collection.MutableObjectIntMap
import androidx.collection.MutableObjectList
import androidx.collection.MutableOrderedScatterSet
import androidx.collection.MutableScatterSet
import androidx.collection.ObjectIntMap
import androidx.collection.ObjectList
import androidx.collection.OrderedScatterSet
import androidx.collection.ScatterMap
import androidx.collection.ScatterSet
import androidx.collection.emptyScatterSet
import androidx.collection.scatterSetOf
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.getValue
import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import java.util.PriorityQueue
import java.util.SortedMap
import java.util.SortedSet

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
internal fun <T : Comparable<T>> Iterable<T>.topologicalSort(
  sourceToTarget: (T) -> ScatterSet<T>,
  onCycle: (ObjectList<T>) -> Nothing = { cycle ->
    val message = buildString {
      append("No topological ordering is possible for these items:")

      for (unorderedItem in cycle.asList().reversed()) {
        append("\n  ")
        append(unorderedItem)
        val unsatisfiedDeps = sourceToTarget(unorderedItem).asSet()
        unsatisfiedDeps.joinTo(this, separator = ", ", prefix = " (", postfix = ")")
      }
    }
    throw IllegalArgumentException(message)
  },
  isDeferrable: (from: T, to: T) -> Boolean = { _, _ -> false },
  onMissing: (source: T, missing: T) -> Unit = { source, missing ->
    throw IllegalArgumentException("No element for $missing found for $source")
  },
): ObjectList<T> {
  val fullAdjacency = buildFullAdjacency(sourceToTarget, onMissing)
  val result = topologicalSort(fullAdjacency, isDeferrable, onCycle)
  return result.sortedKeys
}

internal fun <T> ObjectList<T>.isTopologicallySorted(
  sourceToTarget: (T) -> ScatterSet<T>
): Boolean {
  val seenNodes = MutableScatterSet<T>()
  forEach { node ->
    if (sourceToTarget(node).any { it !in seenNodes }) return false
    seenNodes.add(node)
  }
  return true
}

@JvmName("buildFullAdjacencyIterable")
internal fun <T : Comparable<T>> Iterable<T>.buildFullAdjacency(
  sourceToTarget: (T) -> ScatterSet<T>,
  onMissing: (source: T, missing: T) -> Unit,
): SortedMap<T, SortedSet<T>> {
  val set = MutableScatterSet<T>()
  forEach(set::add)
  return set.buildFullAdjacency(sourceToTarget, onMissing)
}

@JvmName("buildFullAdjacencyScatterMap")
internal fun <K : Comparable<K>> ScatterMap<K, *>.buildFullAdjacency(
  sourceToTarget: (K) -> ScatterSet<K>,
  onMissing: (source: K, missing: K) -> Unit,
): SortedMap<K, SortedSet<K>> {
  val set = MutableScatterSet<K>()
  forEachKey(set::add)
  return set.buildFullAdjacency(sourceToTarget, onMissing)
}

@JvmName("buildFullAdjacencyScatterSet")
internal fun <T : Comparable<T>> ScatterSet<T>.buildFullAdjacency(
  sourceToTarget: (T) -> ScatterSet<T>,
  onMissing: (source: T, missing: T) -> Unit,
): SortedMap<T, SortedSet<T>> {
  /**
   * Sort our map keys and list values here for better performance later (avoiding needing to
   * defensively sort in [computeStronglyConnectedComponents]).
   */
  val adjacency = sortedMapOf<T, SortedSet<T>>()

  forEach { key ->
    val dependencies = adjacency.getOrPut(key, ::sortedSetOf)

    sourceToTarget(key).forEach { targetKey ->
      if (targetKey !in this) {
        // may throw, or silently allow
        onMissing(key, targetKey)
        // If we got here, this missing target is allowable (i.e. a default value). Just ignore it
        return@forEach
      }
      dependencies += targetKey
    }
  }
  return adjacency
}

/**
 * Builds the full adjacency list.
 * * Keeps all edges (strict _and_ deferrable).
 * * Prunes edges whose target isn't in [bindings], delegating the decision to [onMissing].
 */
internal fun <TypeKey : Comparable<TypeKey>, Binding> buildFullAdjacency(
  bindings: ScatterMap<TypeKey, Binding>,
  dependenciesOf: (Binding) -> ScatterSet<TypeKey>,
  onMissing: (source: TypeKey, missing: TypeKey) -> Unit,
): SortedMap<TypeKey, SortedSet<TypeKey>> {
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
  val sortedKeys: ObjectList<T>,
  val deferredTypes: OrderedScatterSet<T>,
  val reachableKeys: Set<T>,
  val adjacency: SortedMap<T, SortedSet<T>>,
  val components: ObjectList<Component<T>>,
  val componentOf: ObjectIntMap<T>,
  val componentDag: IntObjectMap<out IntSet>,
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
internal fun <V : Comparable<V>> topologicalSort(
  fullAdjacency: SortedMap<V, SortedSet<V>>,
  isDeferrable: (from: V, to: V) -> Boolean,
  onCycle: (ObjectList<V>) -> Unit,
  roots: SortedSet<V>? = null,
  parentTracer: Tracer = Tracer.NONE,
  isImplicitlyDeferrable: (V) -> Boolean = { false },
  onSortedCycle: (ObjectList<V>) -> Unit = {},
): GraphTopology<V> {
  val deferredTypes = MutableOrderedScatterSet<V>()

  // Collapse the graph into strongly‑connected components
  val (components, componentOf) =
    parentTracer.traceNested("Compute SCCs") {
      fullAdjacency.computeStronglyConnectedComponents(roots)
    }

  // Only vertices visited by SCC will be in componentOf
  // TODO single pass this
  val reachableKeys = fullAdjacency.filterKeys { it in componentOf }.toSortedMap()

  // Check for cycles
  parentTracer.traceNested("Check for cycles") {
    components.forEach { component ->
      val vertices = component.vertices

      if (vertices.size == 1) {
        val isSelfLoop = fullAdjacency[vertices[0]].orEmpty().any { it == vertices[0] }
        if (!isSelfLoop) {
          // trivial acyclic
          return@forEach
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
    parentTracer.traceNested("Build component DAG") {
      buildComponentDag(reachableKeys, componentOf)
    }
  val componentOrder =
    parentTracer.traceNested("Topo sort component DAG") {
      topologicallySortComponentDag(componentDag, components.size)
    }

  // Expand each component back to its original vertices
  val sortedKeys =
    parentTracer.traceNested("Expand components") {
      MutableObjectList<V>(componentOrder.size * 2).apply {
        componentOrder.forEach { id ->
          val component = components[id]
          val vertices =
            if (component.vertices.size == 1) {
              // Single vertex - no cycle
              component.vertices
            } else {
              // Multiple vertices in a cycle - sort them respecting non-deferrable dependencies
              val deferredInScc =
                MutableScatterSet<V>().apply {
                  component.vertices.forEach { if (it in deferredTypes) add(it) }
                }
              sortVerticesInSCC(component.vertices, reachableKeys, isDeferrable, deferredInScc)
                .also { onSortedCycle(it) }
            }
          addAll(vertices)
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
  vertices: ObjectList<V>,
  fullAdjacency: SortedMap<V, SortedSet<V>>,
  componentOf: ObjectIntMap<V>,
  componentId: Int,
  isDeferrable: (V, V) -> Boolean,
  isImplicitlyDeferrable: (V) -> Boolean,
): ScatterSet<V> {
  // Collect all potential candidates for deferral
  val potentialCandidates = MutableScatterSet<V>()

  vertices.forEach { from ->
    for (to in fullAdjacency[from].orEmpty()) {
      if (componentOf[to] == componentId && isDeferrable(from, to)) {
        // Add the source as a candidate (original behavior)
        potentialCandidates.add(from)
      }
    }
  }

  if (potentialCandidates.isEmpty()) {
    return emptyScatterSet()
  }

  val potentialCandidatesSet = potentialCandidates.asSet()

  // TODO this is... ugly? It's like we want a hierarchy of deferrable types (whole-node or just
  //  edge)
  // Prefer implicitly deferrable types (i.e. assisted factories) over regular types
  val implicitlyDeferrableCandidates = potentialCandidatesSet.filter(isImplicitlyDeferrable)

  // Try implicitly deferrable candidates first
  for (candidate in implicitlyDeferrableCandidates.sorted()) {
    if (
      wouldBreakAllCycles(
        scatterSetOf(candidate),
        vertices,
        fullAdjacency,
        componentOf,
        componentId,
        isDeferrable,
      )
    ) {
      return scatterSetOf(candidate)
    }
  }

  // Then try regular candidates
  val regularCandidates = potentialCandidatesSet.filterNot(isImplicitlyDeferrable)
  for (candidate in regularCandidates.sorted()) {
    if (
      wouldBreakAllCycles(
        scatterSetOf(candidate),
        vertices,
        fullAdjacency,
        componentOf,
        componentId,
        isDeferrable,
      )
    ) {
      return scatterSetOf(candidate)
    }
  }

  // If no single candidate works, try all candidates together
  val wouldBreakAllCycles =
    wouldBreakAllCycles(
      potentialCandidates,
      vertices,
      fullAdjacency,
      componentOf,
      componentId,
      isDeferrable,
    )
  if (wouldBreakAllCycles) {
    return potentialCandidates
  }

  // No combination of deferrable edges can break the cycle
  return emptyScatterSet()
}

/** Checks if deferring the given set of nodes breaks all cycles in the SCC. */
private fun <V> wouldBreakAllCycles(
  deferredNodes: ScatterSet<V>,
  vertices: ObjectList<V>,
  fullAdjacency: SortedMap<V, SortedSet<V>>,
  componentOf: ObjectIntMap<V>,
  componentId: Int,
  isDeferrable: (V, V) -> Boolean,
): Boolean {
  // Build a reduced adjacency list without edges involving deferred nodes
  val reducedAdjacency = mutableMapOf<V, MutableSet<V>>()

  vertices.forEach { from ->
    val targets = mutableSetOf<V>()
    for (to in fullAdjacency[from].orEmpty()) {
      // stays inside SCC
      if (componentOf[to] == componentId) {
        /**
         * Skip deferrable edges where the source is deferred This matches what [sortVerticesInSCC]
         * will do
         */
        if (isDeferrable(from, to) && from in deferredNodes) continue
        targets.add(to)
      }
    }
    // Always add the node to the adjacency, even if it has no targets
    // This ensures all vertices are checked for cycles
    reducedAdjacency[from] = targets
  }

  // Check if the reduced graph is acyclic
  return isAcyclic(reducedAdjacency)
}

/** Checks if the given adjacency list represents an acyclic graph using DFS. */
private fun <V> isAcyclic(adjacency: Map<V, Set<V>>): Boolean {
  val visited = mutableSetOf<V>()
  val inStack = mutableSetOf<V>()

  fun dfs(node: V): Boolean {
    if (node in inStack) {
      // Cycle found
      return false
    }
    if (node in visited) {
      return true
    }

    visited.add(node)
    inStack.add(node)

    for (neighbor in adjacency[node].orEmpty()) {
      if (!dfs(neighbor)) return false
    }

    inStack.remove(node)
    return true
  }

  for (node in adjacency.keys) {
    if (node !in visited && !dfs(node)) {
      return false
    }
  }

  return true
}

/**
 * Sorts vertices within an SCC by respecting non-deferrable dependencies. For cycles broken by
 * deferrable edges, we can still maintain a meaningful order.
 */
private fun <V : Comparable<V>> sortVerticesInSCC(
  vertices: ObjectList<V>,
  fullAdjacency: SortedMap<V, SortedSet<V>>,
  isDeferrable: (V, V) -> Boolean,
  deferredInScc: ScatterSet<V>,
): ObjectList<V> {
  if (vertices.size <= 1) return vertices
  val inScc = MutableScatterSet<V>().apply { addAll(vertices) }

  // An edge is "soft" inside this SCC only if it's deferrable and the source is deferred
  fun isSoftEdge(from: V, to: V): Boolean {
    return isDeferrable(from, to) && from in deferredInScc
  }

  // v -> hard prereqs (non-soft edges)
  val hardDeps = mutableMapOf<V, MutableSet<V>>()
  // prereq -> dependents (via hard edges)
  val revHard = mutableMapOf<V, MutableSet<V>>()

  vertices.forEach { v ->
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

  val hardIn =
    MutableObjectIntMap<V>().apply { vertices.forEach { v -> put(v, hardDeps[v]?.size ?: 0) } }

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
  vertices.forEach { v ->
    if (hardIn[v] == 0) {
      ready += v
    }
  }

  val result = MutableObjectList<V>(vertices.size)
  while (ready.isNotEmpty()) {
    val v = ready.remove()
    result += v
    for (depender in revHard[v].orEmpty()) {
      val degree = hardIn[depender] - 1
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

internal data class Component<V>(
  val id: Int,
  val vertices: MutableObjectList<V> = MutableObjectList(),
)

internal data class TarjanResult<V : Comparable<V>>(
  val components: ObjectList<Component<V>>,
  val componentOf: ObjectIntMap<V>,
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
  val stack = MutableObjectList<V>(size)
  val onStack = MutableScatterSet<V>()

  // DFS discovery time of each vertex
  // Analogous to "v.index" refs in the linked algo
  val indexMap = MutableObjectIntMap<V>()
  // The lowest discovery index that v can reach without
  // leaving the current DFS stack.
  // Analogous to "v.lowlink" refs in the linked algo
  val lowLinkMap = MutableObjectIntMap<V>()
  // Mapping of V to the id of the SCC that v ends up in
  val componentOf = MutableObjectIntMap<V>()
  val components = MutableObjectList<Component<V>>()

  fun strongConnect(v: V) {
    // Set the depth index for v to the smallest unused index
    indexMap[v] = nextIndex
    lowLinkMap[v] = nextIndex
    nextIndex++

    stack += v
    onStack += v

    for (w in this[v].orEmpty()) {
      if (w !in indexMap) {
        // Successor w has not yet been visited; recurse on it
        strongConnect(w)
        lowLinkMap[v] = minOf(lowLinkMap[v], lowLinkMap[w])
      } else if (w in onStack) {
        // Successor w is in stack S and hence in the current SCC
        // If w is not on stack, then (v, w) is an edge pointing to an SCC already found and must be
        // ignored
        // See below regarding the next line
        lowLinkMap[v] = minOf(lowLinkMap[v], indexMap[w])
      }
    }

    // If v is a root node, pop the stack and generate an SCC
    if (lowLinkMap[v] == indexMap[v]) {
      val component = Component<V>(nextComponentId++)
      while (true) {
        val popped = stack.removeAt(stack.size - 1)
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

  return TarjanResult(components, componentOf)
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
  componentOf: ObjectIntMap<V>,
): IntObjectMap<out IntSet> {
  val dag = MutableIntObjectMap<MutableIntSet>()

  for ((fromVertex, outs) in originalEdges) {
    // prerequisite side
    val prereqComp = componentOf[fromVertex]
    for (toVertex in outs) {
      // dependent side
      val dependentComp = componentOf[toVertex]
      if (prereqComp != dependentComp) {
        // Reverse the arrow so Kahn sees "prereq → dependent"
        dag.getOrPut(dependentComp, ::MutableIntSet).add(prereqComp)
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
private fun topologicallySortComponentDag(
  dag: IntObjectMap<out IntSet>,
  componentCount: Int,
): ObjectList<Int> {
  val inDegree = IntArray(componentCount)
  dag.forEachValue { intSet -> intSet.forEach { inDegree[it]++ } }

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

  val order = MutableObjectList<Int>()
  while (queue.isNotEmpty()) {
    val c = queue.remove()
    order += c
    dag[c]?.let { intSet ->
      intSet.forEach { n ->
        if (--inDegree[n] == 0) {
          queue += n
        }
      }
    }
  }
  check(order.size == componentCount) { "Cycle remained after SCC collapse (should be impossible)" }
  return order
}
