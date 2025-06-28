// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.error1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticRenderers.TO_STRING
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.NAME_IDENTIFIER
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory

internal object MetroIrErrors : BaseDiagnosticRendererFactory() {
  val GRAPH_DEPENDENCY_CYCLE by error1<String>(NAME_IDENTIFIER)
  val METRO_ERROR by error1<String>(NAME_IDENTIFIER)

  override val MAP: KtDiagnosticFactoryToRendererMap =
    KtDiagnosticFactoryToRendererMap("MetroIrErrors").apply {
      put(METRO_ERROR, "{0}", TO_STRING)
      put(GRAPH_DEPENDENCY_CYCLE, "[Metro/GraphDependencyCycle] {0}", TO_STRING)
    }

  init {
    RootDiagnosticRendererFactory.registerFactory(this)
  }
}
