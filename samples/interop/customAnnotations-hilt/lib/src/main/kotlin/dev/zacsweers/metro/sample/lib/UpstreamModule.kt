// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.lib

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Upstream Hilt module contributed to downstream singleton-scoped Metro graphs. */
@Module
@InstallIn(SingletonComponent::class)
class UpstreamModule {
  @Provides fun provideUpstreamMessage(): UpstreamMessage = UpstreamMessage("Hello from Hilt")
}

data class UpstreamMessage(val text: String)
