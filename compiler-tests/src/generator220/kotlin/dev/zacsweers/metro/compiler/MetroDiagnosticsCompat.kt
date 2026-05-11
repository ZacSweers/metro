// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.test.Constructor
import org.jetbrains.kotlin.test.backend.BlackBoxCodegenSuppressor.SuppressionChecker
import org.jetbrains.kotlin.test.backend.handlers.NoFir2IrCompilationErrorsHandler
import org.jetbrains.kotlin.test.backend.handlers.findByPath
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.frontend.fir.FirOutputArtifact
import org.jetbrains.kotlin.test.frontend.fir.TagsGeneratorChecker
import org.jetbrains.kotlin.test.model.AfterAnalysisChecker
import org.jetbrains.kotlin.test.model.AnalysisHandler
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toLowerCaseAsciiOnly
import org.jetbrains.kotlin.utils.bind

// Per-generator shims for the few framework APIs that diverge between Kotlin versions. The shared
// `Metro*DiagnosticsHandler` and `setupMetroDiagnosticHandlers` in src/test rely on these.

fun mainFirFilesCompat(info: FirOutputArtifact): Map<TestFile, FirFile> = info.mainFirFiles

fun severityToStringCompat(severity: Severity): String =
  AnalyzerWithCompilerReport.convertSeverity(severity).toString().toLowerCaseAsciiOnly()

fun irDiagnosticsForFileCompat(
  info: IrBackendInput,
  file: TestFile,
  testServices: TestServices,
): List<KtDiagnostic>? {
  val byPath = info.diagnosticReporter.diagnosticsByFilePath
  return file.findByPath(testServices) { byPath[it] }
}

val noIrCompilationErrorsHandlerCtor: Constructor<AnalysisHandler<IrBackendInput>> =
  ::NoFir2IrCompilationErrorsHandler

val suppressionCheckerCtor: Constructor<SuppressionChecker> = ::SuppressionChecker.bind(null)

val tagsGeneratorCheckerHandler: Constructor<AnalysisHandler<FirOutputArtifact>>? = null

val tagsGeneratorCheckerAfterAnalysis: Constructor<AfterAnalysisChecker>? = ::TagsGeneratorChecker
