// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1

/**
 * The default [MetroDiagnostics] factory that transports the rendered message through kotlinc's
 * diagnostic reporting. Call sites with severity-dependent factories, like
 * [MetroDiagnosticId.UNUSED_GRAPH_INPUTS] mapping a configured severity to warning or error
 * variants, select their own instead.
 */
internal val MetroDiagnosticId.factory: KtDiagnosticFactory1<String>
  get() =
    when (this) {
      MetroDiagnosticId.MISSING_BINDING -> MetroDiagnostics.MISSING_BINDING
      MetroDiagnosticId.DUPLICATE_BINDING -> MetroDiagnostics.DUPLICATE_BINDING
      MetroDiagnosticId.DEPENDENCY_CYCLE -> MetroDiagnostics.GRAPH_DEPENDENCY_CYCLE
      MetroDiagnosticId.DUPLICATE_MAP_KEYS -> MetroDiagnostics.DUPLICATE_MAP_KEY
      MetroDiagnosticId.EMPTY_MULTIBINDING -> MetroDiagnostics.EMPTY_MULTIBINDING
      MetroDiagnosticId.SUSPICIOUS_UNUSED_MULTIBINDING ->
        MetroDiagnostics.SUSPICIOUS_UNUSED_MULTIBINDING
      MetroDiagnosticId.INCOMPATIBLY_SCOPED_BINDINGS -> MetroDiagnostics.INCOMPATIBLE_SCOPE
      MetroDiagnosticId.INCOMPATIBLE_RETURN_TYPES -> MetroDiagnostics.INCOMPATIBLE_RETURN_TYPES
      MetroDiagnosticId.INCOMPATIBLE_OVERRIDES -> MetroDiagnostics.INCOMPATIBLE_OVERRIDES
      MetroDiagnosticId.QUALIFIER_OVERRIDE_MISMATCH -> MetroDiagnostics.QUALIFIER_OVERRIDE_MISMATCH
      MetroDiagnosticId.INVALID_BINDING -> MetroDiagnostics.INVALID_ASSISTED_BINDING
      MetroDiagnosticId.UNPROCESSED_UPSTREAM_DECLARATION ->
        MetroDiagnostics.UNPROCESSED_UPSTREAM_DECLARATION
      MetroDiagnosticId.UNUSED_GRAPH_INPUTS -> MetroDiagnostics.UNUSED_GRAPH_INPUT_WARNING
      MetroDiagnosticId.GENERIC -> MetroDiagnostics.METRO_ERROR
    }
