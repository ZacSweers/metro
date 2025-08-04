// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalTime::class)

package dev.zacsweers.metro.sample.multimodule.compose.app

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@BindingContainer
@ContributesTo(AppScope::class)
internal object ClockContainer {
  @Provides fun clock(): Clock = Clock.System
}
