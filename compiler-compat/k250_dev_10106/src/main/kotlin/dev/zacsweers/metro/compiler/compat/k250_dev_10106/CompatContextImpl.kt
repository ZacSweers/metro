// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k250_dev_10106

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k250_dev_7307.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.fir.copy
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile

/** Adapts FIR declaration status copying and IR diagnostic reporting to Kotlin 2.5.0-dev-10106. */
public class CompatContextImpl : CompatContext by DelegateType() {
  override fun FirDeclarationStatus.copyWithOverrideCompat(
    isOverride: Boolean,
  ): FirDeclarationStatus = copy(isOverride = isOverride)

  override fun <A : Any> IrDiagnosticReporter.reportAt(
    element: IrElement,
    file: IrFile,
    factory: KtDiagnosticFactory1<A>,
    a: A,
  ) {
    // at() gained an optional source element parameter.
    at(element, file).report(factory, a)
  }

  public class Factory : CompatContext.Factory {
    // The adapted FIR and IR APIs first shipped in this dev build. Prerelease mappings rely on this
    // floor.
    override val minVersion: String = "2.5.0-dev-9346"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
