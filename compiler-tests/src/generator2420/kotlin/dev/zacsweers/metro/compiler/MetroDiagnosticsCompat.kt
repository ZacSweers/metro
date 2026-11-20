// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.test.Constructor
import org.jetbrains.kotlin.test.backend.BlackBoxCodegenSuppressor
import org.jetbrains.kotlin.test.backend.BlackBoxCodegenSuppressor.SuppressionChecker
import org.jetbrains.kotlin.test.backend.handlers.NoIrCompilationErrorsHandler
import org.jetbrains.kotlin.test.backend.handlers.findByPath
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.builders.RegisteredDirectivesBuilder
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.frontend.fir.FirOutputArtifact
import org.jetbrains.kotlin.test.model.AfterAnalysisChecker
import org.jetbrains.kotlin.test.model.AnalysisHandler
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.services.PhasedPipelineChecker
import org.jetbrains.kotlin.test.services.TestPhase
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.utils.bind

// Kotlin 2.4.20+ renders FIR goldens natively. Only the legacy handler calls this helper.
fun mainFirFilesCompat(info: FirOutputArtifact): Map<TestFile, FirFile> =
  error("Legacy FIR diagnostic handler used on Kotlin 2.4.20+.")

fun severityToStringCompat(severity: Severity): String =
  severity.toCompilerMessageSeverity().toString().lowercase()

/** Resolves the diagnostics for a file in a rich IR snapshot. */
fun irDiagnosticsForFileCompat(
  info: IrBackendInput,
  file: TestFile,
  testServices: TestServices,
): List<KtDiagnostic>? =
  file.findByPath(testServices) { path ->
    info.diagnosticReporter.diagnosticsByFile.entries.firstOrNull { it.key?.path == path }?.value
  }

val noIrCompilationErrorsHandlerCtor: Constructor<AnalysisHandler<IrBackendInput>> =
  ::NoIrCompilationErrorsHandler

val suppressionCheckerCtor: Constructor<SuppressionChecker> = ::SuppressionChecker.bind(null, null)

val tagsGeneratorCheckerHandler: Constructor<AnalysisHandler<FirOutputArtifact>>? = null

val tagsGeneratorCheckerAfterAnalysis: Constructor<AfterAnalysisChecker>? = null

// Kotlin 2.5 renamed the final backend phase to CODEGEN.
val backendTestPhase: TestPhase =
  TestPhase.entries.first { it.name == "BACKEND" || it.name == "CODEGEN" }

fun TestConfigurationBuilder.useIrDumpFailureSuppressorsCompat() {
  useFailureSuppressors(
    ::BlackBoxCodegenSuppressor,
    ::PhasedPipelineChecker.bind(backendTestPhase),
  )
}

fun TestConfigurationBuilder.usePhasedPipelineFailureSuppressorCompat() {
  useFailureSuppressors(::PhasedPipelineChecker)
}

fun RegisteredDirectivesBuilder.firIdenticalCompat() = Unit

abstract class MetroAfterAnalysisCheckerCompat(testServices: TestServices) :
  AfterAnalysisChecker(testServices) {
  final override fun check(thereWereFailures: Boolean) {
    checkAfterAnalysis(thereWereFailures)
  }

  abstract fun checkAfterAnalysis(thereWereFailures: Boolean)
}
