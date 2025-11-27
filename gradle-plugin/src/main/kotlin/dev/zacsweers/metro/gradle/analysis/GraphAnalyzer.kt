// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

/** Performs various graph analysis algorithms on a [BindingGraph]. */
public class GraphAnalyzer(private val graph: BindingGraph) {

  /** Compute basic statistics about the graph. */
  public fun computeStatistics(): GraphStatistics {
    val bindings = graph.getAllBindings().toList()
    val bindingsByKind = bindings.groupingBy { it.bindingKind }.eachCount()
    val dependencies = bindings.map { it.dependencies.size }

    return GraphStatistics(
      graphName = graph.graphName,
      totalBindings = bindings.size,
      scopedBindings = bindings.count { it.isScoped },
      unscopedBindings = bindings.count { !it.isScoped },
      bindingsByKind = bindingsByKind.toSortedMap(),
      averageDependencies = if (dependencies.isNotEmpty()) dependencies.average() else 0.0,
      maxDependencies = dependencies.maxOrNull() ?: 0,
      maxDependenciesBinding = bindings.maxByOrNull { it.dependencies.size }?.key,
      rootBindings = graph.findRoots().size,
      leafBindings = graph.findLeaves().size,
      multibindingCount = bindings.count { it.multibinding != null },
      aliasCount = bindings.count { it.aliasTarget != null },
    )
  }

  /**
   * Find the longest dependency paths in the graph. Uses DFS from each root to find the longest
   * path to any leaf.
   */
  public fun findLongestPaths(maxPaths: Int = 5): LongestPathResult {
    val roots = graph.findRoots()
    val allPaths = mutableListOf<List<String>>()
    val pathLengths = mutableListOf<Int>()

    for (root in roots) {
      findAllPathsFrom(root, allPaths, pathLengths)
    }

    // If no roots, try from all nodes (might have cycles)
    if (roots.isEmpty()) {
      for (key in graph.keys) {
        findAllPathsFrom(key, allPaths, pathLengths)
      }
    }

    val longestLength = pathLengths.maxOrNull() ?: 0
    val longestPaths =
      allPaths
        .filter { it.size == longestLength }
        .distinctBy { it.joinToString("->") }
        .take(maxPaths)

    val distribution = pathLengths.groupingBy { it }.eachCount().toSortedMap()

    return LongestPathResult(
      graphName = graph.graphName,
      longestPathLength = longestLength,
      longestPaths = longestPaths,
      averagePathLength = if (pathLengths.isNotEmpty()) pathLengths.average() else 0.0,
      pathLengthDistribution = distribution,
    )
  }

  private fun findAllPathsFrom(
    start: String,
    allPaths: MutableList<List<String>>,
    pathLengths: MutableList<Int>,
  ) {
    val visited = mutableSetOf<String>()
    val currentPath = mutableListOf<String>()

    fun dfs(node: String) {
      if (node in visited || node !in graph.keys) return
      visited.add(node)
      currentPath.add(node)

      val deps = graph.getDependencies(node).filter { it in graph.keys && it !in visited }
      if (deps.isEmpty()) {
        // Reached a leaf in this path
        allPaths.add(currentPath.toList())
        pathLengths.add(currentPath.size)
      } else {
        for (dep in deps) {
          dfs(dep)
        }
      }

      currentPath.removeLast()
      visited.remove(node)
    }

    dfs(start)
  }

  /**
   * Compute dominator analysis. A node D dominates node N if every path from any root to N goes
   * through D.
   *
   * This uses a simplified algorithm suitable for dependency graphs: For each node, we find all
   * nodes that must be traversed to reach it.
   */
  public fun computeDominators(): DominatorResult {
    val dominatedBy = mutableMapOf<String, MutableSet<String>>()

    // For each node, find which nodes dominate it
    for (key in graph.keys) {
      dominatedBy[key] = findDominators(key)
    }

    // Invert: for each potential dominator, count how many nodes it dominates
    val dominatorCounts = mutableMapOf<String, MutableSet<String>>()
    for ((node, dominators) in dominatedBy) {
      for (dominator in dominators) {
        if (dominator != node) {
          dominatorCounts.getOrPut(dominator) { mutableSetOf() }.add(node)
        }
      }
    }

    val result =
      dominatorCounts
        .map { (key, dominated) ->
          DominatorNode(
            key = key,
            bindingKind = graph.getBinding(key)?.bindingKind ?: "Unknown",
            dominatedCount = dominated.size,
            dominatedKeys = dominated.sorted(),
          )
        }
        .sortedByDescending { it.dominatedCount }

    return DominatorResult(graphName = graph.graphName, dominators = result)
  }

  private fun findDominators(target: String): MutableSet<String> {
    // Find all nodes that appear in EVERY path to target from any root
    val roots = graph.findRoots().ifEmpty { graph.keys }
    val allPathNodes = mutableListOf<Set<String>>()

    for (root in roots) {
      val pathsToTarget = findPathsBetween(root, target)
      for (path in pathsToTarget) {
        allPathNodes.add(path.toSet())
      }
    }

    if (allPathNodes.isEmpty()) {
      return mutableSetOf()
    }

    // Intersection of all paths gives us dominators
    return allPathNodes.reduce { acc, set -> acc.intersect(set) }.toMutableSet()
  }

  private fun findPathsBetween(from: String, to: String): List<List<String>> {
    val paths = mutableListOf<List<String>>()
    val currentPath = mutableListOf<String>()
    val visited = mutableSetOf<String>()

    fun dfs(node: String) {
      if (node in visited || node !in graph.keys) return
      visited.add(node)
      currentPath.add(node)

      if (node == to) {
        paths.add(currentPath.toList())
      } else {
        for (dep in graph.getDependencies(node)) {
          dfs(dep)
        }
      }

      currentPath.removeLast()
      visited.remove(node)
    }

    dfs(from)
    return paths
  }

  /**
   * Compute betweenness centrality for all nodes.
   *
   * Betweenness centrality measures how often a node lies on the shortest path between other pairs
   * of nodes. High betweenness indicates a "bridge" node.
   */
  public fun computeBetweennessCentrality(): CentralityResult {
    val centrality = mutableMapOf<String, Double>()

    // Initialize all to 0
    for (key in graph.keys) {
      centrality[key] = 0.0
    }

    // For each pair of nodes, find shortest paths and count intermediaries
    val keys = graph.keys.toList()
    for (i in keys.indices) {
      for (j in keys.indices) {
        if (i != j) {
          val source = keys[i]
          val target = keys[j]
          val shortestPaths = findShortestPaths(source, target)

          if (shortestPaths.isNotEmpty()) {
            val pathCount = shortestPaths.size.toDouble()
            // Count how many times each intermediate node appears
            for (path in shortestPaths) {
              // Exclude source and target
              for (k in 1 until path.size - 1) {
                val node = path[k]
                centrality[node] = centrality.getValue(node) + (1.0 / pathCount)
              }
            }
          }
        }
      }
    }

    // Normalize by (n-1)(n-2)/2 for undirected, (n-1)(n-2) for directed
    val n = keys.size
    val normFactor = if (n > 2) ((n - 1) * (n - 2)).toDouble() else 1.0
    val maxCentrality = centrality.values.maxOrNull() ?: 1.0

    val scores =
      centrality
        .map { (key, score) ->
          CentralityScore(
            key = key,
            bindingKind = graph.getBinding(key)?.bindingKind ?: "Unknown",
            betweennessCentrality = score,
            normalizedCentrality = if (maxCentrality > 0) score / maxCentrality else 0.0,
          )
        }
        .sortedByDescending { it.betweennessCentrality }

    return CentralityResult(graphName = graph.graphName, centralityScores = scores)
  }

  private fun findShortestPaths(from: String, to: String): List<List<String>> {
    if (from == to) return listOf(listOf(from))
    if (from !in graph.keys || to !in graph.keys) return emptyList()

    // BFS to find shortest path length first
    val distances = mutableMapOf<String, Int>()
    val queue = ArrayDeque<String>()
    queue.add(from)
    distances[from] = 0

    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      val currentDist = distances.getValue(current)

      for (neighbor in graph.getDependencies(current)) {
        if (neighbor !in distances && neighbor in graph.keys) {
          distances[neighbor] = currentDist + 1
          queue.add(neighbor)
        }
      }
    }

    val targetDist = distances[to] ?: return emptyList()

    // Now find all paths of that length
    val paths = mutableListOf<List<String>>()
    val currentPath = mutableListOf<String>()

    fun dfs(node: String, depth: Int) {
      currentPath.add(node)

      if (node == to && depth == targetDist) {
        paths.add(currentPath.toList())
      } else if (depth < targetDist) {
        for (neighbor in graph.getDependencies(node)) {
          if (neighbor in graph.keys && distances[neighbor] == depth + 1) {
            dfs(neighbor, depth + 1)
          }
        }
      }

      currentPath.removeLast()
    }

    dfs(from, 0)
    return paths
  }

  /** Compute fan-in and fan-out analysis. */
  public fun computeFanAnalysis(topN: Int = 10): FanAnalysisResult {
    val scores =
      graph.getAllBindings().map { binding ->
        FanScore(
          key = binding.key,
          bindingKind = binding.bindingKind,
          fanIn = graph.fanIn(binding.key),
          fanOut = graph.fanOut(binding.key),
          dependents = graph.getDependents(binding.key).sorted(),
          dependencies = graph.getDependencies(binding.key).sorted(),
        )
      }

    val avgFanIn = if (scores.isNotEmpty()) scores.map { it.fanIn }.average() else 0.0
    val avgFanOut = if (scores.isNotEmpty()) scores.map { it.fanOut }.average() else 0.0

    return FanAnalysisResult(
      graphName = graph.graphName,
      bindings = scores.sortedBy { it.key },
      highFanIn = scores.sortedByDescending { it.fanIn }.take(topN),
      highFanOut = scores.sortedByDescending { it.fanOut }.take(topN),
      averageFanIn = avgFanIn,
      averageFanOut = avgFanOut,
    )
  }
}
