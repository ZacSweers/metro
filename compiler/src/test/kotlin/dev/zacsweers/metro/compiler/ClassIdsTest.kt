// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.symbols.Symbols
import kotlin.test.Test

class ClassIdsTest {
  @Test
  fun `suspend function providers require both provider options`() {
    val functionProvidersOnly =
      ClassIds(
        MetroOptions()
          .toBuilder()
          .enableFunctionProviders(true)
          .enableSuspendProviders(false)
          .build()
      )
    assertThat(functionProvidersOnly.function0Types)
      .doesNotContain(Symbols.ClassIds.suspendFunction0)
    assertThat(functionProvidersOnly.suspendProviderTypes)
      .doesNotContain(Symbols.ClassIds.suspendFunction0)

    val bothEnabled =
      ClassIds(
        MetroOptions()
          .toBuilder()
          .enableFunctionProviders(true)
          .enableSuspendProviders(true)
          .build()
      )
    assertThat(bothEnabled.function0Types).contains(Symbols.ClassIds.suspendFunction0)
    assertThat(bothEnabled.suspendProviderTypes).contains(Symbols.ClassIds.suspendFunction0)
  }
}
