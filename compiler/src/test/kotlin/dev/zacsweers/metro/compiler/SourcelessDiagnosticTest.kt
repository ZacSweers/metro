// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import kotlin.test.assertEquals
import org.jetbrains.kotlin.AbstractKtSourceElement
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.diagnostics.KtSourcelessDiagnosticFactory
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.IrDiagnosticReporter.IrDiagnosticContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.junit.Assume.assumeTrue
import org.junit.Test

class SourcelessDiagnosticTest {
  // https://github.com/ZacSweers/metro/pull/3032
  @Test
  fun reportsDiagnosticWithoutSourceLocation() {
    val compat = CompatContext.create()
    assumeTrue(compat.supportsSourcelessIrDiagnostics)

    val reporter = RecordingReporter()
    val factory = MetroDiagnostics.SOURCELESS_METRO_ERROR
    val message = "Missing upstream factory"
    with(compat) { reporter.reportCompat(factory, message) }

    assertEquals(listOf(ReportedDiagnostic(factory, message, null)), reporter.diagnostics)
  }

  private data class ReportedDiagnostic(
    val factory: KtSourcelessDiagnosticFactory,
    val message: String,
    val location: CompilerMessageSourceLocation?,
  )

  private class RecordingReporter : IrDiagnosticReporter {
    val diagnostics = mutableListOf<ReportedDiagnostic>()

    override val hasErrors: Boolean
      get() = diagnostics.isNotEmpty()

    // Older compiler versions call this JVM signature.
    fun report(factory: KtSourcelessDiagnosticFactory, message: String) {
      report(factory, message, null)
    }

    override fun report(
      factory: KtSourcelessDiagnosticFactory,
      message: String,
      location: CompilerMessageSourceLocation?,
    ) {
      diagnostics += ReportedDiagnostic(factory, message, location)
    }

    override fun at(irDeclaration: IrDeclaration): IrDiagnosticContext =
      error("This reporter only supports sourceless diagnostics")

    override fun at(irElement: IrElement, containingIrFile: IrFile): IrDiagnosticContext =
      error("This reporter only supports sourceless diagnostics")

    override fun at(
      irElement: IrElement,
      containingIrDeclaration: IrDeclaration,
    ): IrDiagnosticContext = error("This reporter only supports sourceless diagnostics")

    override fun at(
      sourceElement: AbstractKtSourceElement?,
      irElement: IrElement,
      containingFile: IrFile,
    ): IrDiagnosticContext = error("This reporter only supports sourceless diagnostics")
  }
}
