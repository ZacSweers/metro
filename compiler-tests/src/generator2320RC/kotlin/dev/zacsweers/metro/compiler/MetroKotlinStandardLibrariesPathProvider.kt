// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.io.File
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

// KotlinStandardLibrariesPathProvider was changed to an interface in 2.3.20
// webStdlibForTests() was added as a new abstract member in 2.3.20-RC
abstract class MetroKotlinStandardLibrariesPathProvider : KotlinStandardLibrariesPathProvider {
  abstract override fun webStdlibForTests(): File
}
