// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.io.File
import org.jetbrains.kotlin.platform.wasm.WasmTarget
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

// KotlinStandardLibrariesPathProvider was changed to an interface in 2.3.20
// fullWasmStdlib() was added as a new abstract member in 2.4.0-Beta1
// kotlinTestWasmKLib() was added as a new abstract member in 2.4.0-Beta1
abstract class MetroKotlinStandardLibrariesPathProvider : KotlinStandardLibrariesPathProvider {
  abstract override fun webStdlibForTests(): File

  override fun fullWasmStdlib(target: WasmTarget): File = fullWasmStdlib(target.alias)

  override fun kotlinTestWasmKLib(target: WasmTarget): File = kotlinTestWasmKLib(target.alias)

  abstract fun fullWasmStdlib(target: String): File

  abstract fun kotlinTestWasmKLib(target: String): File
}
