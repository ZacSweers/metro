// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.backend.BlackBoxCodegenSuppressor.SuppressionChecker
import org.jetbrains.kotlin.test.backend.handlers.KlibBackendDiagnosticsHandler
import org.jetbrains.kotlin.test.backend.handlers.NoFirCompilationErrorsHandler
import org.jetbrains.kotlin.test.backend.ir.IrDiagnosticsHandler
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.builders.configureFirHandlersStep
import org.jetbrains.kotlin.test.builders.configureIrHandlersStep
import org.jetbrains.kotlin.test.builders.configureLoweredIrHandlersStep
import org.jetbrains.kotlin.test.builders.klibArtifactsHandlersStep
import org.jetbrains.kotlin.test.configuration.setupHandlersForDiagnosticTest
import org.jetbrains.kotlin.test.directives.ConfigurationDirectives.WITH_STDLIB
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives.DISABLE_GENERATED_FIR_TAGS
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives.FIR_IDENTICAL
import org.jetbrains.kotlin.test.directives.TestPhaseDirectives.LATEST_PHASE_IN_PIPELINE
import org.jetbrains.kotlin.test.directives.TestPhaseDirectives.RUN_PIPELINE_TILL
import org.jetbrains.kotlin.test.directives.configureFirParser
import org.jetbrains.kotlin.test.runners.AbstractKotlinCompilerTest
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.LibraryProvider
import org.jetbrains.kotlin.test.services.TestPhase

open class AbstractJsDiagnosticTest : AbstractKotlinCompilerTest() {
  override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
    return ClasspathBasedStandardLibrariesPathProvider
  }

  override fun configure(builder: TestConfigurationBuilder) =
    with(builder) {
      globalDefaults { targetBackend = TargetBackend.JS_IR }

      setupMetroJsPipeline(FirParser.LightTree)
      configureFirParser(FirParser.LightTree)
      configurePlugin()

      useMetaTestConfigurators(::MetroTestConfigurator)

      defaultDirectives {
        +WITH_STDLIB
        +FIR_IDENTICAL
        +DISABLE_GENERATED_FIR_TAGS
        commonMetroTestDirectives()

        // Unless overriden, assume the test will fail within the frontend.
        RUN_PIPELINE_TILL.with(TestPhase.FRONTEND)
        LATEST_PHASE_IN_PIPELINE.with(TestPhase.BACKEND)
      }

      configureFirHandlersStep {
        if (NEEDS_LEGACY_GOLDEN_NAMING) {
          useHandlers(::MetroFirDiagnosticsHandler)
        } else {
          setupHandlersForDiagnosticTest()
        }
        useHandlers(::NoFirCompilationErrorsHandler)
      }

      configureIrHandlersStep {
        if (NEEDS_LEGACY_GOLDEN_NAMING) {
          useHandlers(::MetroIrDiagnosticsHandler)
        } else {
          useHandlers(::IrDiagnosticsHandler)
        }
      }

      configureLoweredIrHandlersStep {
        if (NEEDS_LEGACY_GOLDEN_NAMING) {
          useHandlers(::MetroIrDiagnosticsHandler)
        } else {
          useHandlers(::IrDiagnosticsHandler)
        }
      }

      klibArtifactsHandlersStep { useHandlers(::KlibBackendDiagnosticsHandler) }

      enableMetaInfoHandler()
      useAdditionalService(::LibraryProvider)
      usePhasedPipelineFailureSuppressorCompat()
      useAdditionalService<SuppressionChecker>(suppressionCheckerCtor)
    }
}
