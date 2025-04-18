// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import android.app.Activity
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provider
import kotlin.reflect.KClass

@DependencyGraph(AppScope::class)
interface AppGraph {
  /**
   * A multibinding map of activity classes to their providers accessible for
   * [MetroAppComponentFactory].
   */
  @Multibinds val activityProviders: Map<KClass<out Activity>, Provider<Activity>>
}
