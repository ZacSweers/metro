// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.api.fir.metroOriginData
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.reportCompilerBug
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.ERROR
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.FIXED_WARNING
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.STRONG_WARNING
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.WARNING
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.diagnostics.InternalDiagnosticFactoryMethod
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.sourceElement

/*
Compat reporting functions until IrDiagnosticReporter supports source-less declarations
*/

private val REPORT_LOCK = ReentrantLock()

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

// AnalyzerWithCompilerReport removed this API in 2.3.20, so we copy it in
private fun Severity.convertSeverity(): CompilerMessageSeverity =
  when (this) {
    Severity.INFO -> INFO
    Severity.ERROR -> ERROR
    Severity.WARNING -> WARNING
    Severity.FIXED_WARNING -> FIXED_WARNING
    Severity.STRONG_WARNING -> STRONG_WARNING
  }

internal fun <A : Any> IrMetroContext.reportCompat(
  irDeclaration: IrDeclaration?,
  factory: KtDiagnosticFactory1<A>,
  a: A,
  extraContext: StringBuilder.() -> Unit = {},
) {
  if (options.parallelThreads > 0) {
    REPORT_LOCK.withLock { reportCompatImpl(irDeclaration, factory, a, extraContext) }
  } else {
    reportCompatImpl(irDeclaration, factory, a, extraContext)
  }
}

private fun <A : Any> IrMetroContext.reportCompatImpl(
  irDeclaration: IrDeclaration?,
  factory: KtDiagnosticFactory1<A>,
  a: A,
  extraContext: StringBuilder.() -> Unit,
) {
  // If the declaration has no source (e.g. a generated contribution provider or other
  // generated declaration carrying @Origin/MetroOriginData), redirect to the origin class
  // so the diagnostic points at user-authored code. Only take the redirect when the origin
  // itself has source — otherwise we'd lose the original declaration's metadata (e.g. the
  // BindsMirror function path and "contributes X to scope" hint) without gaining a usable
  // file:line, which is worse for transitive-dep diagnostics.
  var effectiveDeclaration = irDeclaration
  if (
    irDeclaration != null &&
      (irDeclaration.fileOrNull == null || irDeclaration.sourceElement() == null)
  ) {
    irDeclaration
      .findOriginClass()
      ?.takeIf { it.fileOrNull != null && it.sourceElement() != null }
      ?.let { effectiveDeclaration = it }
  }

  val sourceElement = effectiveDeclaration?.sourceElement()
  if (effectiveDeclaration?.fileOrNull == null || sourceElement == null) {
    // Report through message collector for now
    // If we have a source element, report the diagnostic directly
    if (sourceElement != null) {
      // TODO https://youtrack.jetbrains.com/issue/KT-83491
      // val sourcelessFactory = factory.asSourcelessFactory()
      val sourcelessFactory = MetroDiagnostics.SOURCELESS_METRO_ERROR
      if (supportsSourcelessIrDiagnostics) {
        diagnosticReporter.reportCompat(sourcelessFactory, a as String)
      } else {
        val diagnostic =
          sourcelessFactory.createCompat(
            a as String,
            effectiveDeclaration.locationOrNull(),
            languageVersionSettings,
          )
        @Suppress("DEPRECATION")
        reportDiagnosticToMessageCollector(
          diagnostic!!,
          effectiveDeclaration.locationOrNull(),
          messageCollector,
          false,
        )
      }
      return
    }
    val severity = factory.severity.convertSeverity()
    val location = effectiveDeclaration?.locationOrNull()
    val message =
      if (
        location == null &&
          effectiveDeclaration != null &&
          // Java stubs have nothing useful for us here
          effectiveDeclaration.origin != Origins.FirstParty.IR_EXTERNAL_JAVA_DECLARATION_STUB
      ) {
        buildString {
          appendLine(a)
          appendLine()
          appendLine("(context)")
          append("Encountered while processing declaration '")
          val (fullPath, metadata) = effectiveDeclaration.humanReadableDiagnosticMetadata()
          append(fullPath)
          append("'")
          appendLine(" (no source location available)")
          if (metadata.isNotEmpty()) {
            for (line in metadata) {
              appendLine("- $line")
            }
          }
          extraContext()
        }
      } else {
        a.toString()
      }
    @Suppress("DEPRECATION") messageCollector.report(severity, message, location)
  } else {
    diagnosticReporter.reportAt(effectiveDeclaration, factory, a)
  }

  if (factory.severity == Severity.ERROR) {
    // Log an error to MetroContext
    onErrorReported()
  }
}

context(context: IrMetroContext)
private fun IrDeclaration.findOriginClass(): IrClass? {
  var current: IrClass? = this as? IrClass ?: parentClassOrNull
  while (current != null) {
    val originClassId = current.originClassId() ?: current.metroOriginData?.originClassId
    if (originClassId != null) {
      val origin = context.referenceClass(originClassId)?.owner
      if (origin != null && origin != current) return origin
    }
    current = current.parentClassOrNull
  }
  return null
}

private fun reportDiagnosticToMessageCollector(
  diagnostic: KtDiagnostic,
  location: CompilerMessageSourceLocation?,
  reporter: MessageCollector,
  renderDiagnosticName: Boolean,
) {
  val message = diagnostic.renderMessage()
  val textToRender =
    when (renderDiagnosticName) {
      true -> "[${diagnostic.factoryName}] $message"
      false -> message
    }

  reporter.report(diagnostic.severity.convertSeverity(), textToRender, location)
}
