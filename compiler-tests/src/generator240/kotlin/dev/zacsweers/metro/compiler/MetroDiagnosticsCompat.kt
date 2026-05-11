// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.test.Constructor
import org.jetbrains.kotlin.test.backend.BlackBoxCodegenSuppressor.SuppressionChecker
import org.jetbrains.kotlin.test.backend.handlers.NoIrCompilationErrorsHandler
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

fun mainFirFilesCompat(info: FirOutputArtifact): Map<TestFile, FirFile> =
  info.mainFirFilesByTestFile

fun severityToStringCompat(severity: Severity): String =
  severity.toCompilerMessageSeverity().toString().toLowerCaseAsciiOnly()

// 2.4.0-Beta2 dropped `diagnosticsByFilePath` for `diagnosticsByFile` (the same late-on-the-2.4.0
// branch rename 2.3.21 did). 2.4.0-Beta1 isn't supported by this generator's runtime; bump the
// test compiler version if you need it.
fun irDiagnosticsForFileCompat(
  info: IrBackendInput,
  file: TestFile,
  testServices: TestServices,
): List<KtDiagnostic>? {
  val byFile = info.diagnosticReporter.diagnosticsByFile
  return file.findByPath(testServices) { path ->
    byFile.entries.firstOrNull { it.key?.path == path }?.value
  }
}

val noIrCompilationErrorsHandlerCtor: Constructor<AnalysisHandler<IrBackendInput>> =
  ::NoIrCompilationErrorsHandler

val suppressionCheckerCtor: Constructor<SuppressionChecker> = ::SuppressionChecker.bind(null, null)

val tagsGeneratorCheckerHandler: Constructor<AnalysisHandler<FirOutputArtifact>>? =
  ::TagsGeneratorChecker

val tagsGeneratorCheckerAfterAnalysis: Constructor<AfterAnalysisChecker>? = null
