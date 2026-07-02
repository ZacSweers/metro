// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.js.test.runners.commonConfigurationForJsTest
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder

fun TestConfigurationBuilder.setupMetroJsPipeline(@Suppress("UNUSED_PARAMETER") parser: FirParser) {
  commonConfigurationForJsTest()
}
