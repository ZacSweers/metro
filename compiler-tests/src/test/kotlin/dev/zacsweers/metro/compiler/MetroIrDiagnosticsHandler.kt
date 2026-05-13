// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.cli.common.fir.SequentialPositionFinder
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.diagnostics.KtDiagnosticWithSource
import org.jetbrains.kotlin.diagnostics.KtDiagnosticWithoutSource
import org.jetbrains.kotlin.test.backend.handlers.AbstractIrHandler
import org.jetbrains.kotlin.test.backend.handlers.assertFileDoesntExist
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.backend.ir.IrDiagnosticsHandler
import org.jetbrains.kotlin.test.directives.DiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.ServiceRegistrationData
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.services.sourceFileProvider
import org.jetbrains.kotlin.test.utils.MultiModuleInfoDumper

/**
 * Drop-in replacement for [IrDiagnosticsHandler] that asserts the full-text IR diagnostics dump
 * against `<name>.ir.diag.txt` (no `.fir.` prefix) AND renders each entry as `line:column` instead
 * of `(start,end)`. Both changes match the post-KT-85292 behavior Kotlin 2.4.20+ produces natively.
 *
 * Encapsulates a real [IrDiagnosticsHandler] only for its side effects (metadata feeds). The
 * renderer itself is reimplemented here so the output matches 2.4.20+ byte-for-byte.
 *
 * Wired in by [AbstractDiagnosticTest] only when [NEEDS_LEGACY_GOLDEN_NAMING] is true.
 */
class MetroIrDiagnosticsHandler(testServices: TestServices) : AbstractIrHandler(testServices) {
  private val delegate = IrDiagnosticsHandler(testServices)
  private val dumper = MultiModuleInfoDumper(moduleHeaderTemplate = "// -- Module: <%s> --")

  override val additionalServices: List<ServiceRegistrationData>
    get() = delegate.additionalServices

  override fun processModule(module: TestModule, info: IrBackendInput) {
    delegate.processModule(module, info)
    if (DiagnosticsDirectives.RENDER_IR_DIAGNOSTICS_FULL_TEXT !in module.directives) return

    for (currentModule in testServices.moduleStructure.modules) {
      for (file in currentModule.files) {
        val diagnostics = irDiagnosticsForFileCompat(info, file, testServices) ?: continue
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
        "${testDataFile.nameWithoutExtension.removeSuffix(".fir")}.ir.diag.txt"
      )
    if (DiagnosticsDirectives.RENDER_IR_DIAGNOSTICS_FULL_TEXT !in directives) {
      if (DiagnosticsDirectives.RENDER_ALL_DIAGNOSTICS_FULL_TEXT !in directives) {
        testServices.assertions.assertFileDoesntExist(
          expectedFile,
          DiagnosticsDirectives.RENDER_IR_DIAGNOSTICS_FULL_TEXT,
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
