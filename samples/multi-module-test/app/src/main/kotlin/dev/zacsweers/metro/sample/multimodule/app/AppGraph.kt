// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.app

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Extends
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.sample.multimodule.AppScope
import dev.zacsweers.metro.sample.multimodule.aggregator.AggregatorGraph
import dev.zacsweers.metro.sample.multimodule.child.ChildGraph
import dev.zacsweers.metro.sample.multimodule.parent.ParentGraph

@SingleIn(AppScope::class)
@DependencyGraph
interface AppGraph {
  val application: Application

  // Exposed for testing
  val parentGraph: ParentGraph
  val childGraph: ChildGraph
  @Named("aggregation_summary") val aggregationSummary: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      @Extends childGraph: ChildGraph,
      @Includes aggregatorGraph: AggregatorGraph,
    ): AppGraph
  }
}
