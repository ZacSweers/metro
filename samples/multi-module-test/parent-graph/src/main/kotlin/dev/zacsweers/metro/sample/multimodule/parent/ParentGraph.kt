// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.parent

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.sample.multimodule.AppScope
import dev.zacsweers.metro.sample.multimodule.MessageService
import dev.zacsweers.metro.sample.multimodule.NumberService

/**
 * Parent graph that provides basic services. This graph is extendable, allowing child graphs to
 * extend it.
 */
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class, isExtendable = true)
interface ParentGraph {
  val messageService: MessageService

  /** Get the number service. */
  val numberService: NumberService

  /** Provides a number service implementation. */
  @Provides fun provideNumberService(): NumberService = ParentNumberService()
}

/** Implementation of MessageService for the parent graph. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ParentMessageService : MessageService {
  override fun getMessage(): String = "Message from parent"
}

/** Implementation of NumberService for the parent graph. */
class ParentNumberService : NumberService {
  private var count = 0

  override fun getNumber(): Int = count++
}
