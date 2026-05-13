// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.backend.BlackBoxCodegenSuppressor.SuppressionChecker
import org.jetbrains.kotlin.test.backend.handlers.NoFirCompilationErrorsHandler
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.builders.configureFirHandlersStep
import org.jetbrains.kotlin.test.builders.configureIrHandlersStep
import org.jetbrains.kotlin.test.builders.configureJvmArtifactsHandlersStep
import org.jetbrains.kotlin.test.configuration.commonBackendHandlersForCodegenTest
import org.jetbrains.kotlin.test.configuration.configureCommonDiagnosticTestPaths
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives.IGNORE_DEXING
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives.DISABLE_GENERATED_FIR_TAGS
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.FULL_JDK
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives.JVM_TARGET
import org.jetbrains.kotlin.test.directives.LanguageSettingsDirectives.LANGUAGE
import org.jetbrains.kotlin.test.directives.TestPhaseDirectives.LATEST_PHASE_IN_PIPELINE
import org.jetbrains.kotlin.test.directives.TestPhaseDirectives.RUN_PIPELINE_TILL
import org.jetbrains.kotlin.test.directives.configureFirParser
import org.jetbrains.kotlin.test.frontend.fir.handlers.FirCfgConsistencyHandler
import org.jetbrains.kotlin.test.frontend.fir.handlers.FirCfgDumpHandler
import org.jetbrains.kotlin.test.frontend.fir.handlers.FirDumpHandler
import org.jetbrains.kotlin.test.frontend.fir.handlers.FirInferenceLogsHandler
import org.jetbrains.kotlin.test.frontend.fir.handlers.FirResolvedTypesVerifier
import org.jetbrains.kotlin.test.frontend.fir.handlers.FirScopeDumpHandler
import org.jetbrains.kotlin.test.frontend.fir.handlers.FirVFirDumpHandler
import org.jetbrains.kotlin.test.frontend.fir.handlers.NonSourceErrorMessagesHandler
import org.jetbrains.kotlin.test.frontend.fir.handlers.PsiLightTreeMetaInfoProcessor
import org.jetbrains.kotlin.test.runners.AbstractPhasedJvmDiagnosticLightTreeTest
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.PhasedPipelineChecker
import org.jetbrains.kotlin.test.services.TestPhase

open class AbstractDiagnosticTest : AbstractPhasedJvmDiagnosticLightTreeTest() {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return ClasspathBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) {
    if (NEEDS_LEGACY_GOLDEN_NAMING) {
      configureWithMetroDiagnosticHandlers(builder)
    } else {
      super.configure(builder)
    }

    with(builder) {
      configurePlugin()

      useMetaTestConfigurators(::MetroTestConfigurator)

      defaultDirectives {
        JVM_TARGET.with(JvmTarget.JVM_11)
        +FULL_JDK
        +IGNORE_DEXING // Avoids loading R8 from the classpath.
        +DISABLE_GENERATED_FIR_TAGS
        commonMetroTestDirectives()

        // Unless overriden, assume the test will fail within the frontend.
        RUN_PIPELINE_TILL.with(TestPhase.FRONTEND)
      }
    }
  }

  /**
   * Inlined copy of `AbstractFirPhasedDiagnosticTest.configure` (Kotlin 2.3.21) with the FIR and IR
   * diagnostics handlers swapped for Metro variants that point the assertion at the post-KT-85292
   * file naming (`<name>.diag.txt`, `<name>.ir.diag.txt`). Other handlers from
   * `setupHandlersForDiagnosticTest` are inlined verbatim.
   *
   * Active only while [NEEDS_LEGACY_GOLDEN_NAMING] is true (Kotlin < 2.4.20). Once Metro's floor
   * moves to 2.4.20+, delete this method, the two `Metro*DiagnosticsHandler` files, and the version
   * gate above; `super.configure(builder)` then suffices.
   */
  private fun configureWithMetroDiagnosticHandlers(builder: TestConfigurationBuilder) =
    with(builder) {
      defaultDirectives {
        LATEST_PHASE_IN_PIPELINE with TestPhase.BACKEND
        LANGUAGE + "+EnableDfaWarningsInK2"
      }

      setupMetroJvmPipeline(FirParser.LightTree)
      configureFirParser(FirParser.LightTree)
      configureCommonDiagnosticTestPaths()

      configureFirHandlersStep {
        useHandlers(
          ::MetroFirDiagnosticsHandler,
          ::FirDumpHandler,
          ::FirCfgDumpHandler,
          ::FirVFirDumpHandler,
          ::FirInferenceLogsHandler,
          ::FirCfgConsistencyHandler,
          ::FirResolvedTypesVerifier,
          ::FirScopeDumpHandler,
        )
        useHandlers(::NoFirCompilationErrorsHandler)
        tagsGeneratorCheckerHandler?.let { useHandlers(it) }
      }

      configureIrHandlersStep {
        useHandlers(::MetroIrDiagnosticsHandler, noIrCompilationErrorsHandlerCtor)
      }

      configureJvmArtifactsHandlersStep {
        commonBackendHandlersForCodegenTest(includeNoCompilationErrorsHandler = false)
      }

      useMetaInfoProcessors(::PsiLightTreeMetaInfoProcessor)
      val tagsAfter = tagsGeneratorCheckerAfterAnalysis
      if (tagsAfter != null) {
        useAfterAnalysisCheckers(
          ::PhasedPipelineChecker,
          ::NonSourceErrorMessagesHandler,
          tagsAfter,
        )
      } else {
        useAfterAnalysisCheckers(::PhasedPipelineChecker, ::NonSourceErrorMessagesHandler)
      }
      enableMetaInfoHandler()
      useAdditionalService<SuppressionChecker>(suppressionCheckerCtor)
    }
}
