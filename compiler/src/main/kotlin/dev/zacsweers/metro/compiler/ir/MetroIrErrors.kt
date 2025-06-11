package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.diagnostics.BackendErrors
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory

internal object MetroIrErrors {
  val NON_LOCAL_RETURN_IN_DISABLED_INLINE by error0<PsiElement>(SourceElementPositioningStrategies.DEFAULT) // need to reference SourceElementPositioningStrategies at least once to initialize properly

}

internal object MetroIrErrorMessages : BaseDiagnosticRendererFactory() {
  override val MAP by KtDiagnosticFactoryToRendererMap("BackendErrors") { map ->
    map.put(BackendErrors.NON_LOCAL_RETURN_IN_DISABLED_INLINE, "Non-local returns are not allowed with inlining disabled")
  }
}
