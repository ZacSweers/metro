// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k2320_dev_7778

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k2320_dev_5437.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.diagnostics.DiagnosticContext
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.diagnostics.KtDiagnosticWithoutSource
import org.jetbrains.kotlin.diagnostics.KtSourcelessDiagnosticFactory
import org.jetbrains.kotlin.ir.IrDiagnosticReporter

public class CompatContextImpl : CompatContext by DelegateType() {
  override val supportsExternalRepeatableAnnotations: Boolean = true
  override val supportsSourcelessIrDiagnostics: Boolean = true

  override fun KtSourcelessDiagnosticFactory.createCompat(
    message: String,
    location: CompilerMessageSourceLocation?,
    languageVersionSettings: LanguageVersionSettings,
  ): KtDiagnosticWithoutSource? {
    val context =
      object : DiagnosticContext {
        override val containingFilePath: String?
          get() = null

        override fun isDiagnosticSuppressed(diagnostic: KtDiagnostic): Boolean = false

        override val languageVersionSettings: LanguageVersionSettings
          get() = languageVersionSettings
      }
    return create(message, location, context)
  }

  override fun IrDiagnosticReporter.reportCompat(
    factory: KtSourcelessDiagnosticFactory,
    message: String,
  ) {
    report(factory, message)
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.3.20-dev-7778"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
