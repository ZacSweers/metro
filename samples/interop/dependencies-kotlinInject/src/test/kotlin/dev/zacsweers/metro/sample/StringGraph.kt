// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

@DependencyGraph
interface StringGraph {
  val message: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides message: String): StringGraph
  }
}
