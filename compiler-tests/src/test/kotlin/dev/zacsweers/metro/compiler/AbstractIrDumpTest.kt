// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.CompatContext
import kotlin.getValue
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.backend.BlackBoxCodegenSuppressor
import org.jetbrains.kotlin.test.backend.handlers.IrSourceRangesDumpHandler
import org.jetbrains.kotlin.test.backend.handlers.IrTextDumpHandler
import org.jetbrains.kotlin.test.backend.handlers.IrTreeVerifierHandler
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.builders.configureIrHandlersStep
import org.jetbrains.kotlin.test.configuration.additionalK2ConfigurationForIrTextTest
import org.jetbrains.kotlin.test.configuration.commonHandlersForCodegenTest
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.DUMP_IR
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.DUMP_KT_IR
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.IGNORE_DEXING
import org.jetbrains.kotlin.test.directives.ConfigurationDirectives.WITH_STDLIB
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives.DISABLE_GENERATED_FIR_TAGS
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.FULL_JDK
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.JVM_TARGET
import org.jetbrains.kotlin.test.directives.TestPhaseDirectives.LATEST_PHASE_IN_PIPELINE
import org.jetbrains.kotlin.test.directives.model.SimpleDirective
import org.jetbrains.kotlin.test.runners.AbstractKotlinCompilerWithTargetBackendTest
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.PhasedPipelineChecker
import org.jetbrains.kotlin.test.services.TestPhase
import org.jetbrains.kotlin.utils.bind

/**
 * IR dump test that uses [MetroIrPrettyKotlinDumpHandler] (with `betterDumpKotlinLike`) instead of
 * the built-in `IrPrettyKotlinDumpHandler`.
 *
 * We don't extend `AbstractFirLightTreeJvmIrTextTest` because its parent registers the built-in
 * handler which can't be removed and would delete our golden files.
 */
open class AbstractIrDumpTest : AbstractKotlinCompilerWithTargetBackendTest(TargetBackend.JVM_IR) {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return ClasspathBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) =
    with(builder) {
      setupMetroJvmPipeline(FirParser.LightTree)
      commonHandlersForCodegenTest()
      additionalK2ConfigurationForIrTextTest(FirParser.LightTree)

      // Register IR handlers with our custom handler instead of IrPrettyKotlinDumpHandler
      val compatContext = CompatContext.create()
      configureIrHandlersStep {
        useHandlers(
          ::IrTextDumpHandler,
          ::IrTreeVerifierHandler,
          { testServices, artifactKind ->
            MetroIrPrettyKotlinDumpHandler(testServices, artifactKind, compatContext)
          },
          ::IrSourceRangesDumpHandler,
        )
      }

      useAfterAnalysisCheckers(
        ::BlackBoxCodegenSuppressor,
        ::PhasedPipelineChecker.bind(TestPhase.BACKEND),
      )
      enableMetaInfoHandler()

      configurePlugin(compatContext)

      defaultDirectives {
        JVM_TARGET.with(JvmTarget.JVM_11)
        +FULL_JDK
        +WITH_STDLIB
        commonMetroTestDirectives()

        LATEST_PHASE_IN_PIPELINE with TestPhase.BACKEND
        +IGNORE_DEXING // Avoids loading R8 from the classpath.
        +DISABLE_GENERATED_FIR_TAGS

        -DUMP_IR
        -DUMP_KT_IR
        +MetroDirectives.METRO_DUMP_KT_IR

        // Disable the new SKIP_NEW_KOTLIN_REFLECT_COMPATIBILITY_CHECK, we don't need this here
        // However, this fails in our infra _before_ we
        SKIP_NEW_KOTLIN_REFLECT_COMPATIBILITY_CHECK_DIRECTIVE?.let { -it }
      }

      useMetaTestConfigurators(::MetroTestConfigurator)
    }
}

private val SKIP_NEW_KOTLIN_REFLECT_COMPATIBILITY_CHECK_DIRECTIVE: SimpleDirective? by lazy {
  CodegenTestDirectives::class
    .java
    .declaredMethods
    .find { it.name == "getSKIP_NEW_KOTLIN_REFLECT_COMPATIBILITY_CHECK" }
    ?.let { it.invoke(CodegenTestDirectives) as SimpleDirective }
}
