// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k250_dev_6460

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k2420.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceElementOffsetStrategy
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.diagnostics.KtDiagnosticWithParameters1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticWithParameters2
import org.jetbrains.kotlin.diagnostics.KtDiagnosticWithParameters3
import org.jetbrains.kotlin.diagnostics.KtDiagnosticWithParameters4
import org.jetbrains.kotlin.diagnostics.KtDiagnosticWithoutSource
import org.jetbrains.kotlin.diagnostics.KtSimpleDiagnostic
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.containingClassForStaticMemberAttr
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildNamedFunctionCopy
import org.jetbrains.kotlin.fir.declarations.impl.FirDeclarationStatusImpl
import org.jetbrains.kotlin.fir.declarations.utils.isCompanionBlockMember
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment as createEmptyExternalPackageFragmentNative
import org.jetbrains.kotlin.name.FqName

/**
 * Adapts source locations, diagnostic severity, package fragments, and companion APIs to Kotlin
 * 2.5.0-dev-6460.
 */
public class CompatContextImpl : CompatContext by DelegateType() {
  override fun KtDiagnostic.withSeverityCompat(severity: Severity): KtDiagnostic {
    return when (this) {
      is KtSimpleDiagnostic -> copy(severity = severity)
      is KtDiagnosticWithParameters1<*> -> copy(severity = severity)
      is KtDiagnosticWithParameters2<*, *> -> copy(severity = severity)
      is KtDiagnosticWithParameters3<*, *, *> -> copy(severity = severity)
      is KtDiagnosticWithParameters4<*, *, *, *> -> copy(severity = severity)
      is KtDiagnosticWithoutSource ->
        KtDiagnosticWithoutSource(message, location, severity, factory, context)
    }
  }

  override fun FirValueParameterSymbol.defaultValueSourceCompat(): KtSourceElement? {
    // The default expression remains available across the source accessor rename in newer builds.
    return resolvedDefaultValue?.source
  }

  override fun IrModuleFragment.createEmptyExternalPackageFragmentCompat(
    packageName: String,
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

  override val supportsCompanionBlocks: Boolean = true

  override val supportsCompanionExtensions: Boolean = true

  override fun LanguageVersionSettings.companionBlocksEnabledCompat(): Boolean {
    return supportsFeature(LanguageFeature.CompanionBlocks)
  }

  override fun LanguageVersionSettings.companionExtensionsEnabledCompat(): Boolean {
    return supportsFeature(LanguageFeature.CompanionExtensions)
  }

  override val FirCallableSymbol<*>.isCompanionBlockMemberCompat: Boolean
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

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.5.0-dev-6460"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
