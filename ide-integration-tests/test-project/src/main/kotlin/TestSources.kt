// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package test

import dev.zacsweers.metro.*

// TODO: Replace with real Metro code that exercises FIR generators and checkers.
// Examples: @DependencyGraph, @Provides, @Inject constructors, binding validation.

@AssistedInject
class AssistedExampleWithGeneratedFactory(@Assisted private val int: Int) {
  fun example() {
    println(int)
  }
}

// Diagnostic should show for mismatched assisted params
@AssistedInject
class AssistedWithMismatchedParams(@Assisted private val int: Int) {
  @AssistedFactory
  interface Factory {
    // Missing int param
    fun create(): AssistedWithMismatchedParams
  }
}

