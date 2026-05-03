// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.IntList
import androidx.collection.IntObjectMap
import androidx.collection.IntSet
import androidx.collection.MutableIntList
import androidx.collection.MutableIntObjectMap
import androidx.collection.MutableIntSet
import androidx.collection.MutableObjectIntMap
import androidx.collection.ObjectIntMap
import androidx.collection.ScatterMap
import androidx.collection.emptyIntSet
import dev.zacsweers.metro.compiler.filterToSet
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.Collections.emptySortedSet
import java.util.PriorityQueue
import java.util.SortedMap
import java.util.SortedSet

/**
 * @param sortedKeys Topologically sorted list of keys.
 * @param deferredTypes Vertices that sit inside breakable cycles.
 * @param reachableKeys Vertices that were deemed reachable by any input roots.
 * @param adjacency The reachable adjacency (forward and reverse) used for topological sorting.
 * @param components The strongly connected components computed during sorting.
 * @param componentOf Mapping from vertex to component ID.
 * @param componentDag The DAG of components (edges between component IDs).
 */
internal data class GraphTopology<T>(
  val sortedKeys: List<T>,
  val deferredTypes: Set<T>,
  val reachableKeys: Set<T>,
  val adjacency: GraphAdjacency<T>,
  val components: List<Component<T>>,
  val componentOf: ObjectIntMap<T>,
  val componentDag: IntObjectMap<IntSet>,
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
internal fun <V : Comparable<V>> metroSort(
  fullAdjacency: SortedMap<V, SortedSet<V>>,
  isDeferrable: (from: V, to: V) -> Boolean,
  onCycle: (List<V>) -> Unit,
  roots: SortedSet<V>? = null,
  isImplicitlyDeferrable: (V) -> Boolean = { false },
  onSortedCycle: (List<V>) -> Unit = {},
): GraphTopology<V> {
  val deferredTypes = mutableSetOf<V>()

  // Collapse the graph into strongly‑connected components
  // Also builds reachable adjacency (forward and reverse) in the same pass (avoiding separate
  // filter)
  val (components, componentOf, reachableAdjacency) =
    trace("Compute SCCs") { fullAdjacency.computeStronglyConnectedComponents(roots) }

  // Check for cycles
  trace("Check for cycles") {
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
          fullAdjacency = reachableAdjacency.forward,
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
    trace("Build component DAG") { buildComponentDag(reachableAdjacency.forward, componentOf) }
  val componentOrder =
    trace("Topo sort component DAG") {
      topologicallySortComponentDag(componentDag, components.size)
    }

  // Expand each component back to its original vertices
  val sortedKeys = ArrayList<V>(fullAdjacency.size)
  trace("Expand components") {
    componentOrder.forEach { id ->
      val component = components[id]
      if (component.vertices.size == 1) {
        // Single vertex - no cycle
        sortedKeys += component.vertices[0]
      } else {
        // Multiple vertices in a cycle - sort them respecting non-deferrable dependencies
        val deferredInScc = component.vertices.filterToSet { it in deferredTypes }
        sortedKeys +=
          sortVerticesInSCC(
              component.vertices,
              reachableAdjacency.forward,
              isDeferrable,
              deferredInScc,
            )
            .also { onSortedCycle(it) }
      }
    }
  }

  return GraphTopology(
    // Expand each component back to its original vertices
    sortedKeys,
    deferredTypes,
    reachableAdjacency.forward.keys,
    reachableAdjacency,
    components,
    componentOf,
    componentDag,
  )
}

/** Finds the minimal set of nodes that need to be deferred to break all cycles in the SCC. */
private fun <V : Comparable<V>> findMinimalDeferralSet(
  vertices: List<V>,
  fullAdjacency: SortedMap<V, SortedSet<V>>,
  componentOf: ObjectIntMap<V>,
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
  val componentOf: ObjectIntMap<V>,
  /**
   * Adjacency (forward and reverse) filtered to only reachable vertices (built during SCC
   * traversal).
   */
  val reachableAdjacency: GraphAdjacency<V>,
)

/**
 * Computes the strongly connected components (SCCs) of a directed graph using Tarjan's algorithm.
 *
 * NOTE: For performance and determinism, this implementation assumes [this] adjacency is already
 * sorted (both keys and each set of values).
 *
 * @param roots An optional input of source roots to walk from. Defaults to this map's keys. This
 *   can be useful to only return accessible nodes.
 * @return A pair where the first element is a list of components (each containing an ID and its
 *   associated vertices) and the second element is a map that associates each vertex with the ID of
 *   its component.
 * @receiver A map representing the directed graph where the keys are vertices of type [V] and the
 *   values are sets of vertices to which each key vertex has outgoing edges.
 * @see <a
 *   href="https://en.wikipedia.org/wiki/Tarjan%27s_strongly_connected_components_algorithm">Tarjan's
 *   algorithm</a>
 */
internal fun <V : Comparable<V>> SortedMap<V, SortedSet<V>>.computeStronglyConnectedComponents(
  roots: SortedSet<V>? = null
): TarjanResult<V> {
  // Map vertices to dense int ids in sorted order so adjacency lookups, visited bookkeeping, and
  // the DFS stack can run on primitive arrays. This avoids per-edge string-keyed map lookups
  // (IrTypeKey.compareTo / equals) inside the recursion.
  val n = size
  if (n == 0) {
    return TarjanResult(
      components = emptyList(),
      componentOf = MutableObjectIntMap(),
      reachableAdjacency = GraphAdjacency(sortedMapOf(), emptyMap()),
    )
  }

  // Store as Array<Any?> to avoid array-class cast issues with generic V; we cast individual
  // elements back to V on read.
  val idToVertex = arrayOfNulls<Any>(n)
  val vertexToId = MutableObjectIntMap<V>(n)
  run {
    var i = 0
    for (key in keys) {
      idToVertex[i] = key
      vertexToId[key] = i
      i++
    }
  }
  fun vertexAt(id: Int): V {
    @Suppress("UNCHECKED_CAST")
    return idToVertex[id] as V
  }

  // Build int-indexed adjacency. Each row is already sorted because the source SortedSet is
  // sorted and ids preserve that order.
  val adj = arrayOfNulls<IntArray>(n)
  for ((vertex, edges) in this) {
    val fromId = vertexToId[vertex]
    if (edges.isEmpty()) {
      adj[fromId] = EMPTY_INT_ARRAY
    } else {
      val arr = IntArray(edges.size)
      var i = 0
      for (e in edges) {
        arr[i++] = vertexToId[e]
      }
      adj[fromId] = arr
    }
  }

  // Tarjan state, all primitive-backed.
  val indexOfId = IntArray(n) { UNVISITED }
  val lowLinkOfId = IntArray(n)
  val onStack = BooleanArray(n)
  val componentOfId = IntArray(n) { UNVISITED }
  val dfsStack = MutableIntList(n)

  var nextIndex = 0
  var nextComponentId = 0
  // Materialised SCC vertex lists (in int form) keyed by component id.
  val componentMembers = mutableListOf<MutableIntList>()

  // Iterative Tarjan: avoids recursion-depth blowups on deep graphs.
  // Each frame holds (v, current edge index).
  val callStack = MutableIntList(n)
  val edgeCursor = MutableIntList(n)

  fun visit(start: Int) {
    if (indexOfId[start] != UNVISITED) return
    callStack.add(start)
    edgeCursor.add(0)
    indexOfId[start] = nextIndex
    lowLinkOfId[start] = nextIndex
    nextIndex++
    dfsStack.add(start)
    onStack[start] = true

    while (callStack.isNotEmpty()) {
      val top = callStack.size - 1
      val v = callStack[top]
      val edges = adj[v]!!
      val cursor = edgeCursor[top]

      if (cursor < edges.size) {
        edgeCursor[top] = cursor + 1
        val w = edges[cursor]
        if (indexOfId[w] == UNVISITED) {
          // Recurse on w by pushing a new frame.
          indexOfId[w] = nextIndex
          lowLinkOfId[w] = nextIndex
          nextIndex++
          dfsStack.add(w)
          onStack[w] = true
          callStack.add(w)
          edgeCursor.add(0)
        } else if (onStack[w]) {
          if (indexOfId[w] < lowLinkOfId[v]) {
            lowLinkOfId[v] = indexOfId[w]
          }
        }
        continue
      }

      // All edges of v processed: pop. If v is an SCC root, materialise the component.
      if (lowLinkOfId[v] == indexOfId[v]) {
        val componentId = nextComponentId++
        val members = MutableIntList()
        while (true) {
          val popped = dfsStack.removeAt(dfsStack.size - 1)
          onStack[popped] = false
          members.add(popped)
          componentOfId[popped] = componentId
          if (popped == v) break
        }
        componentMembers.add(members)
      }

      callStack.removeAt(top)
      edgeCursor.removeAt(top)
      // Propagate lowlink up to the parent frame, mirroring the recursive
      // `lowLinkMap[parent] = min(lowLinkMap[parent], lowLinkMap[v])` step.
      if (callStack.isNotEmpty()) {
        val parent = callStack[callStack.size - 1]
        if (lowLinkOfId[v] < lowLinkOfId[parent]) {
          lowLinkOfId[parent] = lowLinkOfId[v]
        }
      }
    }
  }

  if (roots != null) {
    for (root in roots) {
      val rootId = vertexToId.getOrDefault(root, UNVISITED)
      if (rootId != UNVISITED) visit(rootId)
    }
  } else {
    for (id in 0 until n) {
      visit(id)
    }
  }

  // Materialise V-typed Component list and componentOf map.
  val components = ArrayList<Component<V>>(componentMembers.size)
  val componentOf = MutableObjectIntMap<V>(n)
  for ((cid, members) in componentMembers.withIndex()) {
    val component = Component<V>(cid)
    members.forEach { id ->
      val vertex = vertexAt(id)
      component.vertices += vertex
      componentOf[vertex] = cid
    }
    components += component
  }

  // Build reachable adjacency in V-space, filtering to only visited vertices.
  val reachableForward = sortedMapOf<V, SortedSet<V>>()
  val reachableReverse = mutableMapOf<V, MutableSet<V>>()
  for (id in 0 until n) {
    if (indexOfId[id] == UNVISITED) continue
    val vertex = vertexAt(id)
    val edges = adj[id]!!
    if (edges.isEmpty()) {
      reachableForward[vertex] = emptySortedSet()
      continue
    }
    val reachableEdges = sortedSetOf<V>()
    for (toId in edges) {
      if (indexOfId[toId] == UNVISITED) continue
      val toVertex = vertexAt(toId)
      reachableEdges += toVertex
      reachableReverse.getAndAdd(toVertex, vertex)
    }
    reachableForward[vertex] = reachableEdges
  }

  return TarjanResult(components, componentOf, GraphAdjacency(reachableForward, reachableReverse))
}

private const val UNVISITED = -1
private val EMPTY_INT_ARRAY = IntArray(0)

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
): IntObjectMap<IntSet> {
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
  @Suppress("UNCHECKED_CAST") // TODO why
  return dag as IntObjectMap<IntSet>
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
private fun topologicallySortComponentDag(dag: IntObjectMap<IntSet>, componentCount: Int): IntList {
  val inDegree = IntArray(componentCount)
  // Avoid temporary list allocation from flatten()
  dag.forEachValue { children -> children.forEach { child -> inDegree[child]++ } }

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

  val order = MutableIntList()
  while (queue.isNotEmpty()) {
    val c = queue.remove()
    order += c
    dag.getOrDefault(c, emptyIntSet()).forEach { n ->
      if (--inDegree[n] == 0) {
        queue += n
      }
    }
  }
  check(order.size == componentCount) { "Cycle remained after SCC collapse (should be impossible)" }
  return order
}

internal fun <T : Comparable<T>> buildFullAdjacency(
  map: ScatterMap<T, *>,
  sourceToTarget: (T) -> Iterable<T>,
  onMissing: (source: T, missing: T) -> Unit,
): SortedMap<T, SortedSet<T>> {
  /**
   * Sort our map keys and list values here for better performance later (avoiding needing to
   * defensively sort in [computeStronglyConnectedComponents]).
   */
  val adjacency = sortedMapOf<T, SortedSet<T>>()

  map.forEachKey { key ->
    val dependencies = adjacency.getOrPut(key, ::sortedSetOf)

    for (targetKey in sourceToTarget(key)) {
      if (targetKey !in map) {
        // may throw, or silently allow
        onMissing(key, targetKey)
        // If we got here, this missing target is allowable (i.e. a default value). Just ignore it
        continue
      }
      dependencies += targetKey
    }
  }
  return adjacency
}
