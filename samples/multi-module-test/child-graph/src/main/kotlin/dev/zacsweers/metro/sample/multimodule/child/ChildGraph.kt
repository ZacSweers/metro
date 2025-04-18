// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.child

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Extends
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.sample.multimodule.ChildScope
import dev.zacsweers.metro.sample.multimodule.ItemService
import dev.zacsweers.metro.sample.multimodule.MessageService
import dev.zacsweers.metro.sample.multimodule.NumberService
import dev.zacsweers.metro.sample.multimodule.parent.ParentGraph

/**
 * Child graph that extends the parent graph. This graph demonstrates graph extension by extending
 * the parent graph.
 */
@SingleIn(ChildScope::class)
@DependencyGraph(ChildScope::class, isExtendable = true)
interface ChildGraph {
  /** Access the message service from the parent graph. */
  val messageService: MessageService

  /** Access the number service from the parent graph. */
  val numberService: NumberService

  /** Access the item service provided by this graph. */
  val itemService: ItemService

  /** Access a custom message that combines parent and child data. */
  @Named("combined") val combinedMessage: String

  /** Factory for creating the child graph. */
  @DependencyGraph.Factory
  interface Factory {
    /** Create a child graph with the parent graph as a dependency. */
    fun create(@Extends parentGraph: ParentGraph): ChildGraph
  }

  /** Provides an item service implementation. */
  @Provides @SingleIn(ChildScope::class) fun provideItemService(): ItemService = ChildItemService()

  /** Provides a combined message using both parent and child services. */
  @Named("combined")
  @Provides
  fun provideCombinedMessage(
    messageService: MessageService,
    numberService: NumberService,
    itemService: ItemService,
  ): String {
    return "${messageService.getMessage()} - Number: ${numberService.getNumber()} - Items: ${itemService.getItems().size}"
  }
}

/** Implementation of ItemService for the child graph. */
@Inject
class ChildItemService : ItemService {
  override fun getItems(): List<String> = listOf("Item 1", "Item 2", "Item 3")
}
