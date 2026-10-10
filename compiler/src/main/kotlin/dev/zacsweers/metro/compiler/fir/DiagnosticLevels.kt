// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

/** Names of every Metro diagnostic. */
internal val metroDiagnosticNames: Set<String> by lazy {
  MetroDiagnostics.getRendererFactory().MAP.factories.mapTo(mutableSetOf()) { it.name }
}

/**
 * Names of Metro diagnostics that IR reports. The `diagnostic-level` option can't change these yet
 * because it only applies to FIR checkers. Some of these are also reported in FIR.
 */
internal val irReportedDiagnosticNames: Set<String> by lazy {
  setOf(
      MetroDiagnostics.ASSISTED_FACTORY_SUSPEND_REQUIRED,
      MetroDiagnostics.DUPLICATE_BINDING,
      MetroDiagnostics.DUPLICATE_MAP_KEY,
      MetroDiagnostics.EMPTY_MULTIBINDING,
      MetroDiagnostics.GRAPH_DEPENDENCY_CYCLE,
      MetroDiagnostics.INCOMPATIBLE_OVERRIDES,
      MetroDiagnostics.INCOMPATIBLE_RETURN_TYPES,
      MetroDiagnostics.INCOMPATIBLE_SCOPE,
      MetroDiagnostics.INVALID_ASSISTED_BINDING,
      MetroDiagnostics.KNOWN_KOTLINC_BUG_ERROR,
      MetroDiagnostics.KNOWN_KOTLINC_BUG_WARNING,
      MetroDiagnostics.MEMBER_INJECTION_OVER_SUSPEND_BINDING,
      MetroDiagnostics.METRO_ERROR,
      MetroDiagnostics.METRO_TRACE_ERROR,
      MetroDiagnostics.METRO_WARNING,
      MetroDiagnostics.MISSING_BINDING,
      MetroDiagnostics.MISSING_RUNTIME_COROUTINES,
      MetroDiagnostics.MULTIBINDING_OVER_SUSPEND_BINDINGS,
      MetroDiagnostics.PRIVATE_BINDING_ERROR,
      MetroDiagnostics.QUALIFIER_OVERRIDE_MISMATCH,
      MetroDiagnostics.SOURCELESS_METRO_ERROR,
      MetroDiagnostics.SOURCELESS_METRO_WARNING,
      MetroDiagnostics.SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR,
      MetroDiagnostics.SUSPEND_BINDING_WRAPPED_IN_LAZY,
      MetroDiagnostics.SUSPEND_BINDING_WRAPPED_IN_PROVIDER,
      MetroDiagnostics.SUSPEND_PROVIDERS_NOT_ENABLED,
      MetroDiagnostics.SUSPICIOUS_MEMBER_INJECT_FUNCTION,
      MetroDiagnostics.SUSPICIOUS_UNUSED_MULTIBINDING,
      MetroDiagnostics.UNPROCESSED_UPSTREAM_DECLARATION,
      MetroDiagnostics.UNUSED_GRAPH_INPUT_ERROR,
      MetroDiagnostics.UNUSED_GRAPH_INPUT_WARNING,
    )
    .mapTo(mutableSetOf()) { it.name }
}
