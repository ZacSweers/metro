// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.intellij.psi.PsiElement
import dev.zacsweers.metro.compiler.compat.CompatContext
import org.jetbrains.kotlin.diagnostics.AbstractSourceElementPositioningStrategy
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity.ERROR
import org.jetbrains.kotlin.diagnostics.Severity.WARNING
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.DEFAULT

private val diagnosticFactoryCompatContext by lazy { CompatContext.create() }

internal inline fun <reified P : PsiElement> KtDiagnosticsContainer.error0Compat(
  positioningStrategy: AbstractSourceElementPositioningStrategy = DEFAULT,
) =
  with(diagnosticFactoryCompatContext) {
    diagnosticFactory0Compat(ERROR, positioningStrategy, P::class)
  }

internal inline fun <reified P : PsiElement, A> KtDiagnosticsContainer.error1Compat(
  positioningStrategy: AbstractSourceElementPositioningStrategy = DEFAULT,
) =
  with(diagnosticFactoryCompatContext) {
    diagnosticFactory1Compat<A>(ERROR, positioningStrategy, P::class)
  }

internal inline fun <reified P : PsiElement, A, B> KtDiagnosticsContainer.error2Compat(
  positioningStrategy: AbstractSourceElementPositioningStrategy = DEFAULT,
) =
  with(diagnosticFactoryCompatContext) {
    diagnosticFactory2Compat<A, B>(ERROR, positioningStrategy, P::class)
  }

internal inline fun <reified P : PsiElement> KtDiagnosticsContainer.warning0Compat(
  positioningStrategy: AbstractSourceElementPositioningStrategy = DEFAULT,
) =
  with(diagnosticFactoryCompatContext) {
    diagnosticFactory0Compat(WARNING, positioningStrategy, P::class)
  }

internal inline fun <reified P : PsiElement, A> KtDiagnosticsContainer.warning1Compat(
  positioningStrategy: AbstractSourceElementPositioningStrategy = DEFAULT,
) =
  with(diagnosticFactoryCompatContext) {
    diagnosticFactory1Compat<A>(WARNING, positioningStrategy, P::class)
  }
