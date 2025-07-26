// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.android

/**
 * Interface for an Android Application to implement to provide a single [MetroAndroidAppGraph] and
 * automatic component creation via [MetroAppComponentFactory].
 */
interface MetroApplication {
  /**
   * Create a MetroAndroidAppGraph for building Android Components.
   *
   * ```kotlin
   * class MetroApp : Application(), MetroApplication {
   *   /** Holder reference for the app graph for [MetroAppComponentFactory]. */
   *   val appGraph by lazy { createGraph<AppGraph>() }
   * }
   * ```
   */
  val appGraph: MetroAndroidAppGraph
}
