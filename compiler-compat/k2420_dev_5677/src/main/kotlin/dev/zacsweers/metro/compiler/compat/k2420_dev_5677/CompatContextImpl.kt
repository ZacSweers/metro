// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k2420_dev_5677

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import dev.zacsweers.metro.compiler.compat.k2420_dev_835.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext

public class CompatContextImpl : CompatContext by DelegateType() {
  override fun createIrGeneratedDeclarationsRegistrar(
    pluginContext: IrPluginContext
  ): IrGeneratedDeclarationsRegistrarCompat {
    return IrAnnotationIrGeneratedDeclarationsRegistrarCompat(
      pluginContext.metadataDeclarationRegistrar
    )
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.4.20-dev-5625"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
