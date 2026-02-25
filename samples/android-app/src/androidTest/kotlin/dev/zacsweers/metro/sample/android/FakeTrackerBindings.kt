// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@BindingContainer
object FakeTrackerBindings {

  val tracker = FakeTracker()

  @SingleIn(AppScope::class)
  @Provides
  fun provideTracker(): Tracker {
    return tracker
  }
}

class FakeTracker : Tracker {

  var increments = 0
  var decrements = 0

  override fun trackIncrements() {
    increments++
  }

  override fun trackDecrements() {
    decrements++
  }
}
