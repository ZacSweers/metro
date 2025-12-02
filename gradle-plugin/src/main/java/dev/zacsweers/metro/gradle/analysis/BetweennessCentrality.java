// Copyright (C) 2017-2023 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis;

import com.google.common.graph.Graph;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import org.jetbrains.annotations.NotNull;

/**
 * Betweenness centrality.
 *
 * <p>Computes the betweenness centrality of each vertex of a graph. The betweenness centrality of a
 * node $v$ is given by the expression: $g(v)= \sum_{s \neq v \neq
 * t}\frac{\sigma_{st}(v)}{\sigma_{st}}$ where $\sigma_{st}$ is the total number of shortest paths
 * from node $s$ to node $t$ and $\sigma_{st}(v)$ is the number of those paths that pass through
 * $v$. For more details see <a
 * href="https://en.wikipedia.org/wiki/Betweenness_centrality">wikipedia</a>.
 *
 * <p>The algorithm is based on
 *
 * <ul>
 *   <li>Brandes, Ulrik (2001). "A faster algorithm for betweenness centrality". Journal of
 *       Mathematical Sociology. 25 (2): 163–177.
 * </ul>
 *
 * The running time is $O(nm)$ for unweighted graphs, where $n$ is the number of vertices and $m$
 * the number of edges of the graph. The space complexity is $O(n + m)$.
 *
 * <p>Note that this running time assumes that arithmetic is performed between numbers whose
 * representation needs a number of bits which is logarithmic in the instance size. There are
 * instances where this is not true and path counters might grow super exponential. This class
 * allows the user to adjust whether an exception is thrown in case overflow occurs. Default
 * behavior is to ignore overflow issues.
 *
 * @param <V> the graph vertex type
 * @author Assaf Mizrachi
 */
// Fork notes: Modified to work with Guava
public class BetweennessCentrality<V> {
  /** Underlying graph */
  private final Graph<@NotNull V> graph;

  /** Whether to normalize scores */
  private final boolean normalize;

  /** The actual scores */
  private Map<V, Double> scores;

  /** Strategy for overflow when counting paths. */
  private final OverflowStrategy overflowStrategy;

  /** Strategy followed when counting paths. */
  public enum OverflowStrategy {
    /**
     * Do not check for overflow in counters. This means that on certain instances the results might
     * be wrong due to counters being too large to fit in a long.
     */
    IGNORE_OVERFLOW,
    /** An exception is thrown if an overflow in counters is detected. */
    THROW_EXCEPTION_ON_OVERFLOW,
  }

  /**
   * Construct a new instance.
   *
   * @param graph the input graph
   */
  public BetweennessCentrality(Graph<@NotNull V> graph) {
    this(graph, false);
  }

  /**
   * Construct a new instance.
   *
   * @param graph the input graph
   * @param normalize whether to normalize by dividing the closeness by $(n-1) \cdot (n-2)$, where
   *     $n$ is the number of vertices of the graph
   */
  public BetweennessCentrality(Graph<@NotNull V> graph, boolean normalize) {
    this(graph, normalize, OverflowStrategy.IGNORE_OVERFLOW);
  }

  /**
   * Construct a new instance.
   *
   * @param graph the input graph
   * @param normalize whether to normalize by dividing the closeness by $(n-1) \cdot (n-2)$, where
   *     $n$ is the number of vertices of the graph
   * @param overflowStrategy strategy to use if overflow is detected
   */
  public BetweennessCentrality(
      Graph<@NotNull V> graph, boolean normalize, OverflowStrategy overflowStrategy) {
    this.graph = Objects.requireNonNull(graph, "Graph cannot be null");

    this.scores = null;
    this.normalize = normalize;
    this.overflowStrategy = overflowStrategy;
  }

  /** {@inheritDoc} */
  public Map<V, Double> getScores() {
    if (scores == null) {
      compute();
    }
    return Collections.unmodifiableMap(scores);
  }

  /** {@inheritDoc} */
  public Double getVertexScore(V v) {
    if (!graph.nodes().contains(v)) {
      throw new IllegalArgumentException("Cannot return score of unknown vertex");
    }
    if (scores == null) {
      compute();
    }
    return scores.get(v);
  }

  /** Compute the centrality index */
  private void compute() {
    // initialize result container
    scores = new HashMap<>();
    graph.nodes().forEach(v -> scores.put(v, 0.0));

    // compute for each source
    graph.nodes().forEach(this::compute);

    // For undirected graph, divide scores by two as each shortest path
    // considered twice.
    if (!graph.isDirected()) {
      scores.forEach((v, score) -> scores.put(v, score / 2));
    }

    if (normalize) {
      int n = graph.nodes().size();
      int normalizationFactor = (n - 1) * (n - 2);
      if (normalizationFactor != 0) {
        scores.forEach((v, score) -> scores.put(v, score / normalizationFactor));
      }
    }
  }

  private void compute(V s) {
    // initialize
    ArrayDeque<V> stack = new ArrayDeque<>();
    Map<V, List<V>> predecessors = new HashMap<>();
    graph.nodes().forEach(w -> predecessors.put(w, new ArrayList<>()));

    // Number of shortest paths from s to v
    Map<V, Long> sigma = new HashMap<>();
    graph.nodes().forEach(t -> sigma.put(t, 0L));
    sigma.put(s, 1L);

    // Distance of the shortest path from s to v (unweighted = hop count)
    Map<V, Integer> distance = new HashMap<>();
    graph.nodes().forEach(t -> distance.put(t, -1));
    distance.put(s, 0);

    // BFS for unweighted graphs
    Queue<V> queue = new ArrayDeque<>();
    queue.add(s);

    // 1. compute the length and the number of shortest paths between all s to v
    while (!queue.isEmpty()) {
      V v = queue.remove();
      stack.push(v);

      for (V w : graph.successors(v)) {
        // w found for the first time?
        if (distance.get(w) < 0) {
          queue.add(w);
          distance.put(w, distance.get(v) + 1);
          sigma.put(w, sigma.get(v));
          predecessors.get(w).add(v);
        }
        // shortest path to w via v? (same distance)
        else if (distance.get(w).equals(distance.get(v) + 1)) {
          long wCounter = sigma.get(w);
          long vCounter = sigma.get(v);
          long sum = wCounter + vCounter;
          if (overflowStrategy.equals(OverflowStrategy.THROW_EXCEPTION_ON_OVERFLOW) && sum < 0) {
            throw new ArithmeticException("long overflow");
          }
          sigma.put(w, sum);
          predecessors.get(w).add(v);
        }
      }
    }

    // 2. sum all pair dependencies.
    // The pair-dependency of s and v in w
    Map<V, Double> dependency = new HashMap<>();
    graph.nodes().forEach(v -> dependency.put(v, 0.0));
    // S returns vertices in order of non-increasing distance from s
    while (!stack.isEmpty()) {
      V w = stack.pop();
      for (V v : predecessors.get(w)) {
        dependency.put(
            v,
            dependency.get(v)
                + (sigma.get(v).doubleValue() / sigma.get(w).doubleValue())
                    * (1 + dependency.get(w)));
      }
      if (!w.equals(s)) {
        scores.put(w, scores.get(w) + dependency.get(w));
      }
    }
  }
}
