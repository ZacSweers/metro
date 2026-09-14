// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k250_dev_7307

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k250_dev_4967.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceElementOffsetStrategy
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.at
import org.jetbrains.kotlin.ir.declarations.IrDeclaration

/** Adapts source locations and IR diagnostics to Kotlin 2.5.0-dev-7307. */
public class CompatContextImpl : CompatContext by DelegateType() {
  override fun FirValueParameterSymbol.defaultValueSourceCompat(): KtSourceElement? {
    return resolvedDefaultValueSource
  }

  override fun <A : Any> IrDiagnosticReporter.reportAt(
    declaration: IrDeclaration,
    factory: KtDiagnosticFactory1<A>,
    a: A,
  ) {
    // Kotlin now exposes the declaration overload as an extension.
    at(declaration).report(factory, a)
  }

  override fun KtSourceElement.fakeElement(
    newKind: KtFakeSourceElementKind,
    startOffset: Int,
    endOffset: Int,
  ): KtSourceElement {
    return this.fakeElement(
      newKind,
      KtSourceElementOffsetStrategy.Custom.Initialized(startOffset, endOffset),
    )
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.5.0-dev-7307"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
