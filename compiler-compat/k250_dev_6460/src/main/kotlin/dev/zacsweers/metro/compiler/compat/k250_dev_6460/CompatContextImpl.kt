// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k250_dev_6460

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k2420.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceElementOffsetStrategy
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment as createEmptyExternalPackageFragmentNative
import org.jetbrains.kotlin.name.FqName

/** Adapts package fragments and source locations to Kotlin 2.5.0-dev-6460. */
public class CompatContextImpl : CompatContext by DelegateType() {
  override fun FirValueParameterSymbol.defaultValueSourceCompat(): KtSourceElement? {
    // The default expression remains available across the source accessor rename in newer builds.
    return resolvedDefaultValue?.source
  }

  override fun IrModuleFragment.createEmptyExternalPackageFragmentCompat(
    packageName: String
  ): IrPackageFragment {
    return createEmptyExternalPackageFragmentNative(this, FqName(packageName))
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
    override val minVersion: String = "2.5.0-dev-6460"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
