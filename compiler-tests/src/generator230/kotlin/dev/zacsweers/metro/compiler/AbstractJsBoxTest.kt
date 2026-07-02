// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.js.test.fir.AbstractFirJsTest
import org.jetbrains.kotlin.test.FirParser

open class KotlinJsBoxTestBase :
  AbstractFirJsTest(
    pathToTestDir = "compiler-tests/src/test/data/box",
    testGroupOutputDirPrefix = "box/js/",
    parser = FirParser.LightTree,
  )
