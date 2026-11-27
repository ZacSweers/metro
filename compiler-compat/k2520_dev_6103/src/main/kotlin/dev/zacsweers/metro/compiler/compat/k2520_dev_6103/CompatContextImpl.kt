// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k2520_dev_6103

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k250_dev_10106.CompatContextImpl as DelegateType
import kotlin.reflect.KClass
import org.jetbrains.kotlin.diagnostics.AbstractSourceElementPositioningStrategy
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory0DelegateProvider
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory1DelegateProvider
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory2DelegateProvider
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.resolve.TypeResolutionConfiguration
import org.jetbrains.kotlin.fir.scopes.FirScope

/** Adapts diagnostics and type-resolution configuration to Kotlin 2.5.20-dev-6103. */
public class CompatContextImpl : CompatContext by DelegateType() {
  override fun KtDiagnosticsContainer.diagnosticFactory0Compat(
    severity: Severity,
    positioningStrategy: AbstractSourceElementPositioningStrategy,
    psiType: KClass<*>,
  ): DiagnosticFactory0DelegateProvider =
    DiagnosticFactory0DelegateProvider(severity, positioningStrategy, this)

  override fun <A> KtDiagnosticsContainer.diagnosticFactory1Compat(
    severity: Severity,
    positioningStrategy: AbstractSourceElementPositioningStrategy,
    psiType: KClass<*>,
  ): DiagnosticFactory1DelegateProvider<A> =
    DiagnosticFactory1DelegateProvider(severity, positioningStrategy, this)

  override fun <A, B> KtDiagnosticsContainer.diagnosticFactory2Compat(
    severity: Severity,
    positioningStrategy: AbstractSourceElementPositioningStrategy,
    psiType: KClass<*>,
  ): DiagnosticFactory2DelegateProvider<A, B> =
    DiagnosticFactory2DelegateProvider(severity, positioningStrategy, this)

  override fun createTypeResolutionConfigurationCompat(
    scopes: Iterable<FirScope>,
    useSiteFile: FirFile?,
  ): TypeResolutionConfiguration =
    TypeResolutionConfiguration(scopes, emptyList(), useSiteFile = useSiteFile)

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.5.20-dev-6103"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
