// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.backend.handlers.AbstractIrHandler
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.backend.ir.IrDiagnosticsHandler
import org.jetbrains.kotlin.test.frontend.fir.handlers.FullDiagnosticsRenderer
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.ServiceRegistrationData
import org.jetbrains.kotlin.test.services.TestServices

/**
 * Drop-in replacement for [IrDiagnosticsHandler] that asserts the full-text IR diagnostics dump
 * against `<name>.ir.diag.txt` (no `.fir.` prefix). Matches the post-KT-85292 naming that ships in
 * Kotlin 2.4.20+.
 *
 * Encapsulates a real [IrDiagnosticsHandler] internally and only overrides [processAfterAllModules]
 * to retarget the assertion. See [MetroFirDiagnosticsHandler] for rationale.
 *
 * Wired in by [AbstractDiagnosticTest] only when [NEEDS_LEGACY_GOLDEN_NAMING] is true.
 */
class MetroIrDiagnosticsHandler(testServices: TestServices) : AbstractIrHandler(testServices) {
  private val delegate = IrDiagnosticsHandler(testServices)

  override val additionalServices: List<ServiceRegistrationData>
    get() = delegate.additionalServices

  override fun processModule(module: TestModule, info: IrBackendInput) {
    delegate.processModule(module, info)
  }

  override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
    (rendererField.get(delegate) as FullDiagnosticsRenderer).assertCollectedDiagnostics(
      testServices,
      ".ir.diag.txt",
    )
  }

  private companion object {
    private val rendererField =
      IrDiagnosticsHandler::class.java.getDeclaredField("fullDiagnosticsRenderer").apply {
        isAccessible = true
      }
  }
}
