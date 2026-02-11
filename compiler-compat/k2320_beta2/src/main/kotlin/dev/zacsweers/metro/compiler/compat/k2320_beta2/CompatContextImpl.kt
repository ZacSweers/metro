// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k2320_beta2

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k2320_beta1.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.diagnostics.DiagnosticContext
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.diagnostics.KtDiagnosticWithoutSource
import org.jetbrains.kotlin.diagnostics.KtSourcelessDiagnosticFactory

public class CompatContextImpl : CompatContext by DelegateType() {
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

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.3.20-Beta2"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
