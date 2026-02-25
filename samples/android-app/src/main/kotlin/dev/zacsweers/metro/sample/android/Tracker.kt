// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

interface Tracker {
  fun trackIncrements()

  fun trackDecrements()
}

@ContributesBinding(AppScope::class)
@Inject
class TrackerImpl : Tracker {
  override fun trackIncrements() {}

  override fun trackDecrements() {}
}
