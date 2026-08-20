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

  @Test
  fun `provider type sets reuse shared options for every provider configuration`() {
    for (enableFunctionProviders in listOf(false, true)) {
      for (enableSuspendProviders in listOf(false, true)) {
        val options =
          MetroOptions()
            .toBuilder()
            .enableFunctionProviders(enableFunctionProviders)
            .enableSuspendProviders(enableSuspendProviders)
            .build()
        val classIds = ClassIds(options)

        assertThat(classIds.suspendProviderModelingTypes)
          .isSameInstanceAs(options.suspendProviderModelingTypes)
        assertThat(classIds.suspendProviderTypes).isSameInstanceAs(options.suspendProviderTypes)
        assertThat(classIds.suspendLazyTypes).isSameInstanceAs(options.suspendLazyTypes)
        assertThat(classIds.function0Types).isSameInstanceAs(options.function0Types)

        val expectedModelingTypes = buildSet {
          add(MetroClassIds.suspendProvider)
          if (enableFunctionProviders) {
            add(MetroClassIds.suspendFunction0)
          }
        }
        val expectedSuspendProviderTypes = buildSet {
          add(MetroClassIds.suspendProvider)
          if (enableFunctionProviders && enableSuspendProviders) {
            add(MetroClassIds.suspendFunction0)
          }
        }
        val expectedFunctionTypes = buildSet {
          if (enableFunctionProviders) {
            add(MetroClassIds.function0)
            if (enableSuspendProviders) {
              add(MetroClassIds.suspendFunction0)
            }
          }
        }

        assertThat(classIds.suspendProviderModelingTypes)
          .containsExactlyElementsIn(expectedModelingTypes)
        assertThat(classIds.suspendProviderTypes)
          .containsExactlyElementsIn(expectedSuspendProviderTypes)
        assertThat(classIds.suspendLazyTypes).containsExactly(MetroClassIds.suspendLazy)
        assertThat(classIds.function0Types).containsExactlyElementsIn(expectedFunctionTypes)
      }
    }
  }
}
