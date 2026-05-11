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
// branch rename 2.3.21 did). compileOnly is pinned to Beta2 -- to also handle Beta1 at runtime,
// resolve the getter reflectively rather than calling either property statically.
private val diagnosticsByFileGetter: java.lang.reflect.Method? =
  org.jetbrains.kotlin.diagnostics.impl.BaseDiagnosticsCollector::class.java.methods.firstOrNull {
    it.name == "getDiagnosticsByFile"
  }

private val diagnosticsByFilePathGetter: java.lang.reflect.Method? =
  org.jetbrains.kotlin.diagnostics.impl.BaseDiagnosticsCollector::class.java.methods.firstOrNull {
    it.name == "getDiagnosticsByFilePath"
  }

fun irDiagnosticsForFileCompat(
  info: IrBackendInput,
  file: TestFile,
  testServices: TestServices,
): List<KtDiagnostic>? {
  val reporter = info.diagnosticReporter
  diagnosticsByFileGetter?.let { getter ->
    @Suppress("UNCHECKED_CAST")
    val byFile = getter.invoke(reporter) as Map<Any?, List<KtDiagnostic>>
    return file.findByPath(testServices) { path ->
      byFile.entries
        .firstOrNull { entry ->
          entry.key?.javaClass?.getMethod("getPath")?.invoke(entry.key) == path
        }
        ?.value
    }
  }
  val getter =
    diagnosticsByFilePathGetter
      ?: error(
        "Neither diagnosticsByFile nor diagnosticsByFilePath found on BaseDiagnosticsCollector"
      )
  @Suppress("UNCHECKED_CAST")
  val byPath = getter.invoke(reporter) as Map<String?, List<KtDiagnostic>>
  return file.findByPath(testServices) { byPath[it] }
}

val noIrCompilationErrorsHandlerCtor: Constructor<AnalysisHandler<IrBackendInput>> =
  ::NoIrCompilationErrorsHandler

val suppressionCheckerCtor: Constructor<SuppressionChecker> = ::SuppressionChecker.bind(null, null)

val tagsGeneratorCheckerHandler: Constructor<AnalysisHandler<FirOutputArtifact>>? =
  ::TagsGeneratorChecker

val tagsGeneratorCheckerAfterAnalysis: Constructor<AfterAnalysisChecker>? = null
