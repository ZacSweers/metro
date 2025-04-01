// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.app

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.sample.multimodule.AppScope
import dev.zacsweers.metro.sample.multimodule.child.ChildGraph
import dev.zacsweers.metro.sample.multimodule.parent.ParentGraph

/** Main application class that demonstrates how to use all the components. */
@SingleIn(AppScope::class)
@Inject
class Application(
  private val parentGraph: ParentGraph,
  private val childGraph: ChildGraph,
  @Named("aggregation_summary") private val aggregationSummary: String,
) {
  /** Run the application and print information about all the components. */
  // TODO create a unit test that asserts all these
  fun run() {
    println("=== Multi-Module Metro Sample ===")
    println()

    // Parent graph
    println("=== Parent Graph ===")
    println("Message: ${parentGraph.messageService.getMessage()}")
    println("Number: ${parentGraph.numberService.getNumber()}")
    println()

    // Child graph
    println("=== Child Graph ===")
    println("Message from parent: ${childGraph.messageService.getMessage()}")
    println("Number from parent: ${childGraph.numberService.getNumber()}")
    println("Items: ${childGraph.itemService.getItems()}")
    println("Combined message: ${childGraph.combinedMessage}")
    println()

    // Aggregator graph
    println("=== Aggregator Graph ===")
    println(aggregationSummary)
    println()
  }
}
