// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.aggregator

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.sample.multimodule.AppScope
import dev.zacsweers.metro.sample.multimodule.ItemService
import dev.zacsweers.metro.sample.multimodule.MapService
import dev.zacsweers.metro.sample.multimodule.MessageService

/**
 * Aggregator graph that demonstrates how to aggregate multiple contributions. This graph uses
 * AppScope, so it will automatically include all contributions to AppScope.
 */
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AggregatorGraph {

  /**
   * Access the set of item services. This will include all ItemService implementations contributed
   * via @ContributesIntoSet.
   */
  @Multibinds val itemServices: Set<ItemService>

  /**
   * Access the map of map services. This will include all MapService implementations contributed
   * via @ContributesIntoMap.
   */
  @Multibinds val mapServices: Map<String, MapService>

  /** Access a summary of all aggregated contributions. */
  @Named("aggregation_summary") val aggregationSummary: String

  /** Provides a summary of all aggregated contributions. */
  @Named("aggregation_summary")
  @Provides
  fun provideAggregationSummary(
    messageService: MessageService,
    @Named("contributed") contributedMessageService: MessageService,
    @Named("contributed") contributedMessage: String,
    itemServices: Set<ItemService>,
    mapServices: Map<String, MapService>,
  ): String {
    return buildString {
      appendLine("Aggregation Summary:")
      appendLine("- Default message: ${messageService.getMessage()}")
      appendLine("- Contributed message service: ${contributedMessageService.getMessage()}")
      appendLine("- Contributed message: $contributedMessage")
      appendLine("- Item services count: ${itemServices.size}")
      appendLine("- Items from all services: ${itemServices.flatMap { it.getItems() }}")
      appendLine("- Map services count: ${mapServices.size}")
      appendLine("- Map services keys: ${mapServices.keys}")
    }
  }
}
