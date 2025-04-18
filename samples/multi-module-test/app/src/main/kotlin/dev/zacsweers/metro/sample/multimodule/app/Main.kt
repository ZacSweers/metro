// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.app

import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metro.sample.multimodule.aggregator.AggregatorGraph
import dev.zacsweers.metro.sample.multimodule.child.ChildGraph
import dev.zacsweers.metro.sample.multimodule.parent.ParentGraph

fun createAppGraph(): AppGraph {
  // Create the parent graph
  val parentGraph = createGraph<ParentGraph>()

  // Create the child graph, extending the parent graph
  val childGraph = createGraphFactory<ChildGraph.Factory>().create(parentGraph)

  // Create the aggregator graph
  val aggregatorGraph = createGraph<AggregatorGraph>()

  // Create the app graph, extending the parent graph and including the child and aggregator graphs
  val appGraph =
    createGraphFactory<AppGraph.Factory>()
      .create(childGraph = childGraph, aggregatorGraph = aggregatorGraph)

  return appGraph
}

/**
 * Main entry point for the multi-module Metro sample application. This demonstrates how to create
 * and use all the components together.
 */
fun main() {
  val appGraph = createAppGraph()
  appGraph.application.run()
}
