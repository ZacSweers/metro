// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k240_dev_539

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k2320_beta2.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

public class CompatContextImpl : CompatContext by DelegateType() {
  context(_: CompilerPluginRegistrar)
  override fun CompilerPluginRegistrar.ExtensionStorage.registerFirExtensionCompat(
    extension: FirExtensionRegistrar
  ) {
    FirExtensionRegistrarAdapter.registerExtension(extension)
  }

  context(_: CompilerPluginRegistrar)
  override fun CompilerPluginRegistrar.ExtensionStorage.registerIrExtensionCompat(
    extension: IrGenerationExtension
  ) {
    IrGenerationExtension.registerExtension(extension)
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.4.0-dev-539"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
