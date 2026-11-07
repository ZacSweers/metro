// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k250_dev_7307

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import dev.zacsweers.metro.compiler.compat.k250_dev_6460.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.at
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.fileForTopLevelPluginDeclarations

/**
 * Adapts IR APIs to Kotlin 2.5.0-dev-7307. Metro's companion-block support starts with this
 * adapter, which Kotlin 2.5.0-Beta1 uses.
 */
public class CompatContextImpl(private val delegate: CompatContext = DelegateType()) :
  CompatContext by delegate {
  override val supportsCompanionBlocks: Boolean = true

  override fun IrFile.clearTopLevelPluginFileMarkerCompat() {
    fileForTopLevelPluginDeclarations = false
  }

  override fun <A : Any> IrDiagnosticReporter.reportAt(
    declaration: IrDeclaration,
    factory: KtDiagnosticFactory1<A>,
    a: A,
  ) {
    // Kotlin now exposes the declaration overload as an extension.
    at(declaration).report(factory, a)
  }

  /**
   * Marks IR-generated companion-block functions as static in Kotlin metadata. Works around
   * [KT-90383](https://youtrack.jetbrains.com/issue/KT-90383).
   */
  override fun createIrGeneratedDeclarationsRegistrar(
    pluginContext: IrPluginContext,
  ): IrGeneratedDeclarationsRegistrarCompat {
    val registrar = delegate.createIrGeneratedDeclarationsRegistrar(pluginContext)
    if (!pluginContext.languageVersionSettings.companionBlocksEnabledCompat()) {
      return registrar
    }
    return CompanionBlockMetadataRegistrar(registrar)
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.5.0-dev-7307"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
