// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.diagnostics.AbstractSourceElementPositioningStrategy
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory0DelegateProvider
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory1DelegateProvider
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory2DelegateProvider
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies

/**
 * The compiler and IDE use different PSI classpaths — the compiler’s embeddable distribution
 * shades IntelliJ packages to `org.jetbrains.kotlin.com.intellij.*`.
 *
 * FIR Analysis is expected to always use the shaded PSI classes, but in practice (unfortunately) this depends on
 * the root project target: JVM projects use the shaded version, while non-JVM projects use the
 * unshaded one. This mismatch can cause `ClassCastException`s such as:
 * ```
 * IllegalArgumentException: class org.jetbrains.kotlin.psi.KtCallExpression
 * is not a subtype of class org.jetbrains.kotlin.com.intellij.psi.PsiElement
 * ```
 *
 * These exceptions then cause the custom FIR diagnostics to silently fail - aborting all custom analysis.
 *
 * Inverting the check ensures we pick the correct PSI supertype after element resolution,
 * allowing FIR analysis to work consistently across JVM and non-JVM targets.
 *
 * Adapted from:
 * [reference](https://github.com/TadeasKriz/K2PluginBase/blob/main/kotlin-plugin/src/main/kotlin/com/tadeaskriz/example/ExamplePluginErrors.kt#L8)
 */
internal val psiElementClass by lazy {
  try {
      Class.forName("com.intellij.psi.PsiElement")
    } catch (_: ClassNotFoundException) {
      Class.forName("org.jetbrains.kotlin.com.intellij.psi.PsiElement")
    }
    .kotlin
}

/* Copies of errors/warnings with a hack for the correct `PsiElement` class. */
context(container: KtDiagnosticsContainer)
internal fun warning0(
  positioningStrategy: AbstractSourceElementPositioningStrategy =
    SourceElementPositioningStrategies.DEFAULT
) =
  DiagnosticFactory0DelegateProvider(
    severity = Severity.WARNING,
    positioningStrategy = positioningStrategy,
    psiType = psiElementClass,
    container = container
  )

context(container: KtDiagnosticsContainer)
internal fun <T> warning1(
  positioningStrategy: AbstractSourceElementPositioningStrategy =
    SourceElementPositioningStrategies.DEFAULT
) =
  DiagnosticFactory1DelegateProvider<T>(
    severity = Severity.WARNING,
    positioningStrategy = positioningStrategy,
    psiType = psiElementClass,
    container = container
  )

context(container: KtDiagnosticsContainer)
internal fun error0(
  positioningStrategy: AbstractSourceElementPositioningStrategy =
    SourceElementPositioningStrategies.DEFAULT
) =
  DiagnosticFactory0DelegateProvider(
    severity = Severity.ERROR,
    positioningStrategy = positioningStrategy,
    psiType = psiElementClass,
    container = container
  )

context(container: KtDiagnosticsContainer)
internal fun <A> error1(
  positioningStrategy: AbstractSourceElementPositioningStrategy =
    SourceElementPositioningStrategies.DEFAULT
) =
  DiagnosticFactory1DelegateProvider<A>(
    severity = Severity.ERROR,
    positioningStrategy = positioningStrategy,
    psiType = psiElementClass,
    container = container
  )

context(container: KtDiagnosticsContainer)
internal fun <A, B> error2(
  positioningStrategy: AbstractSourceElementPositioningStrategy =
    SourceElementPositioningStrategies.DEFAULT
) =
  DiagnosticFactory2DelegateProvider<A, B>(
    severity = Severity.ERROR,
    positioningStrategy = positioningStrategy,
    psiType = psiElementClass,
    container = container
  )
