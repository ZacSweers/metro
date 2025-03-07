// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample

import dagger.BindsInstance
import dagger.Component

@Component
interface StringComponent {
  val message: String

  @Component.Factory
  interface Factory {
    fun create(@BindsInstance message: String): StringComponent
  }
}
