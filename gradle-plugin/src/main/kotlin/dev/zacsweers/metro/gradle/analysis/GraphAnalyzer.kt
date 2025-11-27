// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import org.jgrapht.Graph
import org.jgrapht.alg.scoring.BetweennessCentrality
import org.jgrapht.graph.DefaultDirectedGraph
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.traverse.TopologicalOrderIterator

/** Performs various graph analysis algorithms on a [BindingGraph] using JGraphT. */
public class GraphAnalyzer(private val bindingGraph: BindingGraph) {

  private val fullGraph: DefaultDirectedGraph<String, DefaultEdge> by lazy { buildJgraph() }
  private val eagerGraph: DefaultDirectedGraph<String, DefaultEdge> by lazy {
    buildJgraph(eagerOnly = true)
  }

  /** @param eagerOnly If true, only include edges that are non-deferrable. */
  private fun buildJgraph(eagerOnly: Boolean = false): DefaultDirectedGraph<String, DefaultEdge> {
    val dag = DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge::class.java)
    for (k in bindingGraph.keys) {
      dag.addVertex(k)
    }
    for (binding in bindingGraph.getAllBindings()) {
      val from = binding.key
      if (from !in bindingGraph.keys) continue
      for (dep in binding.dependencies) {
        val to = dep.key
        if (eagerOnly && dep.isDeferrable) continue
        if (to in bindingGraph.keys) {
          dag.addEdge(from, to)
        }
      }
    }
    return dag
  }

  /** Compute basic statistics about the graph. */
  public fun computeStatistics(): GraphStatistics {
    val keys = bindingGraph.keys
    val fanOuts = keys.map { bindingGraph.fanOut(it) }
    val bindings = bindingGraph.getAllBindings().toList()
    val bindingsByKind = bindings.groupingBy { it.bindingKind }.eachCount().toSortedMap()

    return GraphStatistics(
      graphName = bindingGraph.graphName,
      totalBindings = bindingGraph.size,
      scopedBindings = bindings.count { it.isScoped },
      unscopedBindings = bindings.count { !it.isScoped },
      bindingsByKind = bindingsByKind,
      averageDependencies = if (fanOuts.isNotEmpty()) fanOuts.average() else 0.0,
      maxDependencies = fanOuts.maxOrNull() ?: 0,
      maxDependenciesBinding = keys.maxByOrNull { bindingGraph.fanOut(it) },
      rootBindings = bindingGraph.findRoots().size,
      leafBindings = bindingGraph.findLeaves().size,
      multibindingCount = bindings.count { it.multibinding != null },
      aliasCount = bindings.count { it.aliasTarget != null },
    )
  }

  /**
   * Finds the longest paths in a directed acyclic graph (DAG) based on topological ordering.
   * Dynamically computes the longest paths starting from the root nodes up to the specified maximum
   * number of paths.
   *
   * @param maxPaths The maximum number of longest paths to reconstruct and return. Defaults to 5.
   * @return A LongestPathResult object containing details about the computed longest path(s),
   *   including the length of the longest path, list of longest paths, average path length, and a
   *   distribution of path lengths for the graph.
   */
  public fun findLongestPaths(maxPaths: Int = 5): LongestPathResult {
    /*
    Algorithm based on https://en.wikipedia.org/wiki/Longest_path_problem#Acyclic_graphs

    Similarly, for each vertex v in a given DAG, the length of the longest path ending at v may be obtained by the
    following steps:

        - Find a topological ordering of the given DAG.
        - For each vertex v of the DAG, in the topological ordering, compute the length of the longest path ending at
          v by looking at its incoming neighbors and adding one to the maximum length recorded for those neighbors.
          If v has no incoming neighbors, set the length of the longest path ending at v to zero. In either case,
          record this number so that later steps of the algorithm can access it.

    Once this has been done, the longest path in the whole DAG may be obtained by starting at the vertex v with the largest recorded value, then repeatedly stepping backwards to its incoming neighbor with the largest recorded value, and reversing the sequence of vertices found in this way.

    This is equivalent to running the shortest-path algorithm on −G.
     */
    val vs = eagerGraph.vertexSet()
    if (vs.isEmpty()) {
      return LongestPathResult(bindingGraph.graphName, 0, emptyList(), 0.0, sortedMapOf())
    }

    val roots = eagerGraph.computeRoots(vs)

    val topo = eagerGraph.topoOrderOrThrow()

    // longestPaths[v] = the longest path length in the vertices set starting at v
    //
    // dynamic programming algos often just call this "dp",
    // which is a silly opaque name that only a mathematician could love
    val longestPaths = HashMap<String, Int>(vs.size)
    for (v in topo.asReversed()) {
      var best = 1
      for (e in eagerGraph.outgoingEdgesOf(v)) {
        val w = eagerGraph.getEdgeTarget(e)
        best = maxOf(best, 1 + (longestPaths[w] ?: 1))
      }
      longestPaths[v] = best
    }

    val longestPath = roots.maxOfOrNull { longestPaths[it] ?: 1 } ?: 1

    /** Returns the sorted list of direct successors of [v] in the eager graph. */
    fun sortedChildrenOf(v: String): List<String> =
      eagerGraph
        .outgoingEdgesOf(v)
        .asSequence()
        .map { eagerGraph.getEdgeTarget(it) }
        .sorted()
        .toList()

    val outCache = HashMap<String, List<String>>(vs.size)
    val paths = mutableListOf<List<String>>()

    /**
     * Recursively builds longest paths from [v] by following edges to children with optimal path
     * lengths. Stops when [maxPaths] paths have been collected.
     */
    fun build(v: String, path: ArrayDeque<String>) {
      if (paths.size >= maxPaths) return
      path.add(v)

      val vLen = longestPaths[v] ?: 1
      val need = vLen - 1
      val nexts = outCache.getOrPut(v) { sortedChildrenOf(v) }
      val bestKids =
        if (need >= 1) {
          nexts.filter { (longestPaths[it] ?: 1) == need }
        } else {
          emptyList()
        }

      if (bestKids.isEmpty()) {
        paths += path.toList()
      } else {
        for (c in bestKids) {
          build(c, path)
          if (paths.size >= maxPaths) break
        }
      }

      path.removeLast()
    }

    // Start path reconstruction from roots that have the maximum path length
    for (r in roots.sorted()) {
      if ((longestPaths[r] ?: 1) == longestPath) {
        build(r, ArrayDeque())
      }
      if (paths.size >= maxPaths) break
    }

    val rootLens = roots.map { longestPaths[it] ?: 1 }
    val distribution = rootLens.groupingBy { it }.eachCount().toSortedMap()

    return LongestPathResult(
      graphName = bindingGraph.graphName,
      longestPathLength = longestPath,
      longestPaths = paths.distinctBy { it.joinToString("->") },
      averagePathLength = if (rootLens.isNotEmpty()) rootLens.average() else 0.0,
      pathLengthDistribution = distribution,
    )
  }

  private fun DefaultDirectedGraph<String, DefaultEdge>.topoOrderOrThrow(): List<String> {
    if (edgeSet().isEmpty()) return emptyList()
    val topo = TopologicalOrderIterator(this).asSequence().toList()
    check(topo.size == vertexSet().size) { "Cycle remains after ignoring deferrable edges." }
    return topo
  }

  private fun <V> Graph<V, *>.computeRoots(vs: Set<V> = vertexSet()) =
    vs.filterTo(mutableSetOf()) { inDegreeOf(it) == 0 }.ifEmpty { vs }

  /**
   * Computes dominator relationships in the graph. A node X dominates node Y if every path from a
   * root to Y must pass through X. Nodes that dominate many others are critical bottlenecks.
   *
   * @see <a href="http://www.hipersoft.rice.edu/grads/publications/dom14.pdf">A Simple, Fast
   *   Dominance Algorithm.</a>
   */
  public fun computeDominators(): DominatorResult {
    val vs = fullGraph.vertexSet()
    if (vs.isEmpty()) {
      return DominatorResult(bindingGraph.graphName, emptyList())
    }

    // Virtual root unifies multiple graph roots for the dominator algorithm
    val virtualRoot = generateVirtualRoot(vs)
    val roots = fullGraph.computeRoots(vs)

    // Build predecessors map from incoming edges
    val preds = HashMap<String, Set<String>>(vs.size)
    for (v in vs) {
      preds[v] = fullGraph.incomingEdgesOf(v).map { fullGraph.getEdgeSource(it) }.toSet()
    }
    // Add virtual root as predecessor of all graph roots
    val predsWithRoot = HashMap<String, Set<String>>(vs.size)
    predsWithRoot.putAll(preds)
    for (r in roots) {
      predsWithRoot[r] = (predsWithRoot[r].orEmpty()) + virtualRoot
    }

    // Compute immediate dominators and derive domination counts
    val idoms = findImmediateDominators(virtualRoot, vs, predsWithRoot)
    val dominatorCounts = countDominated(idoms, virtualRoot)

    val result =
      dominatorCounts
        .map { (k, dominated) ->
          DominatorNode(
            key = k,
            bindingKind = bindingGraph.getBinding(k)?.bindingKind ?: "Unknown",
            dominatedCount = dominated.size,
            dominatedKeys = dominated.sorted(),
          )
        }
        .sortedByDescending { it.dominatedCount }

    return DominatorResult(bindingGraph.graphName, result)
  }

  private fun generateVirtualRoot(existing: Set<String>): String {
    var candidate = "___VIRTUAL_ROOT___"
    var i = 0
    while (candidate in existing) {
      i++
      candidate = "___VIRTUAL_ROOT___$i"
    }
    return candidate
  }

  /**
   * Computes immediate dominators using the Cooper-Harvey-Kennedy algorithm. Returns a map from
   * each node to its immediate dominator. Runs in nearly O(V+E) time.
   *
   * @see <a href="http://www.hipersoft.rice.edu/grads/publications/dom14.pdf">A Simple, Fast
   *   Dominance Algorithm.</a>
   */
  private fun findImmediateDominators(
    virtualRoot: String,
    nodes: Set<String>,
    predecessors: Map<String, Set<String>>,
  ): Map<String, String> {
    // Step 1: Compute reverse postorder numbering via DFS from virtual root
    val postorder = mutableListOf<String>()
    val visited = mutableSetOf<String>()

    fun dfs(node: String) {
      if (!visited.add(node)) return
      // Visit successors (nodes where this node is a predecessor)
      for (n in nodes) {
        if (node in predecessors[n].orEmpty()) {
          dfs(n)
        }
      }
      postorder.add(node)
    }

    dfs(virtualRoot)

    // Reverse postorder: higher number = closer to root
    val rpoNumber = HashMap<String, Int>(postorder.size)
    for ((index, node) in postorder.asReversed().withIndex()) {
      rpoNumber[node] = index
    }

    // for all nodes, b: doms[b] ← Undefined
    // doms[start_node] ← start_node
    val idom = HashMap<String, String?>(nodes.size + 1)
    idom[virtualRoot] = virtualRoot

    // function intersect(b1, b2) returns node
    fun intersect(b1: String, b2: String): String {
      // finger1 ← b1; finger2 ← b2
      var finger1 = b1
      var finger2 = b2
      // while (finger1 ≠ finger2)
      while (finger1 != finger2) {
        // while (finger1 < finger2): finger1 = doms[finger1]
        while ((rpoNumber[finger1] ?: Int.MAX_VALUE) > (rpoNumber[finger2] ?: Int.MAX_VALUE)) {
          finger1 = idom[finger1] ?: return finger2
        }
        // while (finger2 < finger1): finger2 = doms[finger2]
        while ((rpoNumber[finger2] ?: Int.MAX_VALUE) > (rpoNumber[finger1] ?: Int.MAX_VALUE)) {
          finger2 = idom[finger2] ?: return finger1
        }
      }
      // return finger1
      return finger1
    }

    // Changed ← true
    var changed = true
    // while (Changed)
    while (changed) {
      // Changed ← false
      changed = false
      // for all nodes, b, in reverse postorder (except start node)
      for (node in postorder.asReversed()) {
        if (node == virtualRoot) continue

        val preds = predecessors[node].orEmpty()
        if (preds.isEmpty()) continue

        // new_idom ← first (processed) predecessor of b
        var newIdom: String? = null
        for (p in preds) {
          if (idom[p] != null) {
            newIdom = p
            break
          }
        }
        if (newIdom == null) continue

        // for all other predecessors, p, of b
        //   if doms[p] ≠ Undefined: new_idom ← intersect(p, new_idom)
        for (p in preds) {
          if (p == newIdom) continue
          if (idom[p] != null) {
            newIdom = intersect(p, newIdom!!)
          }
        }

        // if doms[b] ≠ new_idom: doms[b] ← new_idom; Changed ← true
        if (idom[node] != newIdom) {
          idom[node] = newIdom
          changed = true
        }
      }
    }

    // Return non-null idoms only
    return idom.entries
      .filter { it.value != null && it.key != virtualRoot }
      .associate { it.key to it.value!! }
  }

  /** Counts how many nodes each dominator dominates by walking up the idom tree from each node. */
  private fun countDominated(
    idoms: Map<String, String>,
    virtualRoot: String,
  ): Map<String, MutableSet<String>> {
    val dominatedBy = mutableMapOf<String, MutableSet<String>>()

    for (node in idoms.keys) {
      // Walk up the idom chain, adding this node to each ancestor's dominated set
      var current = idoms[node]
      while (current != null && current != virtualRoot) {
        dominatedBy.getOrPut(current) { mutableSetOf() }.add(node)
        current = idoms[current]
      }
    }

    return dominatedBy
  }

  /**
   * Computes betweenness centrality for each node. Nodes with high centrality lie on many shortest
   * paths between other nodes, making them important connectors in the graph.
   */
  public fun computeBetweennessCentrality(): CentralityResult {
    if (fullGraph.vertexSet().isEmpty()) {
      return CentralityResult(bindingGraph.graphName, emptyList())
    }

    val bc = BetweennessCentrality(fullGraph).scores
    val max = bc.values.maxOrNull() ?: 1.0

    val scores =
      bc
        .map { (k, s) ->
          CentralityScore(
            key = k,
            bindingKind = bindingGraph.getBinding(k)?.bindingKind ?: "Unknown",
            betweennessCentrality = s,
            normalizedCentrality = if (max > 0) s / max else 0.0,
          )
        }
        .sortedByDescending { it.betweennessCentrality }

    return CentralityResult(bindingGraph.graphName, scores)
  }

  /**
   * Computes fan-in (number of dependents) and fan-out (number of dependencies) for each binding.
   * High fan-in indicates widely used bindings; high fan-out indicates bindings with many
   * dependencies.
   */
  public fun computeFanAnalysis(topN: Int): FanAnalysisResult {
    val keys = bindingGraph.keys
    if (keys.isEmpty()) {
      return FanAnalysisResult(
        bindingGraph.graphName,
        emptyList(),
        emptyList(),
        emptyList(),
        0.0,
        0.0,
      )
    }

    val base =
      keys.map { k ->
        FanScore(
          key = k,
          bindingKind = bindingGraph.getBinding(k)?.bindingKind ?: "Unknown",
          fanIn = bindingGraph.fanIn(k),
          fanOut = bindingGraph.fanOut(k),
          dependents = emptyList(),
          dependencies = emptyList(),
        )
      }

    fun hydrate(s: FanScore) =
      s.copy(
        dependents = bindingGraph.getDependents(s.key).sorted(),
        dependencies = bindingGraph.getDependencies(s.key).sorted(),
      )

    val topIn = base.sortedByDescending { it.fanIn }.take(topN).map(::hydrate)
    val topOut = base.sortedByDescending { it.fanOut }.take(topN).map(::hydrate)

    return FanAnalysisResult(
      graphName = bindingGraph.graphName,
      bindings = base.sortedBy { it.key },
      highFanIn = topIn,
      highFanOut = topOut,
      averageFanIn = base.map { it.fanIn }.average(),
      averageFanOut = base.map { it.fanOut }.average(),
    )
  }
}
