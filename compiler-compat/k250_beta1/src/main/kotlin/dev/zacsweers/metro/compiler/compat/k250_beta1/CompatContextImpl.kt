// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k250_beta1

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import dev.zacsweers.metro.compiler.compat.k250_dev_7307.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.fir.containingClassForStaticMemberAttr
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildNamedFunctionCopy
import org.jetbrains.kotlin.fir.declarations.impl.FirDeclarationStatusImpl
import org.jetbrains.kotlin.fir.declarations.utils.isCompanionBlockMember
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol

/** Adapts companion-block declarations and language-feature settings to Kotlin 2.5.0-Beta1. */
public class CompatContextImpl private constructor(private val delegate: DelegateType) :
  CompatContext by delegate {
  public constructor() : this(DelegateType())

  override val supportsCompanionBlocks: Boolean = true

  override val supportsCompanionExtensions: Boolean = true

  override fun LanguageVersionSettings.companionBlocksEnabledCompat(): Boolean {
    return supportsFeature(LanguageFeature.CompanionBlocks)
  }

  override fun LanguageVersionSettings.companionExtensionsEnabledCompat(): Boolean {
    return supportsFeature(LanguageFeature.CompanionExtensions)
  }

  override val FirCallableDeclaration.isCompanionBlockMemberCompat: Boolean
    get() = isCompanionBlockMember

  override fun FirFunction.markAsCompanionBlockMemberCompat(owner: FirClassSymbol<*>): FirFunction {
    // FIR fixes dispatch receiver types when it constructs declarations.
    val function = this as FirNamedFunction
    return buildNamedFunctionCopy(function) {
        symbol = function.symbol
        dispatchReceiverType = null
        (status as FirDeclarationStatusImpl).isStatic = true
      }
      .also { it.containingClassForStaticMemberAttr = owner.toLookupTag() }
  }

  override fun createIrGeneratedDeclarationsRegistrar(
    pluginContext: IrPluginContext
  ): IrGeneratedDeclarationsRegistrarCompat {
    val registrar = delegate.createIrGeneratedDeclarationsRegistrar(pluginContext)
    if (!pluginContext.languageVersionSettings.companionBlocksEnabledCompat()) {
      return registrar
    }
    return CompanionBlockMetadataRegistrar(registrar)
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.5.0-Beta1"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
