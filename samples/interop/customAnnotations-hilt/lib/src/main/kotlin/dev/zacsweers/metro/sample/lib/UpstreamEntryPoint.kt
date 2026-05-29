// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.lib

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Upstream Hilt entry point picked up by downstream `@DependencyGraph(Singleton::class)` graphs.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpstreamEntryPoint {
  val upstreamMessage: UpstreamMessage
}
