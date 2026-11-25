// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.MutableIntList
import androidx.collection.MutableObjectIntMap
import androidx.collection.ObjectIntMap
import androidx.collection.ScatterMap
import dev.zacsweers.metro.compiler.calculateInitialCapacity
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.BitSet
import java.util.PriorityQueue
import java.util.SortedMap
import java.util.SortedSet
import java.util.TreeMap

/**
 * @param sortedKeys Topologically sorted list of keys.
 * @param deferredTypes Vertices whose outgoing edges were selected to break cycles.
 * @param reachableKeys Vertices that were deemed reachable by any input roots.
 * @param adjacency The reachable adjacency (forward and reverse) used for topological sorting.
 * @param components The strongly connected components computed during sorting.
 * @param componentOf Mapping from vertex to component ID.
 */
public data class GraphTopology<T>(
  val sortedKeys: List<T>,
  val deferredTypes: Set<T>,
  val reachableKeys: Set<T>,
  val adjacency: GraphAdjacency<T>,
  val components: List<Component<T>>,
  val componentOf: ObjectIntMap<T>,
)

/**
 * Result of building adjacency maps, containing both forward and reverse mappings.
 *
 * @property forward Maps each vertex to its dependencies (outgoing edges).
 * @property reverse Maps each vertex to its dependents (incoming edges).
 */
public data class GraphAdjacency<T>(
  val forward: SortedMap<T, SortedSet<T>>,
  val reverse: Map<T, Set<T>>,
)

/**
 * A prepared graph for [analyze]. Vertex ids follow sorted key order. Nothing here can reference a
 * compiler object, so any thread can analyze it.
 *
 * @property rowStarts Where each vertex's edges start in [targets]. Its size is `vertexCount + 1`.
 * @property targets Edge targets. Each row is sorted.
 * @property deferredEdges Edges that may break a cycle. Indexed like [targets].
 * @property implicitlyDeferrable Vertices that are preferred when choosing deferrals.
 * @property roots Sorted vertices to walk from.
 */
public class AnalysisInput(
  public val vertexCount: Int,
  public val rowStarts: IntArray,
  public val targets: IntArray,
  public val deferredEdges: BitSet,
  public val implicitlyDeferrable: BitSet,
  public val roots: IntArray,
)

/**
 * The result of [analyze], indexed like its [AnalysisInput].
 *
 * @property order Reachable vertices with dependencies first. Empty when [hardCycle] is set.
 * @property deferred Vertices whose deferrable edges were selected to break cycles.
 * @property softCycles Each cycle that deferral broke, in [order]'s order.
 * @property hardCycle One cycle of eager edges, or null when deferral breaks every cycle.
 * @property hardComponent The component that contains [hardCycle].
 * @property componentStarts Where each component starts in [componentMembers]. Components are in
 *   Tarjan's finish order.
 * @property componentMembers Reachable vertices grouped by component, in Tarjan's pop order.
 */
public class AnalysisOutput(
  public val order: IntArray,
  public val deferred: BitSet,
  public val softCycles: List<IntArray>,
  public val hardCycle: IntArray?,
  public val hardComponent: IntArray?,
  public val componentStarts: IntArray,
  public val componentMembers: IntArray,
)

/**
 * Returns the reachable vertices in dependency order after deferring edges to break cycles.
 * [onCycle] reports cycles that remain after every deferrable edge is removed.
 *
 * This adapts key-based callers to [analyze]. Ids and callbacks run on the calling thread before
 * analysis, and results are mapped back to keys afterward.
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
 * │  └─────────────────┘│
 * └─────────────────────┘
 *      │
 *      ▼
 * ┌──────────────────────┐
 * │  Phase 2: Expand     │
 * │  ┌──────────────────┐│
 * │  │ Components -> Vs ││  ◄─── Tarjan finish order
 * │  └──────────────────┘│
 * └──────────────────────┘
 *      │
 *      ▼
 * GraphTopology
 * ├─ sortedKeys (dependency order)
 * └─ deferredTypes (sources of deferred edges)
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
 *       B, A    (B receives Provider<A>, so A can depend on B)
 *
 *   The deferred set ends up in `deferredTypes` so codegen knows to emit
 *   a DelegateFactory<B> and set its delegate once the provider for A is ready.
 * ```
 *
 * If a cycle has **no** deferrable edge, [onCycle] is invoked with the offending component's
 * vertices and the caller decides how to surface it (typically a compilation error). If a cycle has
 * deferrable edges but no subset of deferring sources actually breaks it (rare, e.g., multiple
 * interleaved cycles), [onCycle] is also invoked. Only the first such component is reported. If
 * [onCycle] returns, this throws [IllegalStateException].
 *
 * @param fullAdjacency outgoing‑edge map (every vertex key must be present)
 * @param isDeferrable predicate for "edge may break a cycle". Called once per edge.
 * @param onCycle called with the offending cycle if deferring edges cannot break it
 * @param roots optional set of source roots for computing reachability. If null, all keys will be
 *   kept.
 * @param isImplicitlyDeferrable vertices to prefer when choosing deferrals. Called once per vertex.
 * @param ensureActive cancellation callback polled throughout sorting. Defaults to no checks.
 * @param onSortedCycle optional callback reporting (sorted) cycles.
 */
context(traceScope: TraceScope)
public fun <V : Comparable<V>> metroSort(
  fullAdjacency: SortedMap<V, SortedSet<V>>,
  isDeferrable: (from: V, to: V) -> Boolean,
  onCycle: (List<V>) -> Unit,
  roots: SortedSet<V>? = null,
  isImplicitlyDeferrable: (V) -> Boolean = { false },
  ensureActive: () -> Unit = {},
  onSortedCycle: (List<V>) -> Unit = {},
): GraphTopology<V> {
  val indexed = fullAdjacency.indexed(ensureActive)
  val input =
    AnalysisInput(
      vertexCount = indexed.vertexCount,
      rowStarts = indexed.rowStarts,
      targets = indexed.targets,
      deferredEdges = indexed.deferredEdges(isDeferrable, ensureActive),
      implicitlyDeferrable = indexed.matchingVertices(isImplicitlyDeferrable, ensureActive),
      roots = indexed.rootIds(roots, ensureActive),
    )
  val output = analyze(input, ensureActive)

  val hardComponent = output.hardComponent
  if (hardComponent != null) {
    onCycle(indexed.keysOf(hardComponent))
    // An unbroken component can't be ordered.
    error("Hard cycle remained inside SCC after removing selected soft edges")
  }

  for (cycle in output.softCycles) {
    ensureActive()
    onSortedCycle(indexed.keysOf(cycle))
  }
  return indexed.topologyOf(output, ensureActive)
}

/**
 * Computes the components, deferrals, and order of a prepared graph. It reads only [input] and its
 * own local state, and only calls [ensureActive] and trace callbacks.
 *
 * Tie-breaks compare vertex ids. Ids follow sorted key order, so results match a key-based sort.
 *
 * @see metroSort
 */
context(traceScope: TraceScope)
public fun analyze(input: AnalysisInput, ensureActive: () -> Unit = {}): AnalysisOutput {
  // Collapse the graph into strongly‑connected components
  val components = trace("Compute SCCs") { findComponents(input, ensureActive) }

  val deferred = BitSet(input.vertexCount)
  var hardComponent: IntArray? = null
  var hardCycle: IntArray? = null

  // Check for cycles
  trace("Check for cycles") {
    for (componentId in 0 until components.count) {
      ensureActive()
      val start = components.starts[componentId]
      val end = components.starts[componentId + 1]

      if (end - start == 1) {
        val vertex = components.members[start]
        var isSelfLoop = false
        for (edge in input.rowStarts[vertex] until input.rowStarts[vertex + 1]) {
          ensureActive()
          if (input.targets[edge] == vertex) {
            isSelfLoop = true
            break
          }
        }
        if (!isSelfLoop) {
          // trivial acyclic
          continue
        }
      }

      // Look for cycles - find minimal set of nodes to defer
      val contributorsToCycle =
        findMinimalDeferralSet(
          input = input,
          components = components,
          componentId = componentId,
          ensureActive = ensureActive,
        )

      if (contributorsToCycle.isEmpty()) {
        // no deferrable -> hard cycle
        val vertices = components.members.copyOfRange(start, end)
        hardComponent = vertices
        hardCycle = findHardCycle(input, components, componentId, ensureActive)
        break
      }

      for (vertex in contributorsToCycle) {
        deferred.set(vertex)
      }
    }
  }

  val softCycles = ArrayList<IntArray>()
  val order =
    if (hardCycle != null) {
      EMPTY_INT_ARRAY
    } else {
      // Tarjan emits components in finish order, which in turn is the topological ordering we
      // want
      trace("Expand components") {
        expandComponents(input, components, deferred, softCycles, ensureActive)
      }
    }

  return AnalysisOutput(
    order = order,
    deferred = deferred,
    softCycles = softCycles,
    hardCycle = hardCycle,
    hardComponent = hardComponent,
    componentStarts = components.starts,
    componentMembers = components.members,
  )
}

/**
 * Picks bindings to defer until this component has no remaining cycles. Every selected binding is
 * needed to keep those cycles broken.
 *
 * "Minimal" matters because every deferred vertex becomes a `DelegateFactory` in generated code:
 * fewer deferred sources = fewer wrappers = cleaner output and less indirection at runtime.
 *
 * ### Strategy
 *
 * 1. Walk the SCC and build:
 *     - `sccAdjacency`: edges that stay inside the SCC (cross-SCC edges can't contribute to its
 *       internal cycle).
 *     - `deferrableEdgesFrom`: for each vertex, the set of its outgoing deferrable edges.
 *     - `potentialCandidates`: every vertex that has at least one outgoing deferrable edge. Only
 *       these can break the cycle, since deferring a vertex with no deferrable outgoing edge is a
 *       no-op.
 * 2. If there are no candidates, the cycle has no soft edges -> return empty (caller treats this as
 *    a hard cycle).
 * 3. Try the first candidate alone. Return it if its deferral makes the SCC acyclic. This preserves
 *    the common case where one `Provider<T>` breaks a cycle.
 * 4. If the only candidate failed, return empty. Otherwise, try all candidates together. Return
 *    empty if a cycle remains because no combination of deferrals can break it.
 * 5. Try the remaining candidates alone in priority order. The first successful candidate wins.
 * 6. Remove candidates in reverse priority order while the remaining graph stays acyclic. This
 *    keeps preferred candidates and ensures that every remaining candidate is necessary.
 *
 * Candidate priority: implicitly deferrable vertices (e.g. `@AssistedFactory`s, which the user
 * already marked as constructed-on-demand) come first, then natural order. This keeps generated
 * code more stable and prefers obvious-on-the-source choices over arbitrary picks.
 *
 * Cycle-checking is done via [ReusableCycleChecker], which masks the deferred edges during an
 * iterative DFS instead of materializing a new adjacency map for each candidate test.
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
private fun findMinimalDeferralSet(
  input: AnalysisInput,
  components: Components,
  componentId: Int,
  ensureActive: () -> Unit,
): Set<Int> {
  val vertices = components.verticesOf(componentId)

  // Build the SCC-internal adjacency and deferrable edges ONCE upfront
  // This avoids rebuilding these structures for each candidate test.
  // Each collection here is bounded by the SCC's vertex count, so size them up front.
  val cap = calculateInitialCapacity(vertices.size)
  val sccAdjacency = HashMap<Int, MutableSet<Int>>(cap)
  val deferrableEdgesFrom = HashMap<Int, MutableSet<Int>>(cap)
  val potentialCandidates = HashSet<Int>(cap)

  vertices.forEachCancellable(ensureActive) { from ->
    val rowStart = input.rowStarts[from]
    val rowEnd = input.rowStarts[from + 1]
    // Targets bounded by `from`'s outgoing degree.
    val targets = LinkedHashSet<Int>(calculateInitialCapacity(rowEnd - rowStart))
    (rowStart until rowEnd).forEachCancellable(ensureActive) { edge ->
      val to = input.targets[edge]
      // Only consider edges that stay inside the SCC
      if (components.componentOf[to] == componentId) {
        targets.add(to)
        if (input.deferredEdges.get(edge)) {
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
  val cycleChecker = ReusableCycleChecker(vertices, sccAdjacency, deferrableEdgesFrom, ensureActive)

  // Two flavors of "deferrable" inform candidate priority:
  // - implicit (whole-node, e.g., @AssistedFactory, user already marked it on-demand)
  // - explicit (edge-only, source wraps the target in Provider/Lazy)
  //
  // Implicit wins ties so codegen prefers the user-visible choice.
  // A DeferralKind { WHOLE_NODE, EDGE, NONE } ordinal would express this more cleanly but isn't
  // worth the ripple through the rest of this logic.
  // Sort candidates once upfront instead of sorting in each loop.
  val sortedCandidates = ArrayList<Int>(potentialCandidates.size)
  potentialCandidates.forEachCancellable(ensureActive) { candidate ->
    sortedCandidates += candidate
  }
  val implicitlyDeferrable = input.implicitlyDeferrable
  sortedCandidates.sortWith { left, right ->
    ensureActive()
    val implicitPriority =
      (!implicitlyDeferrable.get(left)).compareTo(!implicitlyDeferrable.get(right))
    if (implicitPriority != 0) {
      implicitPriority
    } else {
      left.compareTo(right)
    }
  }

  val preferredDeferral = setOf(sortedCandidates.first())
  if (cycleChecker.isAcyclicWith(preferredDeferral)) {
    return preferredDeferral
  }
  if (sortedCandidates.size == 1) {
    return emptySet()
  }

  // A cycle that survives every available deferral makes further candidate checks unnecessary.
  if (!cycleChecker.isAcyclicWith(potentialCandidates)) {
    return emptySet()
  }

  for (index in 1..sortedCandidates.lastIndex) {
    ensureActive()
    val deferral = setOf(sortedCandidates[index])
    if (cycleChecker.isAcyclicWith(deferral)) {
      return deferral
    }
  }

  // The full candidate set is already known to keep the graph acyclic.
  // Try removing less preferred bindings first.
  for (index in sortedCandidates.lastIndex downTo 0) {
    ensureActive()
    // Every single candidate was already tested, so two remaining candidates are both necessary.
    if (potentialCandidates.size == 2) {
      break
    }

    val candidate = sortedCandidates[index]
    potentialCandidates.remove(candidate)
    if (!cycleChecker.isAcyclicAfterRestoringEdges(candidate, potentialCandidates)) {
      // This candidate is required to keep the remaining graph acyclic.
      potentialCandidates.add(candidate)
    }
  }

  return potentialCandidates
}

/** Searches the component's eager edges once and returns a cycle's vertices in traversal order. */
private fun findHardCycle(
  input: AnalysisInput,
  components: Components,
  componentId: Int,
  ensureActive: () -> Unit,
): IntArray {
  val vertices = components.verticesOf(componentId)
  val hardAdjacency = HashMap<Int, Set<Int>>(calculateInitialCapacity(vertices.size))
  for (vertex in vertices) {
    ensureActive()
    // Keep the row order so repeated analyses choose the same cycle.
    val hardNeighbors = LinkedHashSet<Int>()
    for (edge in input.rowStarts[vertex] until input.rowStarts[vertex + 1]) {
      ensureActive()
      val neighbor = input.targets[edge]
      val isInComponent = components.componentOf[neighbor] == componentId
      if (isInComponent && !input.deferredEdges.get(edge)) {
        hardNeighbors.add(neighbor)
      }
    }
    hardAdjacency[vertex] = hardNeighbors
  }

  val checker = ReusableCycleChecker(vertices, hardAdjacency, emptyMap(), ensureActive)
  val cycle = checker.findCycleWith(emptySet())
  if (cycle == null) {
    reportCompilerBug("Found an unbreakable component, but couldn't find a hard cycle.")
  }
  return cycle.toIntArray()
}

/**
 * Expands the topologically sorted list of components back into a flat list of original vertices.
 *
 * For each component (in dependency-respecting order):
 * - If the component has a single vertex (a non-cycle node, or a cycle of size 1 from a self-loop),
 *   append it directly.
 * - If the component has multiple vertices (i.e., it's a cycle), call [sortVerticesInSCC] to order
 *   the cycle's vertices internally, ignoring the deferrable edges from the chosen deferred
 *   sources. Record the sorted cycle in [softCycles] for diagnostics or codegen.
 *
 * Visual:
 * ```
 *   finish order:     [c0, c1, c2]     (Tarjan's output: c0 has no deps, c2 depends on c0/c1)
 *   components[c0]:   {X}               (single vertex)
 *   components[c1]:   {A, B}            (cycle, B -> A is deferrable; B first then A)
 *   components[c2]:   {Y}               (single vertex)
 *
 *   expanded:         [X, B, A, Y]
 * ```
 *
 * @param components vertices grouped by component ID (from [findComponents]) in Tarjan's finish
 *   order (prereqs first).
 * @param deferred the vertices selected for deferral (sources of soft edges).
 * @param softCycles receives each multi-vertex SCC's sorted vertices.
 */
private fun expandComponents(
  input: AnalysisInput,
  components: Components,
  deferred: BitSet,
  softCycles: MutableList<IntArray>,
  ensureActive: () -> Unit,
): IntArray {
  val order = IntArray(components.members.size)
  var next = 0
  (0 until components.count).forEachCancellable(ensureActive) { componentId ->
    val start = components.starts[componentId]
    val end = components.starts[componentId + 1]
    if (end - start == 1) {
      // Single vertex (no cycle, or cycle-of-one from a self-loop). Emit it directly.
      order[next++] = components.members[start]
    } else {
      // Multi-vertex SCC: sort the cycle internally, ignoring soft edges from deferred sources.
      val sorted = sortVerticesInSCC(input, components, componentId, deferred, ensureActive)
      softCycles += sorted
      sorted.copyInto(order, destinationOffset = next)
      next += sorted.size
    }
  }
  return order
}

/**
 * Orders the vertices **inside a single SCC** (a cycle) into a sensible linear order, ignoring the
 * deferrable edges that will be cut at codegen time.
 *
 * Within an SCC we classify each edge as either **hard** (non-deferrable, or deferrable but the
 * source isn't in [deferred]) or **soft** (deferrable + source is deferred). Soft edges are the
 * ones a `DelegateFactory` will paper over at runtime, so we pretend they don't exist when
 * ordering. The remaining hard edges form a DAG inside the SCC, which we Kahn-sort.
 *
 * Example: `A <-> B` cycle, B -> A is deferrable, B is deferred.
 *
 * ```
 *   Edges (inside SCC):
 *     A -> B   (hard: forward edge, A depends on B)
 *     B -> A   (soft: deferrable AND B is deferred -> ignored)
 *
 *   Hard DAG inside SCC:    A -> B
 *   Kahn output:            [B, A]
 *
 *   Generated code:
 *     Create a DelegateFactory<B> so A's provider can refer to B.
 *     Set its delegate to B's provider, which receives Provider<A>.
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
private fun sortVerticesInSCC(
  input: AnalysisInput,
  components: Components,
  componentId: Int,
  deferred: BitSet,
  ensureActive: () -> Unit,
): IntArray {
  val vertices = components.verticesOf(componentId)
  val cap = calculateInitialCapacity(vertices.size)

  // v -> number of hard prereqs (non-soft edges). Bounded by SCC size.
  val hardIn = HashMap<Int, Int>(cap)
  // prereq -> dependents (via hard edges). Bounded by SCC size.
  val revHard = HashMap<Int, MutableList<Int>>(cap)

  for (v in vertices) {
    ensureActive()
    var hardDependencies = 0
    for (edge in input.rowStarts[v] until input.rowStarts[v + 1]) {
      ensureActive()
      val dep = input.targets[edge]
      if (components.componentOf[dep] != componentId) {
        continue
      }

      // An edge is "soft" inside this SCC only if it's deferrable and the source is deferred
      val isSoftEdge = input.deferredEdges.get(edge) && deferred.get(v)
      if (isSoftEdge) {
        // ignore only these edges when ordering
        continue
      }
      hardDependencies++
      revHard.getOrPut(dep, ::ArrayList).add(v)
    }
    hardIn[v] = hardDependencies
  }

  // Sort ready by:
  // 1 - deferred nodes (i.e., emit DelegateFactory before its users)
  // 2 - more hard dependents (unlocks more)
  // 3 - natural order for determinism
  // The ready set holds at most every vertex in the SCC; size up front.
  val ready =
    PriorityQueue<Int>(vertices.size) { a, b ->
      val aDef = deferred.get(a)
      val bDef = deferred.get(b)
      if (aDef != bDef) return@PriorityQueue if (aDef) -1 else 1

      val aFanOut = revHard[a]?.size ?: 0
      val bFanOut = revHard[b]?.size ?: 0
      if (aFanOut != bFanOut) return@PriorityQueue bFanOut - aFanOut

      a.compareTo(b)
    }

  // Seed with nodes that have no hard deps
  vertices.forEachCancellable(ensureActive) { vertex ->
    if (hardIn.getValue(vertex) == 0) ready += vertex
  }

  val result = IntArray(vertices.size)
  var size = 0
  while (ready.isNotEmpty()) {
    ensureActive()
    val v = ready.remove()
    result[size++] = v
    revHard[v].orEmpty().forEachCancellable(ensureActive) { depender ->
      val degree = hardIn.getValue(depender) - 1
      hardIn[depender] = degree
      if (degree == 0) {
        ready += depender
      }
    }
  }

  check(size == vertices.size) {
    "Hard cycle remained inside SCC after removing selected soft edges"
  }
  return result
}

public data class Component<V>(val id: Int, val vertices: MutableList<V> = mutableListOf())

public data class TarjanResult<V : Comparable<V>>(
  val components: List<Component<V>>,
  val componentOf: ObjectIntMap<V>,
  /** Adjacency (forward and reverse) filtered to only reachable vertices. */
  val reachableAdjacency: GraphAdjacency<V>,
)

/**
 * Computes the strongly connected components (SCCs) of a directed graph. See [findComponents] for
 * the algorithm.
 *
 * Ids are assigned and results are mapped back to vertices on the calling thread.
 *
 * NOTE: The receiver adjacency (both keys and each value set) is assumed to be already sorted. This
 * preserves determinism of the int-id mapping and of the per-vertex edge ordering, so a given input
 * always produces the same output.
 *
 * @param roots Optional set of source roots to walk from. If null, every key in the receiver is
 *   walked (the entire graph is reachable). If provided, only vertices reachable from these roots
 *   are visited; the rest are filtered out of [TarjanResult.reachableAdjacency].
 * @param ensureActive cancellation callback invoked during traversal and adjacency construction.
 * @return The list of components (in reverse topological order), the vertex->component-id map, and
 *   the reachable forward/reverse adjacency over visited vertices only. The forward map shares its
 *   rows with the input. Treat those rows as read-only.
 * @receiver A directed graph as `vertex -> outgoing edges`. Both the key set and each edge set must
 *   be sorted.
 */
public fun <V : Comparable<V>> SortedMap<V, SortedSet<V>>.computeStronglyConnectedComponents(
  roots: SortedSet<V>? = null,
  ensureActive: () -> Unit = {},
): TarjanResult<V> {
  val indexed = indexed(ensureActive)
  val input =
    AnalysisInput(
      vertexCount = indexed.vertexCount,
      rowStarts = indexed.rowStarts,
      targets = indexed.targets,
      deferredEdges = BitSet(),
      implicitlyDeferrable = BitSet(),
      roots = indexed.rootIds(roots, ensureActive),
    )
  val components = findComponents(input, ensureActive)
  val (keyComponents, componentOf) =
    indexed.componentsOf(components.starts, components.members, ensureActive)
  return TarjanResult(
    keyComponents,
    componentOf,
    indexed.reachableAdjacency(components.members, ensureActive),
  )
}

/** Components found by [findComponents], with membership in Tarjan's pop order. */
private class Components(
  /** Where each component starts in [members]. Its size is the component count plus one. */
  val starts: IntArray,
  val members: IntArray,
  /** Each vertex's component, or [UNVISITED] for vertices no root reaches. */
  val componentOf: IntArray,
) {
  val count: Int
    get() = starts.size - 1

  /** Boxes the component's members for the generic cycle checks. */
  fun verticesOf(componentId: Int): List<Int> {
    val start = starts[componentId]
    val end = starts[componentId + 1]
    val vertices = ArrayList<Int>(end - start)
    for (index in start until end) {
      vertices += members[index]
    }
    return vertices
  }
}

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
 * popped SCC is a sink (depends on no later SCC), the last is a source. Since edges here point from
 * dependent to prerequisite, the sink is a leaf prerequisite and iterating ids 0..N is already the
 * desired init order (prereqs first, dependents last).
 *
 * ### How it works
 *
 * Tarjan does a single DFS, marking each vertex with two integers:
 * - `index[v]`: the order in which v was first visited (0, 1, 2, ...).
 * - `lowlink[v]` — the smallest `index` reachable from v's DFS subtree **without leaving the
 *   current DFS path**.
 *
 * `lowlink[v] == index[v]` precisely when v is the root of an SCC: no descendant could reach an
 * ancestor of v, so everything sitting above v on the SCC stack belongs to v's component.
 *
 * Two stacks are needed:
 * - **DFS stack**: the current call path (`callStack` here, with parallel `edgeCursor` for "which
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
 *  step  action                            index   lowlink  dfsStack          popped
 *  ───────────────────────────────────────────────────────────────────────────────────
 *  1     visit A                           A=0     A=0      [A]
 *  2     A->B  visit B                     B=1     B=1      [A,B]
 *  3     B->D  visit D                     D=2     D=2      [A,B,D]
 *  4     D->C  visit C                     C=3     C=3      [A,B,D,C]
 *  5     C->A  (A on stack; back-edge)             C=0      [A,B,D,C]
 *  6     C done; lowlink!=index, don't pop
 *        D inherits lowlink from C                 D=0      [A,B,D,C]
 *  7     D->E  visit E                     E=4     E=4      [A,B,D,C,E]
 *  8     E done; lowlink==index -> pop                      [A,B,D,C]         {E}
 *  9     D done; lowlink!=index, don't pop
 *  10    B inherits                                B=0      [A,B,D,C]
 *  11    A inherits                                A=0      [A,B,D,C]
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
 * Vertices arrive as dense int ids in sorted key order. Adjacency, visited flags, and stacks are
 * primitive `IntArray` / `BooleanArray` / `MutableIntList`s. This is cheaper than per-edge
 * `IrTypeKey.compareTo` / `equals` work that Metro previously used, which then dominated the inner
 * loop.
 *
 * Only vertices reachable from [AnalysisInput.roots] are visited. The rest keep [UNVISITED] in
 * [Components.componentOf].
 *
 * @see <a
 *   href="https://en.wikipedia.org/wiki/Tarjan%27s_strongly_connected_components_algorithm">Tarjan's
 *   algorithm</a>
 */
private fun findComponents(input: AnalysisInput, ensureActive: () -> Unit): Components {
  val n = input.vertexCount
  val rowStarts = input.rowStarts
  val targets = input.targets

  // Tarjan state, all primitive-backed.
  // indexOfId[v] is v's DFS discovery time (analogous to "v.index" in the classic algorithm).
  // lowLinkOfId[v] is the lowest discovery index v can reach without leaving the current DFS
  // stack (analogous to "v.lowlink").
  // onStack[v] tracks whether v is currently on `dfsStack` (the SCC-membership stack).
  // dfsStack holds vertices of the current DFS branch.
  val indexOfId = IntArray(n) { UNVISITED }
  val lowLinkOfId = IntArray(n)
  val onStack = BooleanArray(n)
  val dfsStack = MutableIntList(n)

  var nextIndex = 0
  // SCC members in pop order. componentStarts marks where each component begins.
  // At most one component per vertex (degenerate case where every vertex is its own SCC).
  val members = MutableIntList(n)
  val componentStarts = MutableIntList(n + 1)
  componentStarts.add(0)

  // Iterative Tarjan: a recursive `strongConnect(v)` would blow the JVM stack on deep dependency
  // chains, so we fold the recursion into an explicit (callStack, edgeCursor) pair where each
  // frame is `(v, current edge index)`. Edge cursors index directly into `targets`.
  val callStack = MutableIntList(n)
  val edgeCursor = MutableIntList(n)

  val visit: (start: Int) -> Unit = visit@{ start ->
    if (indexOfId[start] != UNVISITED) return@visit
    // Set the depth index for `start` to the smallest unused index and push the first frame.
    callStack.add(start)
    edgeCursor.add(rowStarts[start])
    indexOfId[start] = nextIndex
    lowLinkOfId[start] = nextIndex
    nextIndex++
    dfsStack.add(start)
    onStack[start] = true

    while (callStack.isNotEmpty()) {
      ensureActive()
      val top = callStack.size - 1
      val v = callStack[top]
      val cursor = edgeCursor[top]

      if (cursor < rowStarts[v + 1]) {
        edgeCursor[top] = cursor + 1
        val w = targets[cursor]
        if (indexOfId[w] == UNVISITED) {
          // Successor w has not yet been visited; "recurse" on it by pushing a new frame.
          indexOfId[w] = nextIndex
          lowLinkOfId[w] = nextIndex
          nextIndex++
          dfsStack.add(w)
          onStack[w] = true
          callStack.add(w)
          edgeCursor.add(rowStarts[w])
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
        while (true) {
          ensureActive()
          val popped = dfsStack.removeAt(dfsStack.size - 1)
          onStack[popped] = false
          members.add(popped)
          if (popped == v) break
        }
        componentStarts.add(members.size)
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

  for (root in input.roots) {
    ensureActive()
    visit(root)
  }

  val memberArray = IntArray(members.size) { members[it] }
  val startArray = IntArray(componentStarts.size) { componentStarts[it] }
  val componentOf = IntArray(n) { UNVISITED }
  for (componentId in 0 until startArray.size - 1) {
    ensureActive()
    for (index in startArray[componentId] until startArray[componentId + 1]) {
      componentOf[memberArray[index]] = componentId
    }
  }
  return Components(startArray, memberArray, componentOf)
}

/**
 * A sorted adjacency with vertex ids assigned in key order. Build and read it only on the thread
 * that owns the keys.
 */
internal class IndexedAdjacency<V>(
  /** The map these ids came from. Its entry order is id order. Never modify it. */
  val source: SortedMap<V, SortedSet<V>>,
  /** Keys by id. */
  val vertices: List<V>,
  /** Each key's edges, in the order [targets] stores them. */
  val rows: List<SortedSet<V>>,
  val idOf: ObjectIntMap<V>,
  val rowStarts: IntArray,
  val targets: IntArray,
) {
  val vertexCount: Int
    get() = vertices.size

  fun keysOf(ids: IntArray): List<V> = ids.map { vertices[it] }
}

/**
 * Assigns ids in sorted key order and stores each row's targets as ids. Throws
 * [NoSuchElementException] if a target is not a key.
 */
internal fun <V> SortedMap<V, SortedSet<V>>.indexed(
  ensureActive: () -> Unit,
): IndexedAdjacency<V> {
  val n = size
  val vertices = ArrayList<V>(n)
  val rows = ArrayList<SortedSet<V>>(n)
  val idOf = MutableObjectIntMap<V>(n)
  var edgeCount = 0
  for ((vertex, edges) in this) {
    ensureActive()
    idOf[vertex] = vertices.size
    vertices += vertex
    rows += edges
    edgeCount += edges.size
  }

  // Each row is already sorted because the source SortedSet is sorted and ids preserve that order.
  val rowStarts = IntArray(n + 1)
  val targets = IntArray(edgeCount)
  var next = 0
  for (id in 0 until n) {
    ensureActive()
    rowStarts[id] = next
    rows[id].forEachCancellable(ensureActive) { target ->
      targets[next++] = idOf[target]
    }
  }
  rowStarts[n] = next
  return IndexedAdjacency(this, vertices, rows, idOf, rowStarts, targets)
}

/** Returns sorted ids for [roots], skipping keys outside the graph. Null selects every vertex. */
internal fun <V> IndexedAdjacency<V>.rootIds(
  roots: SortedSet<V>?,
  ensureActive: () -> Unit,
): IntArray {
  if (roots == null) {
    return IntArray(vertexCount) { it }
  }

  val ids = MutableIntList(roots.size)
  roots.forEachCancellable(ensureActive) { root ->
    val id = idOf.getOrDefault(root, UNVISITED)
    if (id != UNVISITED) {
      ids.add(id)
    }
  }
  return IntArray(ids.size) { ids[it] }
}

/** Marks each edge for which [isDeferrable] returns true. */
private fun <V> IndexedAdjacency<V>.deferredEdges(
  isDeferrable: (from: V, to: V) -> Boolean,
  ensureActive: () -> Unit,
): BitSet {
  val deferredEdges = BitSet(targets.size)
  for (id in 0 until vertexCount) {
    ensureActive()
    val from = vertices[id]
    var edge = rowStarts[id]
    rows[id].forEachCancellable(ensureActive) { to ->
      if (isDeferrable(from, to)) {
        deferredEdges.set(edge)
      }
      edge++
    }
  }
  return deferredEdges
}

/** Marks each vertex for which [predicate] returns true. */
private fun <V> IndexedAdjacency<V>.matchingVertices(
  predicate: (V) -> Boolean,
  ensureActive: () -> Unit,
): BitSet {
  val matches = BitSet(vertexCount)
  for (id in 0 until vertexCount) {
    ensureActive()
    if (predicate(vertices[id])) {
      matches.set(id)
    }
  }
  return matches
}

/** Maps component membership back to keys. */
private fun <V> IndexedAdjacency<V>.componentsOf(
  starts: IntArray,
  members: IntArray,
  ensureActive: () -> Unit,
): Pair<List<Component<V>>, ObjectIntMap<V>> {
  val components = ArrayList<Component<V>>(starts.size - 1)
  val componentOf = MutableObjectIntMap<V>(vertexCount)
  for (componentId in 0 until starts.size - 1) {
    ensureActive()
    val component = Component<V>(componentId)
    (starts[componentId] until starts[componentId + 1]).forEachCancellable(ensureActive) { index ->
      val vertex = vertices[members[index]]
      component.vertices += vertex
      componentOf[vertex] = componentId
    }
    components += component
  }
  return components to componentOf
}

/**
 * Builds the reachable adjacency in key space.
 *
 * The full adjacency may include vertices that no root reaches. The result covers only the visited
 * vertices. The DFS follows every outgoing edge, so each visited vertex's sorted row is already
 * complete. Reusing it avoids copying and sorting dense rows (~6500 edges per vertex in benchmark
 * graphs).
 */
private fun <V : Comparable<V>> IndexedAdjacency<V>.reachableAdjacency(
  members: IntArray,
  ensureActive: () -> Unit,
): GraphAdjacency<V> {
  val reachable = BitSet(vertexCount)
  for (id in members) {
    reachable.set(id)
  }

  val reachableForward = reachableRows(reachable, members.size, ensureActive)
  val reachableReverse = HashMap<V, MutableSet<V>>(vertexCount)
  for (id in 0 until vertexCount) {
    ensureActive()
    // Skip vertices the DFS never visited. They're not part of the reachable subgraph.
    if (!reachable.get(id)) {
      continue
    }

    val vertex = vertices[id]
    for (dependency in rows[id]) {
      ensureActive()
      // Mirror the edge into the reverse adjacency so consumers can walk dependents.
      reachableReverse.getAndAdd(dependency, vertex)
    }
  }
  return GraphAdjacency(reachableForward, reachableReverse)
}

/**
 * Returns the rows of the [reachable] vertices by key. Empty rows are included so callers that
 * index into the result (like `forward[v]`) see every leaf.
 *
 * Inserting a row compares keys. That's expensive for compiler keys. When at least half of the
 * vertices are reachable, this copies [IndexedAdjacency.source] and removes the other entries
 * through its iterator. Both steps are linear and compare no keys.
 */
private fun <V : Comparable<V>> IndexedAdjacency<V>.reachableRows(
  reachable: BitSet,
  reachableCount: Int,
  ensureActive: () -> Unit,
): SortedMap<V, SortedSet<V>> {
  val isMostlyReachable = reachableCount >= vertexCount - reachableCount
  if (!isMostlyReachable) {
    // A copy would visit every entry to keep a few.
    val forward = sortedMapOf<V, SortedSet<V>>()
    var id = reachable.nextSetBit(0)
    while (id >= 0) {
      ensureActive()
      forward[vertices[id]] = rows[id]
      id = reachable.nextSetBit(id + 1)
    }
    return forward
  }

  val forward = TreeMap(source)
  val entries = forward.entries.iterator()
  var id = 0
  while (entries.hasNext()) {
    ensureActive()
    entries.next()
    // The copy's entry order is id order.
    if (!reachable.get(id)) {
      entries.remove()
    }
    id++
  }
  return forward
}

/** Maps [output] back to keys. [output] must come from this adjacency's analysis input. */
internal fun <V : Comparable<V>> IndexedAdjacency<V>.topologyOf(
  output: AnalysisOutput,
  ensureActive: () -> Unit,
): GraphTopology<V> {
  val (components, componentOf) =
    componentsOf(output.componentStarts, output.componentMembers, ensureActive)
  val adjacency = reachableAdjacency(output.componentMembers, ensureActive)

  val sortedKeys = ArrayList<V>(output.order.size)
  for (id in output.order) {
    ensureActive()
    sortedKeys += vertices[id]
  }

  // Deferred keys follow id order, which is sorted key order.
  val deferredTypes = LinkedHashSet<V>(calculateInitialCapacity(output.deferred.cardinality()))
  var deferredId = output.deferred.nextSetBit(0)
  while (deferredId >= 0) {
    ensureActive()
    deferredTypes += vertices[deferredId]
    deferredId = output.deferred.nextSetBit(deferredId + 1)
  }

  return GraphTopology(
    sortedKeys,
    deferredTypes,
    adjacency.forward.keys,
    adjacency,
    components,
    componentOf,
  )
}

private const val UNVISITED = -1
private val EMPTY_INT_ARRAY = IntArray(0)

/** Builds the full adjacency map while checking for cancellation. */
public fun <T : Comparable<T>> buildFullAdjacency(
  map: ScatterMap<T, *>,
  sourceToTarget: (T) -> Iterable<T>,
  ensureActive: () -> Unit = {},
  onMissing: (source: T, missing: T) -> Unit,
): SortedMap<T, SortedSet<T>> {
  /**
   * Sort our map keys and list values here for better performance later (avoiding needing to
   * defensively sort before assigning vertex ids).
   */
  val adjacency = sortedMapOf<T, SortedSet<T>>()

  map.forEachKey { key ->
    ensureActive()
    val dependencies = adjacency.getOrPut(key, ::sortedSetOf)

    for (targetKey in sourceToTarget(key)) {
      ensureActive()
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
