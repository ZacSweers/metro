// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.android

import android.app.Application

/**
 * Interface for an Android [Application] class to implement to provide a single
 * [MetroAppComponentProviders] instance and automatic component creation via
 * [MetroAppComponentFactory].
 */
public interface MetroApplication {
  /**
   * A [MetroAppComponentProviders] instance to access
   *
   * ```kotlin
   * class MetroApp : Application(), MetroApplication {
   *   /** Holder reference for the app graph for [MetroAppComponentFactory]. */
   *   val appGraph by lazy { createGraph<AppGraph>() }
   *
   *   override val appComponentProviders: MetroAppComponentProviders
   *     get() = appGraph
   * }
   *
   * @DependencyGraph
   * interface AppGraph : MetroAppComponentProviders {
   *   // ...
   * }
   * ```
   */
  public val appComponentProviders: MetroAppComponentProviders
}
