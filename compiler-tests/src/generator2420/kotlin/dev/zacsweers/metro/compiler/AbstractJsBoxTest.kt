// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.js.test.runners.AbstractJsTest
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.JsEnvironmentConfigurationDirectives

open class KotlinJsBoxTestBase :
  AbstractJsTest(
    pathToTestDir = "compiler-tests/src/test/data/box",
    testGroupOutputDirPrefix = "box/js/",
    parser = FirParser.LightTree,
  ) {
  override fun configure(builder: TestConfigurationBuilder) =
    with(builder) {
      // give each JS box test its own output directory, so no race conditions happen
      defaultDirectives {
        JsEnvironmentConfigurationDirectives.TEST_GROUP_OUTPUT_DIR_PREFIX with
          "box/js/${testInfo.className}"
      }
      super.configure(builder)
    }
}
