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
import androidx.collection.emptyIntObjectMap
import androidx.collection.emptyIntSet
import dev.zacsweers.metro.compiler.calculateInitialCapacity
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
 * │  │ Build comp DAG  ││  ◄─── collapse the SCCs -> nodes
 * │  └─────────────────┘│
 * └─────────────────────┘
 *      │
 *      ▼
 * ┌──────────────────────┐
 * │  Phase 2: Kahn       │
 * │  ┌──────────────────┐│
 * │  │ Topo sort DAG    ││  ◄─── Deterministic order
 * │  │ Expand components││  ◄─── Components -> vertices
 * │  └──────────────────┘│
 * └──────────────────────┘
 *      │
 *      ▼
 * TopoSortResult
 * ├─ sortedKeys (dependency order)
 * └─ deferredTypes (Lazy/Provider)
 * ```
 *
 * ### Deferrable (breakable) cycles
 *
 * Some DI cycles can be "valid" if at least one edge is wrapped in a deferrable type (e.g.,
 * `Provider`/`Lazy`/ `() -> T`), which means the dependency is resolved on-demand rather than at
 * construction. The [isDeferrable] predicate identifies such edges.
 *
 * ```
 *      ┌────── Provider<A> ──────┐
 *      │     (deferrable edge)   │
 *      ▼                         │
 *   ┌─────┐                  ┌───┴───┐
 *   │  A  ├─────────────────►│   B   │
 *   └─────┘                  └───────┘
 *
 *   Tarjan groups A and B into one SCC because of the back-edge B -> A.
 *   findMinimalDeferralSet picks B as the deferral candidate (its outgoing
 *   edge to A is deferrable and that single choice breaks the cycle).
 *   sortVerticesInSCC then ignores the soft edge B -> A and produces:
 *
 *       A, B    (A constructed first; B receives Provider<A>)
 *
 *   The deferred set ends up in `deferredTypes` so codegen knows to emit
 *   a DelegateFactory wrapper for B's view of A.
 * ```
 *
 * If a cycle has **no** deferrable edge, [onCycle] is invoked with the offending vertex list and
 * the caller decides how to surface it (typically a compilation error). If a cycle has deferrable
 * edges but no subset of deferring sources actually breaks it (rare, e.g., multiple interleaved
 * cycles), [onCycle] is also invoked.
 *
 * @param fullAdjacency outgoing‑edge map (every vertex key must be present)
 * @param isDeferrable predicate for "edge may break a cycle"
 * @param onCycle called with the offending cycle if no deferrable edge
 * @param roots optional set of source roots for computing reachability. If null, all keys will be
 *   kept.
 * @param onSortedCycle optional callback reporting (sorted) cycles.
 * @param useSecondaryTopoSort Controls how the **component-level** ordering is produced.
 *     - `true` (default): build the component DAG and run a Kahn topological sort over it. Ties
 *       among simultaneously-ready components are broken by lowest component id (via
 *       `PriorityQueue<Int>`). The component DAG is exposed in the result.
 *     - `false`: skip the DAG build and Kahn's pass. Tarjan emits components in finish order (id 0
 *       = first-popped = a leaf with no outgoing dep edges, i.e. depends on nothing), which in this
 *       codebase's adjacency orientation IS the desired init order — prereqs first, dependents
 *       last. Tie-breaking is by DFS finish order, but **since Kahn assigns its tie-break by
 *       component id and Tarjan ids ARE finish-order, both paths produce the same order**. The
 *       component DAG isn't built — `componentDag` in the result is empty.
 *
 *   Both paths produce **the same `sortedKeys` order** in practice. The `false` path is purely a
 *   cheaper way to compute it for graphs that don't need the materialised `componentDag`. If you
 *   need `GraphTopology.componentDag` populated for downstream analysis, leave this `true`.
 *
 *   Note that the **vertex-level** Kahn pass inside [sortVerticesInSCC] always runs regardless of
 *   this flag — that's a different sort, ordering vertices within a single SCC by their hard
 *   intra-SCC edges.
 */
context(traceScope: TraceScope)
internal fun <V : Comparable<V>> metroSort(
  fullAdjacency: SortedMap<V, SortedSet<V>>,
  isDeferrable: (from: V, to: V) -> Boolean,
  onCycle: (List<V>) -> Unit,
  roots: SortedSet<V>? = null,
  isImplicitlyDeferrable: (V) -> Boolean = { false },
  onSortedCycle: (List<V>) -> Unit = {},
  useSecondaryTopoSort: Boolean = true,
): GraphTopology<V> {
  val deferredTypes = HashSet<V>()

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

  val componentDag: IntObjectMap<IntSet>
  val componentOrder: IntList
  if (useSecondaryTopoSort) {
    // Build the component DAG with edges reversed for Kahn (dependent → prereqs) and run a
    // priority-queued Kahn topological sort over it. Stable across builds: ties are broken by
    // lowest component id.
    componentDag =
      trace("Build component DAG") {
        buildComponentDag(reachableAdjacency.forward, componentOf, components.size)
      }
    componentOrder =
      trace("Topo sort component DAG") {
        topologicallySortComponentDag(componentDag, components.size)
      }
  } else {
    // Tarjan finish-order matches Kahn-with-id-priority output (see KDoc); skip the DAG build.
    componentDag = emptyIntObjectMap()
    componentOrder =
      trace("Component order from Tarjan finish order") {
        MutableIntList(components.size).apply {
          for (id in components.indices) {
            add(id)
          }
        }
      }
  }

  val sortedKeys =
    trace("Expand components") {
      expandComponents(
        componentOrder = componentOrder,
        components = components,
        forwardAdjacency = reachableAdjacency.forward,
        deferredTypes = deferredTypes,
        isDeferrable = isDeferrable,
        onSortedCycle = onSortedCycle,
        expectedSize = fullAdjacency.size,
      )
    }

  return GraphTopology(
    sortedKeys,
    deferredTypes,
    reachableAdjacency.forward.keys,
    reachableAdjacency,
    components,
    componentOf,
    componentDag,
  )
}

/**
 * Picks the smallest set of vertices in this SCC that, when their deferrable outgoing edges are
 * cut, leaves the remaining graph acyclic.
 *
 * "Minimal" matters because every deferred vertex becomes a `DelegateFactory` in generated code:
 * fewer deferred sources = fewer wrappers = cleaner output and less indirection at runtime.
 *
 * ### Strategy
 *
 * 1. Walk the SCC and build:
 *     - `sccAdjacency` — edges that stay inside the SCC (cross-SCC edges can't contribute to its
 *       internal cycle).
 *     - `deferrableEdgesFrom` — for each vertex, the set of its outgoing deferrable edges.
 *     - `potentialCandidates` — every vertex that has at least one outgoing deferrable edge. Only
 *       these can break the cycle, since deferring a vertex with no deferrable outgoing edge is a
 *       no-op.
 * 2. If there are no candidates, the cycle has no soft edges -> return empty (caller treats this as
 *    a hard cycle).
 * 3. Try each candidate **alone**, in priority order. The first one whose deferral makes the SCC
 *    acyclic wins. Single-vertex deferral is the common case (one `Provider<T>` typically breaks a
 *    2-cycle).
 * 4. If no individual candidate works, try **all candidates together** as a last resort. Some
 *    interleaved cycles need multiple soft cuts.
 * 5. If even that fails, return empty (the available deferrable edges aren't enough to break the
 *    cycle).
 *
 * Candidate priority: implicitly deferrable vertices (e.g. `@AssistedFactory`s, which the user
 * already marked as constructed-on-demand) come first, then natural order. This keeps generated
 * code more stable and prefers obvious-on-the-source choices over arbitrary picks.
 *
 * Cycle-checking is done via [ReusableCycleChecker], which masks the deferred edges during a
 * standard recursive DFS instead of materializing a new adjacency map for each candidate test.
 *
 * Example: `A <-> B` with `B -> A` deferrable (`Provider<A>`):
 * ```
 *   sccAdjacency:        A -> B,  B -> A
 *   deferrableEdgesFrom: B -> {A}
 *   candidates:          {B}
 *
 *   Try {B}: mask B -> A, remaining edge is A -> B -> acyclic. Return {B}.
 * ```
 */
private fun <V : Comparable<V>> findMinimalDeferralSet(
  vertices: List<V>,
  fullAdjacency: SortedMap<V, SortedSet<V>>,
  componentOf: ObjectIntMap<V>,
  componentId: Int,
  isDeferrable: (V, V) -> Boolean,
  isImplicitlyDeferrable: (V) -> Boolean,
): Set<V> {
  // Build the SCC-internal adjacency and deferrable edges ONCE upfront
  // This avoids rebuilding these structures for each candidate test.
  // Each collection here is bounded by the SCC's vertex count, so size them up front.
  val cap = calculateInitialCapacity(vertices.size)
  val sccAdjacency = HashMap<V, MutableSet<V>>(cap)
  val deferrableEdgesFrom = HashMap<V, MutableSet<V>>(cap)
  val potentialCandidates = HashSet<V>(cap)

  for (from in vertices) {
    // Targets bounded by `from`'s outgoing degree.
    val targets = LinkedHashSet<V>(calculateInitialCapacity(fullAdjacency[from]?.size ?: 0))
    for (to in fullAdjacency[from].orEmpty()) {
      // Only consider edges that stay inside the SCC
      if (componentOf[to] == componentId) {
        targets.add(to)
        if (isDeferrable(from, to)) {
          // Track deferrable edges and candidates
          deferrableEdgesFrom.getOrPut(from) { HashSet() }.add(to)
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
  // Reuse these sets across checks to reduce allocations.
  private val visited: HashSet<V>
  private val inStack: HashSet<V>

  init {
    // Sized to the full SCC since the worst case is "every vertex visited."
    val cap = calculateInitialCapacity(vertices.size)
    visited = HashSet(cap)
    inStack = HashSet(cap)
  }

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
 * Expands the topologically sorted list of component IDs back into a flat list of original
 * vertices.
 *
 * For each component (in dependency-respecting order):
 * - If the component has a single vertex (a non-cycle node, or a cycle of size 1 from a self-loop),
 *   append it directly.
 * - If the component has multiple vertices (i.e., it's a cycle), call [sortVerticesInSCC] to order
 *   the cycle's vertices internally, ignoring the deferrable edges from the chosen deferred
 *   sources. Notify the caller via [onSortedCycle] so they can record the cycle for diagnostics or
 *   codegen.
 *
 * Visual:
 * ```
 *   componentOrder:   [c0, c1, c2]      (Kahn's output: c0 has no deps, c2 depends on c0/c1)
 *   components[c0]:   {X}               (single vertex)
 *   components[c1]:   {A, B}            (cycle, B -> A is deferrable; A first then B)
 *   components[c2]:   {Y}               (single vertex)
 *
 *   expanded:         [X, A, B, Y]
 * ```
 *
 * @param componentOrder the topologically-sorted list of component IDs from
 *   [topologicallySortComponentDag].
 * @param components vertices grouped by component ID (from [computeStronglyConnectedComponents]).
 * @param forwardAdjacency the reachable forward adjacency, used by [sortVerticesInSCC] to walk
 *   intra-SCC dependencies.
 * @param deferredTypes the set of vertices the caller has elected to defer (sources of soft edges).
 * @param isDeferrable predicate matching [metroSort]'s parameter.
 * @param onSortedCycle invoked once per multi-vertex SCC with the sorted cycle vertices.
 * @param expectedSize total vertex count (used to pre-size the result list).
 */
private fun <V : Comparable<V>> expandComponents(
  componentOrder: IntList,
  components: List<Component<V>>,
  forwardAdjacency: SortedMap<V, SortedSet<V>>,
  deferredTypes: Set<V>,
  isDeferrable: (V, V) -> Boolean,
  onSortedCycle: (List<V>) -> Unit,
  expectedSize: Int,
): List<V> {
  val sortedKeys = ArrayList<V>(expectedSize)
  componentOrder.forEach { id ->
    val component = components[id]
    if (component.vertices.size == 1) {
      // Single vertex (no cycle, or cycle-of-one from a self-loop). Emit it directly.
      sortedKeys += component.vertices[0]
    } else {
      // Multi-vertex SCC: sort the cycle internally, ignoring soft edges from deferred sources.
      val deferredInScc = component.vertices.filterToSet { it in deferredTypes }
      sortedKeys +=
        sortVerticesInSCC(component.vertices, forwardAdjacency, isDeferrable, deferredInScc).also {
          onSortedCycle(it)
        }
    }
  }
  return sortedKeys
}

/**
 * Orders the vertices **inside a single SCC** (a cycle) into a sensible linear order, ignoring the
 * deferrable edges that will be cut at codegen time.
 *
 * Within an SCC we classify each edge as either **hard** (non-deferrable, or deferrable but the
 * source isn't in [deferredInScc]) or **soft** (deferrable + source is deferred). Soft edges are
 * the ones a `DelegateFactory` will paper over at runtime, so we pretend they don't exist when
 * ordering — the remaining hard edges form a DAG inside the SCC, which we Kahn-sort.
 *
 * Example: `A <-> B` cycle, B -> A is deferrable, deferredInScc = {B}.
 *
 * ```
 *   Edges (inside SCC):
 *     A -> B   (hard: forward edge, A constructs B)
 *     B -> A   (soft: deferrable AND B is deferred -> ignored)
 *
 *   Hard DAG inside SCC:    A -> B
 *   Kahn output:            [A, B]
 *
 *   Generated code:
 *     A is built normally
 *     B is built with Provider<A> wrapping A's eventual instance
 * ```
 *
 * The PriorityQueue tie-breaks the "ready" set to keep emission stable and codegen-friendly:
 * 1. Deferred nodes first (they emit a `DelegateFactory` that their dependents need to wrap).
 * 2. Then more hard dependents (popping high-fanout nodes earliest unblocks the most work).
 * 3. Then natural order for determinism.
 *
 * @throws IllegalStateException if a hard cycle remains after removing soft edges (shouldn't happen
 *   — [findMinimalDeferralSet] is supposed to have proved acyclicity).
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
  val isSoftEdge: (from: V, to: V) -> Boolean = { from, to ->
    isDeferrable(from, to) && from in deferredInScc
  }

  // v -> hard prereqs (non-soft edges). Bounded by SCC size.
  val cap = calculateInitialCapacity(vertices.size)
  val hardDeps = HashMap<V, MutableSet<V>>(cap)
  // prereq -> dependents (via hard edges). Bounded by SCC size.
  val revHard = HashMap<V, MutableSet<V>>(cap)

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

  val hardIn = vertices.associateWithTo(HashMap(cap)) { hardDeps[it]?.size ?: 0 }

  // Sort ready by:
  // 1 - nodes that are in deferredInScc (i.e., emit DelegateFactory before its users)
  // 2 - more hard dependents (unlocks more)
  // 3 - natural order for determinism
  // The ready set holds at most every vertex in the SCC; size up front.
  val ready =
    PriorityQueue<V>(vertices.size) { a, b ->
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
 * An SCC is a maximal subset of vertices in which every vertex can reach every other vertex **and
 * is reachable from every other vertex** (mutual reachability — one-way reachability is not
 * enough). A cycle is a single SCC containing every vertex on it; vertices that can be reached but
 * cannot reach back form their own size-1 SCCs.
 *
 * Example: five vertices forming one cycle plus one terminal vertex ->
 *
 * ```
 *           ┌────────────────────── back-edge ─────────────┐
 *           │                                              │
 *           ▼                                              │
 *       ┌───────┐    ┌───────┐    ┌───────┐    ┌───────┐   │
 *       │   A   ├───►│   B   ├───►│   D   ├───►│   E   │   │
 *       └───────┘    └───────┘    └───┬───┘    └───────┘   │
 *                                     │                    │
 *                                     ▼                    │
 *                                 ┌───────┐                │
 *                                 │   C   ├────────────────┘
 *                                 └───────┘
 *
 *   SCCs:  {A, B, C, D}    cycle  A -> B -> D -> C -> A
 *          {E}             reachable from the cycle but cannot reach back (no outgoing edge),
 *                          so E is its own component despite being reachable from A
 * ```
 *
 * Components are returned in **reverse topological order** (a property of Tarjan's): the first
 * popped SCC is a sink (depends on no later SCC), the last is a source. Callers wanting forward
 * topological order reverse the list (see how [topologicallySortComponentDag] is used).
 *
 * ### How it works
 *
 * Tarjan does a single DFS, marking each vertex with two integers:
 * - `index[v]` — the order in which v was first visited (0, 1, 2, …).
 * - `lowlink[v]` — the smallest `index` reachable from v's DFS subtree **without leaving the
 *   current DFS path**.
 *
 * `lowlink[v] == index[v]` precisely when v is the root of an SCC: no descendant could reach an
 * ancestor of v, so everything sitting above v on the SCC stack belongs to v's component.
 *
 * Two stacks are needed:
 * - **DFS stack** — the current call path (`callStack` here, with parallel `edgeCursor` for "which
 *   outgoing edge of v are we up to"). Pops as the DFS unwinds.
 * - **SCC stack** — vertices visited but **not yet assigned to a component**, in DFS-discovery
 *   order (`dfsStack`). When an SCC root finalises, we pop everything down to and including that
 *   root from this stack — those popped vertices form the SCC.
 *
 * The two stacks differ because the SCC stack can hold a long chain across many backtracked DFS
 * frames. In the example above, when we finish processing C's only outgoing edge (`C -> A`), C
 * stays on the SCC stack — C still has no SCC assigned. Only when we finally backtrack all the way
 * to A and find `lowlink[A] == index[A]` do we pop C, D, B, A together as one SCC.
 *
 * Walk-through of the example (edges visited in sorted order):
 * ```
 *  step  action                           index   lowlink  dfsStack          popped
 *  ───────────────────────────────────────────────────────────────────────────────────
 *  1     visit A                          A=0     A=0      [A]
 *  2     A->B  visit B                     B=1     B=1      [A,B]
 *  3     B->D  visit D                     D=2     D=2      [A,B,D]
 *  4     D->C  visit C                     C=3     C=3      [A,B,D,C]
 *  5     C->A  (A on stack; back-edge)             C=0      [A,B,D,C]
 *  6     C done; lowlink≠index, don't pop
 *        D inherits lowlink from C                D=0      [A,B,D,C]
 *  7     D->E  visit E                     E=4     E=4      [A,B,D,C,E]
 *  8     E done; lowlink==index -> pop                      [A,B,D,C]         {E}
 *  9     D done; lowlink≠index, don't pop
 *  10    B inherits                               B=0      [A,B,D,C]
 *  11    A inherits                               A=0      [A,B,D,C]
 *  12    A done; lowlink==index -> pop                      []                {C,D,B,A}
 * ```
 *
 * Result: components = [{E}, {C, D, B, A}] (reverse-topo: sink first, source last).
 *
 * ### Implementation notes
 *
 * Standard Tarjan is recursive. For deep dependency chains (the 1000-module benchmark hits 1000+
 * levels) recursion overflows the JVM stack, so we run an explicit iterative DFS using the
 * `(callStack, edgeCursor)` pair where each frame holds `(v, current edge index)`.
 *
 * Vertices are mapped to dense int ids in sorted order. Adjacency, visited flags, and stacks become
 * primitive `IntArray` / `BooleanArray` / `MutableIntList`s. This is cheaper than per-edge
 * `IrTypeKey.compareTo` / `equals` work that Metro previously used, which then dominated the inner
 * loop.
 *
 * NOTE: The receiver adjacency (both keys and each value set) is assumed to be already sorted. This
 * preserves determinism of the int-id mapping and of the per-vertex edge ordering, so a given input
 * always produces the same output.
 *
 * @param roots Optional set of source roots to walk from. If null, every key in the receiver is
 *   walked (the entire graph is reachable). If provided, only vertices reachable from these roots
 *   are visited; the rest stay [UNVISITED] and are filtered out of
 *   [TarjanResult.reachableAdjacency].
 * @return The list of components (in reverse topological order), the vertex->component-id map, and
 *   the reachable forward/reverse adjacency over visited vertices only.
 * @receiver A directed graph as `vertex -> outgoing edges`. Both the key set and each edge set must
 *   be sorted.
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
    for ((i, key) in keys.withIndex()) {
      idToVertex[i] = key
      vertexToId[key] = i
    }
  }

  val vertexAt: (id: Int) -> V = { id ->
    @Suppress("UNCHECKED_CAST")
    idToVertex[id] as V
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
  // indexOfId[v] is v's DFS discovery time (analogous to "v.index" in the classic algorithm).
  // lowLinkOfId[v] is the lowest discovery index v can reach without leaving the current DFS
  // stack (analogous to "v.lowlink").
  // onStack[v] tracks whether v is currently on `dfsStack` (the SCC-membership stack).
  // componentOfId[v] is filled with the SCC id once v has been assigned to a component.
  // dfsStack holds vertices of the current DFS branch.
  val indexOfId = IntArray(n) { UNVISITED }
  val lowLinkOfId = IntArray(n)
  val onStack = BooleanArray(n)
  val componentOfId = IntArray(n) { UNVISITED }
  val dfsStack = MutableIntList(n)

  var nextIndex = 0
  var nextComponentId = 0
  // Materialised SCC vertex lists (in int form) keyed by component id.
  // At most one component per vertex (degenerate case where every vertex is its own SCC).
  val componentMembers = ArrayList<MutableIntList>(n)

  // Iterative Tarjan: a recursive `strongConnect(v)` would blow the JVM stack on deep dependency
  // chains, so we fold the recursion into an explicit (callStack, edgeCursor) pair where each
  // frame is `(v, current edge index)`.
  val callStack = MutableIntList(n)
  val edgeCursor = MutableIntList(n)

  val visit: (start: Int) -> Unit = visit@{ start ->
    if (indexOfId[start] != UNVISITED) return@visit
    // Set the depth index for `start` to the smallest unused index and push the first frame.
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
          // Successor w has not yet been visited; "recurse" on it by pushing a new frame.
          indexOfId[w] = nextIndex
          lowLinkOfId[w] = nextIndex
          nextIndex++
          dfsStack.add(w)
          onStack[w] = true
          callStack.add(w)
          edgeCursor.add(0)
        } else if (onStack[w]) {
          // Successor w is on the SCC stack and hence in the current SCC. If w is not on the
          // stack, then (v, w) points to an SCC already finalised and must be ignored.
          if (indexOfId[w] < lowLinkOfId[v]) {
            lowLinkOfId[v] = indexOfId[w]
          }
        }
        continue
      }

      // All edges of v processed. If v is an SCC root (lowlink == index), pop everything down to
      // and including v from the SCC stack and materialise it as a component.
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

  // ── Build the reachable adjacency in V-space ──
  //
  // The original receiver adjacency may include vertices that the DFS never reached (they sit
  // outside the chosen `roots`). The contract here is to return a `SortedMap<V, SortedSet<V>>`
  // covering ONLY the vertices the DFS touched, with edges to UNVISITED targets filtered out.
  //
  // Build into HashMap/HashSet (O(1) inserts, sized to known counts) and convert to the sorted
  // contract in a single bulk pass at the end. For dense graphs (~6500 edges per vertex on the
  // benchmark) per-edge TreeSet inserts (O(log m) each) added up; HashSet -> toSortedSet() does
  // the sort once per row with much smaller per-edge constants.
  val rawForward = HashMap<V, HashSet<V>>(n)
  val reachableReverse = HashMap<V, MutableSet<V>>(n)
  for (id in 0 until n) {
    // Skip vertices the DFS never visited — they're not part of the reachable subgraph.
    if (indexOfId[id] == UNVISITED) continue
    val vertex = vertexAt(id)
    val edges = adj[id]!!
    if (edges.isEmpty()) {
      // Vertex has no outgoing edges. Record an empty set so callers that index into the result
      // (e.g. `forward[v]`) see a present-but-empty entry rather than null.
      rawForward[vertex] = HashSet(0)
      continue
    }
    val reachableEdges = HashSet<V>(edges.size)
    for (toId in edges) {
      // Filter out edges that leave the reachable subgraph: if the DFS didn't reach `toId`,
      // it's not part of the result and any edge into it is dropped.
      if (indexOfId[toId] == UNVISITED) continue
      val toVertex = vertexAt(toId)
      reachableEdges.add(toVertex)
      // Mirror the edge into the reverse adjacency so consumers can walk dependents.
      reachableReverse.getAndAdd(toVertex, vertex)
    }
    rawForward[vertex] = reachableEdges
  }

  // Convert HashMap<V, HashSet<V>> -> SortedMap<V, SortedSet<V>>. The TreeMap inserts here are
  // O(log(n)) but they happen once per vertex (not once per edge); per-row sorts via
  // `toSortedSet()` are O(mlog(m)) in bulk.
  val reachableForward = sortedMapOf<V, SortedSet<V>>()
  for ((vertex, set) in rawForward) {
    reachableForward[vertex] = if (set.isEmpty()) emptySortedSet() else set.toSortedSet()
  }

  return TarjanResult(components, componentOf, GraphAdjacency(reachableForward, reachableReverse))
}

private const val UNVISITED = -1
private val EMPTY_INT_ARRAY = IntArray(0)

/**
 * Collapses the per-vertex graph into a DAG over component IDs. Each SCC becomes a single node;
 * edges that cross between SCCs become edges in the DAG.
 *
 * **Edge direction is reversed** from the original graph. The original edges read "dependent ->
 * prerequisite" (a binding's outgoing edges point to what it needs). Kahn's topological sort works
 * in the opposite direction: we want to process prerequisites first, then nodes whose inputs are
 * all ready. So for every original edge `from -> to`, we record an edge in the DAG `componentOf[to]
 * -> componentOf[from]` (read as "prereq -> dependent"). Self-edges within an SCC are dropped since
 * they collapse onto the same component.
 *
 * Example:
 * ```
 *   Original (vertex graph):
 *     X -> A      A and B form an SCC (cycle A -> B -> A);
 *     A -> B      X is its own SCC; Y is its own SCC;
 *     B -> A      Y depends on B (so on the SCC).
 *     B -> Y
 *
 *   Components: c0={X}, c1={A,B}, c2={Y}
 *
 *   Cross-SCC edges in the original graph:
 *     X -> A     ->   c0 -> c1
 *     B -> Y     ->   c1 -> c2
 *     (A -> B and B -> A stay inside c1; dropped)
 *
 *   DAG returned (edges reversed for Kahn's):
 *     c1 ← c0           reads as "c0 must come before c1"
 *     c2 ← c1           reads as "c1 must come before c2"
 *
 *   So result map looks like:
 *     c1 -> {c0}
 *     c2 -> {c1}
 * ```
 *
 * @param originalEdges per-vertex outgoing edges of the input graph.
 * @param componentOf vertex -> SCC id, from [computeStronglyConnectedComponents].
 * @param componentCount total number of components (used to pre-size the result map).
 * @return A map keyed by SCC id whose values are the set of SCC ids that must run **before** the
 *   key (i.e., prerequisites, in Kahn-compatible orientation).
 */
private fun <V> buildComponentDag(
  originalEdges: Map<V, Set<V>>,
  componentOf: ObjectIntMap<V>,
  componentCount: Int,
): IntObjectMap<IntSet> {
  // At most one entry per component.
  val dag = MutableIntObjectMap<MutableIntSet>(calculateInitialCapacity(componentCount, 7f / 8))

  for ((fromVertex, outs) in originalEdges) {
    // prerequisite side
    val prereqComp = componentOf[fromVertex]
    for (toVertex in outs) {
      // dependent side
      val dependentComp = componentOf[toVertex]
      if (prereqComp != dependentComp) {
        // Reverse the arrow so Kahn sees "prereq -> dependent"
        dag.getOrPut(dependentComp, ::MutableIntSet).add(prereqComp)
      }
    }
  }
  @Suppress("UNCHECKED_CAST") // TODO why
  return dag as IntObjectMap<IntSet>
}

/**
 * Topologically sorts the component DAG using Kahn's algorithm.
 *
 * At a glance:
 * 1. Compute the in-degree of every node (how many prereqs point at it).
 * 2. Seed a worklist with every node whose in-degree is 0 (no prereqs).
 * 3. Pop a node from the worklist, append it to the output, and decrement the in-degree of each of
 *    its dependents. Any dependent that drops to in-degree 0 is now ready and joins the worklist.
 * 4. Repeat until the worklist drains. If fewer nodes were emitted than exist, a cycle was present
 *    (impossible here since SCC collapse already removed cycles).
 *
 * Example using the DAG from [buildComponentDag] (`c1 -> {c0}`, `c2 -> {c1}`):
 * ```
 *   in-degrees:  c0=0, c1=1, c2=1
 *
 *   step 1: worklist = [c0]                emit c0, decrement c1 -> 0; worklist = [c1]
 *   step 2: worklist = [c1]                emit c1, decrement c2 -> 0; worklist = [c2]
 *   step 3: worklist = [c2]                emit c2;                   worklist = []
 *
 *   order:    [c0, c1, c2]
 * ```
 *
 * Note the [PriorityQueue] is intentionally keyed by component id. After processing a node,
 * multiple dependents may become ready at the same time; ordering them by id keeps the output
 * stable across builds. From the doc on the queue below:
 * ```
 *   (0)──▶(2)
 *    │
 *    └───▶(1)
 *
 *   After processing 0, both 1 and 2 are ready. A FIFO ArrayDeque would emit them in whatever
 *   order Kahn happens to find them; PriorityQueue dequeues the lowest id first (1 before 2),
 *   keeping generated code stable.
 * ```
 *
 * @param dag prereq-oriented DAG from [buildComponentDag]: keys are dependent components, values
 *   are the prerequisite components they depend on.
 * @param componentCount total number of components (also the upper bound on node ids).
 * @return Component ids in topological order (prereqs first, dependents last).
 * @throws IllegalStateException if a cycle remains. Should be impossible after SCC collapse.
 * @see <a href="https://en.wikipedia.org/wiki/Topological_sorting">Topological sorting</a>
 * @see <a href="https://www.interviewcake.com/concept/java/topological-sort">Topological sort</a>
 */
private fun topologicallySortComponentDag(dag: IntObjectMap<IntSet>, componentCount: Int): IntList {
  val inDegree = IntArray(componentCount)
  // Avoid temporary list allocation from flatten()
  dag.forEachValue { children -> children.forEach { child -> inDegree[child]++ } }

  // PriorityQueue (not FIFO) so multiple ready nodes are emitted in id order — see the function
  // KDoc above for why determinism matters here. Holds at most every component at once.
  val queue =
    PriorityQueue<Int>(componentCount.coerceAtLeast(1)).apply {
      // Seed with every component whose in-degree is 0 (no prerequisites).
      for (id in 0 until componentCount) {
        if (inDegree[id] == 0) {
          add(id)
        }
      }
    }

  // We emit exactly `componentCount` ids in the success path.
  val order = MutableIntList(componentCount)
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
