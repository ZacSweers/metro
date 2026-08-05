// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.ABSTRACT_MODIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.INNER_MODIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.NAME_IDENTIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.TYPE_PARAMETERS_LIST
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.VISIBILITY_MODIFIER
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers.STRING
import org.jetbrains.kotlin.psi.KtElement

internal object CircuitDiagnostics : KtDiagnosticsContainer() {
  val CIRCUIT_INJECT_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val CIRCUIT_SERIALIZABLE_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val CIRCUIT_SERIALIZABLE_VISIBILITY_ERROR by error1<KtElement, String>(VISIBILITY_MODIFIER)
  val CIRCUIT_SERIALIZABLE_ABSTRACT_ERROR by error1<KtElement, String>(ABSTRACT_MODIFIER)
  val CIRCUIT_SERIALIZABLE_INNER_ERROR by error1<KtElement, String>(INNER_MODIFIER)
  val CIRCUIT_SERIALIZABLE_TYPE_PARAMETERS_ERROR by error1<KtElement, String>(TYPE_PARAMETERS_LIST)

  override fun getRendererFactory(): BaseDiagnosticRendererFactory = CircuitErrorMessages
}

private object CircuitErrorMessages : BaseDiagnosticRendererFactory() {
  override val MAP by
    KtDiagnosticFactoryToRendererMap("Circuit") { map ->
      map.put(CircuitDiagnostics.CIRCUIT_INJECT_ERROR, "{0}", STRING)
      map.put(CircuitDiagnostics.CIRCUIT_SERIALIZABLE_ERROR, "{0}", STRING)
      map.put(CircuitDiagnostics.CIRCUIT_SERIALIZABLE_VISIBILITY_ERROR, "{0}", STRING)
      map.put(CircuitDiagnostics.CIRCUIT_SERIALIZABLE_ABSTRACT_ERROR, "{0}", STRING)
      map.put(CircuitDiagnostics.CIRCUIT_SERIALIZABLE_INNER_ERROR, "{0}", STRING)
      map.put(CircuitDiagnostics.CIRCUIT_SERIALIZABLE_TYPE_PARAMETERS_ERROR, "{0}", STRING)
    }
}
