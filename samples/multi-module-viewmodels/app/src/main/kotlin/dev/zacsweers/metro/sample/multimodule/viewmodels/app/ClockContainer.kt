// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.viewmodels.app

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.sample.multimodule.viewmodels.core.Clock

@BindingContainer
@ContributesTo(AppScope::class)
internal object ClockContainer {
  @Provides fun clock(): Clock = Clock { System.currentTimeMillis() }
}
