// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.MetroOptions
import kotlin.test.fail
import org.jetbrains.kotlin.platform.js.JsPlatforms
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.konan.NativePlatforms
import org.jetbrains.kotlin.platform.wasm.WasmPlatforms
import org.junit.Test

class IrTest {

  // region shouldCheckMirrorParamMismatches tests

  @Test
  fun `shouldCheckMirrorParamMismatches returns false when enableKlibParamsCheck is false`() {
    val options = MetroOptions(enableKlibParamsCheck = false)

    // Should return false regardless of platform when the option is disabled
    assertThat(shouldCheckMirrorParamMismatches(options, JvmPlatforms.defaultJvmPlatform) { true })
      .isFalse()
    assertThat(
        shouldCheckMirrorParamMismatches(options, NativePlatforms.unspecifiedNativePlatform) {
          true
        }
      )
      .isFalse()
    assertThat(shouldCheckMirrorParamMismatches(options, JsPlatforms.defaultJsPlatform) { true })
      .isFalse()
    assertThat(shouldCheckMirrorParamMismatches(options, WasmPlatforms.Default) { true }).isFalse()
    assertThat(shouldCheckMirrorParamMismatches(options, null) { true }).isFalse()
  }

  @Test
  fun `shouldCheckMirrorParamMismatches returns true for Native platform when enabled`() {
    val options = MetroOptions(enableKlibParamsCheck = true)

    assertThat(
        shouldCheckMirrorParamMismatches(options, NativePlatforms.unspecifiedNativePlatform) {
          false
        }
      )
      .isTrue()
  }

  @Test
  fun `shouldCheckMirrorParamMismatches returns true for JS platform when enabled`() {
    val options = MetroOptions(enableKlibParamsCheck = true)

    assertThat(shouldCheckMirrorParamMismatches(options, JsPlatforms.defaultJsPlatform) { false })
      .isTrue()
  }

  @Test
  fun `shouldCheckMirrorParamMismatches returns true for Wasm platform when enabled`() {
    val options = MetroOptions(enableKlibParamsCheck = true)

    assertThat(shouldCheckMirrorParamMismatches(options, WasmPlatforms.Default) { false }).isTrue()
  }

  @Test
  fun `shouldCheckMirrorParamMismatches returns true for JVM when AnnotationsInMetadata is enabled`() {
    val options = MetroOptions(enableKlibParamsCheck = true)

    assertThat(shouldCheckMirrorParamMismatches(options, JvmPlatforms.defaultJvmPlatform) { true })
      .isTrue()
  }

  @Test
  fun `shouldCheckMirrorParamMismatches returns false for JVM when AnnotationsInMetadata is disabled`() {
    val options = MetroOptions(enableKlibParamsCheck = true)

    assertThat(shouldCheckMirrorParamMismatches(options, JvmPlatforms.defaultJvmPlatform) { false })
      .isFalse()
  }

  @Test
  fun `shouldCheckMirrorParamMismatches returns false for null platform`() {
    val options = MetroOptions(enableKlibParamsCheck = true)

    assertThat(shouldCheckMirrorParamMismatches(options, null) { true }).isFalse()
  }

  @Suppress("RETURN_VALUE_NOT_USED")
  @Test
  fun `shouldCheckMirrorParamMismatches lambda is only called for JVM platform`() {
    val options = MetroOptions(enableKlibParamsCheck = true)
    // Native
    shouldCheckMirrorParamMismatches(options, NativePlatforms.unspecifiedNativePlatform) {
      fail("Should not be called")
    }

    // JS
    shouldCheckMirrorParamMismatches(options, JsPlatforms.defaultJsPlatform) {
      fail("Should not be called")
    }

    // Wasm
    shouldCheckMirrorParamMismatches(options, WasmPlatforms.Default) {
      fail("Should not be called")
    }

    // JVM - lambda should be called
    var lambdaCalled = false
    shouldCheckMirrorParamMismatches(options, JvmPlatforms.defaultJvmPlatform) {
      lambdaCalled = true
      false
    }
    assertThat(lambdaCalled).isTrue()
  }

  // endregion
}
