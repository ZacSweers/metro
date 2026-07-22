// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k250_dev_498

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k2420_beta2.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment as createEmptyExternalPackageFragmentNative
import org.jetbrains.kotlin.name.FqName

public class CompatContextImpl : CompatContext by DelegateType() {
  override fun IrModuleFragment.createEmptyExternalPackageFragmentCompat(
    packageName: String
  ): IrPackageFragment {
    return createEmptyExternalPackageFragmentNative(this, FqName(packageName))
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.5.0-dev-498"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
