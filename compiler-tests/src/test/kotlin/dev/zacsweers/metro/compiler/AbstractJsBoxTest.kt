// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.js.test.runners.AbstractJsTest
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractJsBoxTest :
  AbstractJsTest(
    pathToTestDir = "compiler-tests/src/test/data/box",
    testGroupOutputDirPrefix = "box/js/",
    parser = FirParser.LightTree,
  ) {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return ClasspathBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) {
    super.configure(builder)

    with(builder) {
      configurePlugin()

      useSourcePreprocessor(::KotlinTestImportPreprocessor)

      useMetaTestConfigurators(::MetroTestConfigurator)

      defaultDirectives { commonMetroTestDirectives() }
    }
  }
}

open class AbstractJsFastInitBoxTest : AbstractJsBoxTest() {
  override fun configure(builder: TestConfigurationBuilder) {
    super.configure(builder)

    with(builder) { defaultDirectives { MetroDirectives.ENABLE_SWITCHING_PROVIDERS.with(true) } }
  }
}

open class AbstractJsContributionProvidersBoxTest : AbstractJsBoxTest() {
  override fun configure(builder: TestConfigurationBuilder) {
    super.configure(builder)

    with(builder) {
      defaultDirectives {
        // Only run on 2.3.21+ due to top-level requirements
        MetroDirectives.MIN_COMPILER_VERSION.with("2.3.21")
        MetroDirectives.GENERATE_CONTRIBUTION_HINTS.with(true)
        +MetroDirectives.GENERATE_CONTRIBUTION_HINTS_IN_FIR

        MetroDirectives.GENERATE_CONTRIBUTION_PROVIDERS.with(true)
      }
    }
  }
}
