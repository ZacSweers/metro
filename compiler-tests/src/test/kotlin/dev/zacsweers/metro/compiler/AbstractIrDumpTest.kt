// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.getValue
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.DUMP_IR
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.DUMP_KT_IR
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.IGNORE_DEXING
import org.jetbrains.kotlin.test.directives.ConfigurationDirectives.WITH_STDLIB
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives.DISABLE_GENERATED_FIR_TAGS
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.FULL_JDK
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.JVM_TARGET
import org.jetbrains.kotlin.test.directives.model.SimpleDirective
import org.jetbrains.kotlin.test.runners.ir.AbstractFirLightTreeJvmIrTextTest
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractIrDumpTest : AbstractFirLightTreeJvmIrTextTest() {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return ClasspathBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) {
    super.configure(builder)

    with(builder) {
      configurePlugin()

      defaultDirectives {
        JVM_TARGET.with(JvmTarget.JVM_11)
        +FULL_JDK
        +WITH_STDLIB
        commonMetroTestDirectives()

        +IGNORE_DEXING // Avoids loading R8 from the classpath.
        +DISABLE_GENERATED_FIR_TAGS

        -DUMP_IR
        +DUMP_KT_IR

        // Disable the new SKIP_NEW_KOTLIN_REFLECT_COMPATIBILITY_CHECK, we don't need this here
        // However, this fails in our infra _before_ we
        SKIP_NEW_KOTLIN_REFLECT_COMPATIBILITY_CHECK_DIRECTIVE?.let { -it }
      }

      useMetaTestConfigurators(::MetroTestConfigurator)
    }
  }
}

private val SKIP_NEW_KOTLIN_REFLECT_COMPATIBILITY_CHECK_DIRECTIVE: SimpleDirective? by lazy {
  CodegenTestDirectives::class
    .java
    .declaredMethods
    .find { it.name == "getSKIP_NEW_KOTLIN_REFLECT_COMPATIBILITY_CHECK" }
    ?.let { it.invoke(CodegenTestDirectives) as SimpleDirective }
}
