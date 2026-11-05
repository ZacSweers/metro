// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.MetroOptions
import kotlin.test.fail
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.js.JsPlatforms
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.konan.NativePlatforms
import org.jetbrains.kotlin.platform.wasm.WasmPlatforms
import org.junit.Test

class IrTest {

  private val metroOptions = MetroOptions()

  @Test
  fun `superinterfaces preserve ancestor order and shared inheritance paths`() {
    fun declaration(name: String, kind: ClassKind, vararg parents: IrClass): IrClass {
      return IrFactoryImpl.buildClass {
          this.name = Name.identifier(name)
          this.kind = kind
          modality = Modality.ABSTRACT
        }
        .apply { superTypes = parents.map { it.typeWith() } }
    }

    val root = declaration("Root", ClassKind.INTERFACE)
    val left = declaration("Left", ClassKind.INTERFACE, root)
    val right = declaration("Right", ClassKind.INTERFACE, root)
    val base = declaration("Base", ClassKind.CLASS, left)
    val target = declaration("Target", ClassKind.CLASS, base, right)

    assertThat(target.allSuperInterfaces()).containsExactly(root, left, root, right).inOrder()
    assertThat(left.allSuperInterfaces()).containsExactly(root, left).inOrder()
  }

  // region shouldCheckSignatureCarrierParamMismatches tests

  @Test
  fun `signature carrier mismatch check returns false when enableKlibParamsCheck is false`() {
    val options = metroOptions.toBuilder().enableKlibParamsCheck(false).build()

    // Should return false regardless of platform when the option is disabled
    assertThat(
        shouldCheckSignatureCarrierParamMismatches(options, JvmPlatforms.defaultJvmPlatform) {
          true
        },
      )
      .isFalse()
    assertThat(
        shouldCheckSignatureCarrierParamMismatches(
          options,
          NativePlatforms.unspecifiedNativePlatform,
        ) {
          true
        },
      )
      .isFalse()
    assertThat(
        shouldCheckSignatureCarrierParamMismatches(options, JsPlatforms.defaultJsPlatform) { true },
      )
      .isFalse()
    assertThat(shouldCheckSignatureCarrierParamMismatches(options, WasmPlatforms.Default) { true })
      .isFalse()
    assertThat(shouldCheckSignatureCarrierParamMismatches(options, null) { true }).isFalse()
  }

  @Test
  fun `signature carrier mismatch check returns true for Native platform when enabled`() {
    val options = metroOptions.toBuilder().enableKlibParamsCheck(true).build()

    assertThat(
        shouldCheckSignatureCarrierParamMismatches(
          options,
          NativePlatforms.unspecifiedNativePlatform,
        ) {
          false
        },
      )
      .isTrue()
  }

  @Test
  fun `signature carrier mismatch check returns true for JS platform when enabled`() {
    val options = metroOptions.toBuilder().enableKlibParamsCheck(true).build()

    assertThat(
        shouldCheckSignatureCarrierParamMismatches(options, JsPlatforms.defaultJsPlatform) {
          false
        },
      )
      .isTrue()
  }

  @Test
  fun `signature carrier mismatch check returns true for Wasm platform when enabled`() {
    val options = metroOptions.toBuilder().enableKlibParamsCheck(true).build()

    assertThat(shouldCheckSignatureCarrierParamMismatches(options, WasmPlatforms.Default) { false })
      .isTrue()
  }

  @Test
  fun `signature carrier mismatch check returns true for JVM metadata annotations`() {
    val options = metroOptions.toBuilder().enableKlibParamsCheck(true).build()

    assertThat(
        shouldCheckSignatureCarrierParamMismatches(options, JvmPlatforms.defaultJvmPlatform) {
          true
        },
      )
      .isTrue()
  }

  @Test
  fun `signature carrier mismatch check returns false without JVM metadata annotations`() {
    val options = metroOptions.toBuilder().enableKlibParamsCheck(true).build()

    assertThat(
        shouldCheckSignatureCarrierParamMismatches(options, JvmPlatforms.defaultJvmPlatform) {
          false
        },
      )
      .isFalse()
  }

  @Test
  fun `signature carrier mismatch check returns false for null platform`() {
    val options = metroOptions.toBuilder().enableKlibParamsCheck(true).build()

    assertThat(shouldCheckSignatureCarrierParamMismatches(options, null) { true }).isFalse()
  }

  @Suppress("RETURN_VALUE_NOT_USED")
  @Test
  fun `signature carrier mismatch check lambda is only called for JVM platform`() {
    val options = metroOptions.toBuilder().enableKlibParamsCheck(true).build()
    // Native
    shouldCheckSignatureCarrierParamMismatches(
      options,
      NativePlatforms.unspecifiedNativePlatform,
    ) {
      fail("Should not be called")
    }

    // JS
    shouldCheckSignatureCarrierParamMismatches(options, JsPlatforms.defaultJsPlatform) {
      fail("Should not be called")
    }

    // Wasm
    shouldCheckSignatureCarrierParamMismatches(options, WasmPlatforms.Default) {
      fail("Should not be called")
    }

    // JVM - lambda should be called
    var lambdaCalled = false
    shouldCheckSignatureCarrierParamMismatches(options, JvmPlatforms.defaultJvmPlatform) {
      lambdaCalled = true
      false
    }
    assertThat(lambdaCalled).isTrue()
  }

  // endregion
}
