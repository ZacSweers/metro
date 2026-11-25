// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.api.fir.metroOriginData
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.reportCompilerBug
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

@OptIn(InternalDiagnosticFactoryMethod::class)
internal fun <A : Any> IrMetroContext.reportCompat(
  irDeclarations: Sequence<IrDeclaration?>,
  factory: KtDiagnosticFactory1<A>,
  a: A,
  includeDeclarationContext: Boolean = true,
) {
  val toReport = reportTarget(irDeclarations)
  reportCompat(toReport, factory, a, includeDeclarationContext = includeDeclarationContext)
}

/**
 * Picks the declaration that [reportCompat] reports on from [irDeclarations]. It's the first one
 * with a source or location, or the first non-null one when none has either. Callers that batch
 * messages per element use this to group them the same way.
 */
internal fun reportTarget(irDeclarations: Sequence<IrDeclaration?>): IrDeclaration {
  val toReport =
    irDeclarations.filterNotNull().firstOrNull {
      (it.fileOrNull != null && it.sourceElement() != null) || it.locationOrNull() != null
    } ?: irDeclarations.filterNotNull().firstOrNull()
  if (toReport == null) {
    reportCompilerBug("No non-null declarations to report on!")
  }
  return toReport
}

/**
 * Joins diagnostic messages that land on the same element into one report.
 *
 * kotlinc keeps only the first diagnostic for one factory on one source element. It drops later
 * reports with the same factory on that element. So messages are grouped by factory, by the
 * declaration [reportTarget] picks from their candidates, and by `includeDeclarationContext`.
 *
 * [flush] reports one diagnostic per group, in the order each group got its first message. A
 * group's messages are sorted so output is deterministic across Kotlin versions. They're joined
 * with a blank line and padded once with [padForConsole].
 *
 * Each reporting site creates its own instance and flushes it where it reports.
 */
internal class DiagnosticBatcher {
  private data class Group(
    val factory: KtDiagnosticFactory1<String>,
    val target: IrDeclaration,
    val includeDeclarationContext: Boolean,
  )

  private val groups = LinkedHashMap<Group, MutableList<String>>()

  /** True when no message was added since the last [flush]. */
  val isEmpty: Boolean
    get() = groups.isEmpty()

  /**
   * Adds an unpadded [message]. [candidates] are in priority order, and [reportTarget] picks the
   * one it's reported on.
   */
  fun add(
    factory: KtDiagnosticFactory1<String>,
    candidates: Sequence<IrDeclaration?>,
    message: String,
    includeDeclarationContext: Boolean = true,
  ) {
    val group = Group(factory, reportTarget(candidates), includeDeclarationContext)
    groups.getOrPut(group) { mutableListOf() } += message
  }

  /** Reports one diagnostic per group and clears the batch. */
  context(context: IrMetroContext)
  fun flush() {
    for ((group, messages) in groups) {
      val combinedMessage = messages.sorted().joinToString(separator = "\n\n")
      context.reportCompat(
        group.target,
        group.factory,
        combinedMessage.padForConsole(),
        includeDeclarationContext = group.includeDeclarationContext,
      )
    }
    groups.clear()
  }
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
  includeDeclarationContext: Boolean = true,
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

  var reportedSeverity = factory.severity
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
          metroMessageCollector,
          false,
        )
      }
      return
    }
    // Apply configured warning levels before sending diagnostics to the collector.
    val effectiveSeverity = factory.getEffectiveSeverityCompat(languageVersionSettings)
    if (effectiveSeverity == null) {
      return
    }
    reportedSeverity = effectiveSeverity
    val severity = effectiveSeverity.convertSeverity()
    val location = effectiveDeclaration?.locationOrNull()
    val message =
      if (
        includeDeclarationContext &&
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
    @Suppress("DEPRECATION") metroMessageCollector.report(severity, message, location)
  } else {
    diagnosticReporter.reportAt(effectiveDeclaration, factory, a)
  }

  if (reportedSeverity == Severity.ERROR) {
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
      val origin = this@findOriginClass.lookupClass(originClassId)?.owner
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
