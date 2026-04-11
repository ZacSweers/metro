// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.circuit.CircuitDiagnostics.CIRCUIT_INJECT_ERROR
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.LanguageVersionSettingsCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.config.FirLanguageVersionSettingsChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

internal class CircuitFirCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers =
    object : DeclarationCheckers() {
      override val classCheckers: Set<FirClassChecker>
        get() = setOf(CircuitInjectClassChecker)

      override val callableDeclarationCheckers: Set<FirCallableDeclarationChecker>
        get() = setOf(CircuitInjectCallableChecker)
    }

  override val languageVersionSettingsCheckers: LanguageVersionSettingsCheckers
    get() =
      object : LanguageVersionSettingsCheckers() {
        override val languageVersionSettingsCheckers: Set<FirLanguageVersionSettingsChecker>
          get() = setOf(CircuitInjectLanguageChecker)
      }
}

internal object CircuitInjectLanguageChecker : FirLanguageVersionSettingsChecker() {
  context(context: CheckerContext)
  override fun check(reporter: DiagnosticReporter) {
    val session = context.session
    val circuitInjectDeclarationsByName =
      CircuitFirExtension.findCircuitInjectFunctions(
          CircuitFirExtension.findCircuitInjectSymbols(session)
        )
        .groupBy { it.name }

    for ((name, functions) in circuitInjectDeclarationsByName) {
      if (functions.size > 1) {
        for (function in functions) {
          reporter.reportOn(
            function.source,
            CIRCUIT_INJECT_ERROR,
            "Multiple @CircuitInject-annotated functions named $name were found. " +
              "This will create conflicts in Circuit FIR code gen, please deduplicate names.",
          )
        }
      }
    }
  }
}
