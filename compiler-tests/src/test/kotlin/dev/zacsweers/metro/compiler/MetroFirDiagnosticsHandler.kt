// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.cli.common.fir.SequentialPositionFinder
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.diagnostics.KtDiagnosticWithSource
import org.jetbrains.kotlin.diagnostics.KtDiagnosticWithoutSource
import org.jetbrains.kotlin.test.Constructor
import org.jetbrains.kotlin.test.backend.handlers.assertFileDoesntExist
import org.jetbrains.kotlin.test.directives.DiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.frontend.fir.FirOutputArtifact
import org.jetbrains.kotlin.test.frontend.fir.handlers.FirAnalysisHandler
import org.jetbrains.kotlin.test.frontend.fir.handlers.FirDiagnosticsHandler
import org.jetbrains.kotlin.test.frontend.fir.handlers.firDiagnosticCollectorService
import org.jetbrains.kotlin.test.model.AfterAnalysisChecker
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.ServiceRegistrationData
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.services.sourceFileProvider
import org.jetbrains.kotlin.test.utils.MultiModuleInfoDumper

/**
 * Drop-in replacement for [FirDiagnosticsHandler] that asserts the full-text diagnostics dump
 * against `<name>.diag.txt` (no `.fir.` prefix) *and* renders each entry as `line:column` instead
 * of `(start,end)`. Both changes match the post-KT-85292 behavior Kotlin 2.4.20+ produces natively.
 *
 * Encapsulates a real [FirDiagnosticsHandler] only for its side effects (metadata feeds, debug-info
 * diagnostics, dynamic-call detection). The renderer itself is reimplemented here so the output
 * matches 2.4.20+ byte-for-byte.
 *
 * Wired in by [AbstractDiagnosticTest] only when [NEEDS_LEGACY_GOLDEN_NAMING] is true. Once Metro's
 * floor is 2.4.20+, this class can be deleted.
 */
class MetroFirDiagnosticsHandler(testServices: TestServices) : FirAnalysisHandler(testServices) {
  private val delegate = FirDiagnosticsHandler(testServices)
  private val dumper = MultiModuleInfoDumper(moduleHeaderTemplate = "// -- Module: <%s> --")

  override val directiveContainers: List<DirectivesContainer>
    get() = delegate.directiveContainers

  override val additionalServices: List<ServiceRegistrationData>
    get() = delegate.additionalServices

  override val additionalAfterAnalysisCheckers: List<Constructor<AfterAnalysisChecker>>
    get() = delegate.additionalAfterAnalysisCheckers

  override fun processModule(module: TestModule, info: FirOutputArtifact) {
    delegate.processModule(module, info)
    if (DiagnosticsDirectives.RENDER_DIAGNOSTICS_FULL_TEXT !in module.directives) return

    val perFile = testServices.firDiagnosticCollectorService.getFrontendDiagnosticsForModule(info)
    for (part in info.partsForDependsOnModules) {
      for (file in part.module.files) {
        val firFile = mainFirFilesCompat(info)[file] ?: continue
        val diagnostics = perFile[firFile].map { it.diagnostic }
        val rendered = renderDiagnosticsAsLineColumn(diagnostics, file) ?: continue
        dumper.builderForModule(module).appendLine(rendered)
      }
    }
  }

  override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
    val directives = testServices.moduleStructure.allDirectives
    if (FirDiagnosticsDirectives.USE_LATEST_LANGUAGE_VERSION in directives) return
    val testDataFile = testServices.moduleStructure.originalTestDataFiles.first()
    val expectedFile =
      testDataFile.parentFile.resolve(
        "${testDataFile.nameWithoutExtension.removeSuffix(".fir")}.diag.txt"
      )
    if (DiagnosticsDirectives.RENDER_DIAGNOSTICS_FULL_TEXT !in directives) {
      if (DiagnosticsDirectives.RENDER_ALL_DIAGNOSTICS_FULL_TEXT !in directives) {
        testServices.assertions.assertFileDoesntExist(
          expectedFile,
          DiagnosticsDirectives.RENDER_DIAGNOSTICS_FULL_TEXT,
        )
      }
      return
    }
    if (dumper.isEmpty() && !expectedFile.exists()) return
    testServices.assertions.assertEqualsToFile(expectedFile, dumper.generateResultingDump())
  }

  private fun renderDiagnosticsAsLineColumn(
    diagnostics: List<KtDiagnostic>,
    file: TestFile,
  ): String? {
    if (diagnostics.isEmpty()) return null

    data class DiagnosticData(
      val textRanges: List<TextRange>,
      val severity: String,
      val message: String,
    )

    val reported =
      diagnostics
        .map {
          DiagnosticData(
            textRanges =
              when (it) {
                is KtDiagnosticWithSource -> it.textRanges
                is KtDiagnosticWithoutSource -> listOf(it.firstRange)
              },
            severity = severityToStringCompat(it.severity),
            message = it.renderMessage(),
          )
        }
        .sortedWith(
          compareBy<DiagnosticData> { it.textRanges.first().startOffset }.thenBy { it.message }
        )

    return testServices.sourceFileProvider
      .getContentOfSourceFile(file)
      .byteInputStream()
      .reader()
      .use { reader ->
        val finder = SequentialPositionFinder(reader)
        reported.joinToString(separator = "\n\n") { d ->
          val pos =
            finder.findNextPosition(d.textRanges.first().startOffset, withLineContents = false)
          "/${file.name}:${pos.line}:${pos.column}: ${d.severity}: ${d.message}"
        }
      }
  }
}
