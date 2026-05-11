// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.test.Constructor
import org.jetbrains.kotlin.test.backend.BlackBoxCodegenSuppressor.SuppressionChecker
import org.jetbrains.kotlin.test.backend.handlers.NoIrCompilationErrorsHandler
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.frontend.fir.FirOutputArtifact
import org.jetbrains.kotlin.test.model.AfterAnalysisChecker
import org.jetbrains.kotlin.test.model.AnalysisHandler
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.utils.bind

// Stubs: on Kotlin 2.4.20+ the framework natively produces post-KT-85292 file naming and
// `line:column` format, so [NEEDS_LEGACY_GOLDEN_NAMING] is false and `AbstractDiagnosticTest`
// just calls `super.configure`. None of these helpers are reachable at runtime here -- they
// only exist so the call sites in src/test resolve at compile time.

private const val UNREACHABLE = "Metro diagnostic shim should not run on Kotlin 2.4.20+."

fun mainFirFilesCompat(info: FirOutputArtifact): Map<TestFile, FirFile> = error(UNREACHABLE)

fun severityToStringCompat(severity: Severity): String = error(UNREACHABLE)

fun irDiagnosticsForFileCompat(
  info: IrBackendInput,
  file: TestFile,
  testServices: TestServices,
): List<KtDiagnostic>? = error(UNREACHABLE)

val noIrCompilationErrorsHandlerCtor: Constructor<AnalysisHandler<IrBackendInput>> =
  ::NoIrCompilationErrorsHandler

val suppressionCheckerCtor: Constructor<SuppressionChecker> = ::SuppressionChecker.bind(null, null)

val tagsGeneratorCheckerHandler: Constructor<AnalysisHandler<FirOutputArtifact>>? = null

val tagsGeneratorCheckerAfterAnalysis: Constructor<AfterAnalysisChecker>? = null
