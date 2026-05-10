// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.Constructor
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.frontend.fir.FirOutputArtifact
import org.jetbrains.kotlin.test.frontend.fir.handlers.FirAnalysisHandler
import org.jetbrains.kotlin.test.frontend.fir.handlers.FirDiagnosticsHandler
import org.jetbrains.kotlin.test.frontend.fir.handlers.FullDiagnosticsRenderer
import org.jetbrains.kotlin.test.model.AfterAnalysisChecker
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.ServiceRegistrationData
import org.jetbrains.kotlin.test.services.TestServices

/**
 * Drop-in replacement for [FirDiagnosticsHandler] that asserts the full-text diagnostics dump
 * against `<name>.diag.txt` (no `.fir.` prefix). Matches the post-KT-85292 naming that ships in
 * Kotlin 2.4.20+.
 *
 * Encapsulates a real [FirDiagnosticsHandler] internally. We only intercept
 * [processAfterAllModules] to point the assertion at the new extension, reaching into the
 * delegate's private renderer reflectively.
 *
 * Wired in by [AbstractDiagnosticTest] only when [NEEDS_LEGACY_GOLDEN_NAMING] is true. Once Metro's
 * floor is 2.4.20+, this class can be deleted.
 */
class MetroFirDiagnosticsHandler(testServices: TestServices) : FirAnalysisHandler(testServices) {
  private val delegate = FirDiagnosticsHandler(testServices)

  override val directiveContainers: List<DirectivesContainer>
    get() = delegate.directiveContainers

  override val additionalServices: List<ServiceRegistrationData>
    get() = delegate.additionalServices

  override val additionalAfterAnalysisCheckers: List<Constructor<AfterAnalysisChecker>>
    get() = delegate.additionalAfterAnalysisCheckers

  override fun processModule(module: TestModule, info: FirOutputArtifact) {
    delegate.processModule(module, info)
  }

  override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
    (rendererField.get(delegate) as FullDiagnosticsRenderer).assertCollectedDiagnostics(
      testServices,
      ".diag.txt",
    )
  }

  private companion object {
    private val rendererField =
      FirDiagnosticsHandler::class.java.getDeclaredField("fullDiagnosticsRenderer").apply {
        isAccessible = true
      }
  }
}
