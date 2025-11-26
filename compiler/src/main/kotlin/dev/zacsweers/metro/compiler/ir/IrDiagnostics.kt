// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.diagnostics.InternalDiagnosticFactoryMethod
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.sourceElement

/*
Compat reporting functions until IrDiagnosticReporter supports source-less declarations
*/

@OptIn(InternalDiagnosticFactoryMethod::class)
internal fun <A : Any> IrMetroContext.reportCompat(
  irDeclarations: Sequence<IrDeclaration?>,
  factory: KtDiagnosticFactory1<A>,
  a: A,
) {
  val toReport =
    irDeclarations.filterNotNull().firstOrNull {
      (it.fileOrNull != null && it.sourceElement() != null) || it.locationOrNull() != null
    } ?: irDeclarations.filterNotNull().firstOrNull()
  if (toReport == null) {
    reportCompilerBug("No non-null declarations to report on!")
  }
  reportCompat(toReport, factory, a)
}

@OptIn(InternalDiagnosticFactoryMethod::class)
@Suppress("DEPRECATION")
internal fun <A : Any> IrMetroContext.reportCompat(
  irDeclaration: IrDeclaration?,
  factory: KtDiagnosticFactory1<A>,
  a: A,
  extraContext: StringBuilder.() -> Unit = {},
) {
  val sourceElement = irDeclaration?.sourceElement()
  if (irDeclaration?.fileOrNull == null || sourceElement == null) {
    // Report through message collector for now
    // If we have a source element, report the diagnostic directly
    if (sourceElement != null) {
      val diagnostic = factory.on(sourceElement, a, null, languageVersionSettings)
      reportDiagnosticToMessageCollector(
        diagnostic!!,
        irDeclaration.locationOrNull(),
        messageCollector,
        false,
      )
      return
    }
    val severity = AnalyzerWithCompilerReport.convertSeverity(factory.severity)
    val location = irDeclaration?.locationOrNull()
    val message =
      if (
        location == null &&
          irDeclaration != null &&
          // Java stubs have nothing useful for us here
          irDeclaration.origin != IrDeclarationOrigin.IR_EXTERNAL_JAVA_DECLARATION_STUB
      ) {
        buildString {
          appendLine(a)
          appendLine()
          appendLine("(context)")
          append("Encountered while processing declaration '")
          append(irDeclaration.humanReadableDiagnosticLocation())
          append("'")
          appendLine(" (no source location available)")
          extraContext()
        }
      } else {
        a.toString()
      }
    messageCollector.report(severity, message, location)
  } else {
    diagnosticReporter.at(irDeclaration).report(factory, a)
  }
  // Log an error to MetroContext
  onErrorReported()
}

private fun reportDiagnosticToMessageCollector(
  diagnostic: KtDiagnostic,
  location: CompilerMessageSourceLocation?,
  reporter: MessageCollector,
  renderDiagnosticName: Boolean,
) {
  val severity = AnalyzerWithCompilerReport.convertSeverity(diagnostic.severity)
  val message = diagnostic.renderMessage()
  val textToRender =
    when (renderDiagnosticName) {
      true -> "[${diagnostic.factoryName}] $message"
      false -> message
    }

  reporter.report(severity, textToRender, location)
}
