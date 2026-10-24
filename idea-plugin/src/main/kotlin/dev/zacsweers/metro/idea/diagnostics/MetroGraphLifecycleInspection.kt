// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.diagnostics

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElementVisitor
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.metroIdeState
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

private val lifecycleAnnotations = setOf(MetroClassIds.onGraphInit, MetroClassIds.onGraphClose)

/** Checks callback declarations and explicit uses independently of retained graph validation. */
internal class MetroGraphLifecycleInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val file = holder.file as? KtFile ?: return PsiElementVisitor.EMPTY_VISITOR
    if (file.isCompiled || DumbService.isDumb(file.project)) {
      return PsiElementVisitor.EMPTY_VISITOR
    }
    if (!file.metroIdeState().isEnabled) {
      return PsiElementVisitor.EMPTY_VISITOR
    }
    return object : KtVisitorVoid() {
      override fun visitNamedFunction(function: KtNamedFunction) {
        lifecycleAnalysis(function) {
          val symbol = function.symbol as? KaNamedFunctionSymbol ?: return@lifecycleAnalysis
          val inherited =
            symbol.allOverriddenSymbols
              .flatMap { overridden ->
                lifecycleAnnotations.filter { overridden.hasAnyAnnotation(setOf(it)) }
              }
              .toSet()
          val missing = inherited.filterNot { symbol.hasAnyAnnotation(setOf(it)) }
          val anchor = function.nameIdentifier ?: function
          if (missing.isNotEmpty()) {
            holder.registerProblem(
              anchor,
              "Overrides must repeat ${missing.joinToString { "@${it.shortClassName}" }}.",
            )
          }
          if (!symbol.hasAnyAnnotation(lifecycleAnnotations)) {
            return@lifecycleAnalysis
          }
          val implementedUnit = function.bodyExpression != null && symbol.returnType.isUnitType
          if (!implementedUnit || function.containingClassOrObject == null) {
            holder.registerProblem(
              anchor,
              "Lifecycle callbacks must be implemented member functions returning Unit.",
            )
          }
          val hasReceiver =
            symbol.receiverParameter != null || symbol.contextParameters.isNotEmpty()
          if (hasReceiver || symbol.typeParameters.isNotEmpty()) {
            holder.registerProblem(
              anchor,
              "Lifecycle callbacks cannot declare receivers or type parameters.",
            )
          }
        }
      }

      override fun visitCallExpression(expression: KtCallExpression) {
        lifecycleAnalysis(expression) {
          val function =
            expression.resolveToCall()?.successfulFunctionCallOrNull()?.signature?.symbol
              ?: return@lifecycleAnalysis
          if (function.fakeOverrideOriginal.hasAnyAnnotation(lifecycleAnnotations)) {
            holder.registerProblem(expression, "Lifecycle callbacks cannot be called directly.")
          }
        }
      }

      override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
        lifecycleAnalysis(expression) {
          val declaration =
            expression.callableReference.mainReference.resolve() as? KtNamedFunction
              ?: return@lifecycleAnalysis
          val symbol = declaration.symbol
          if (symbol.fakeOverrideOriginal.hasAnyAnnotation(lifecycleAnnotations)) {
            holder.registerProblem(
              expression,
              "Callable references to lifecycle callbacks are prohibited.",
            )
          }
        }
      }
    }
  }
}

private fun lifecycleAnalysis(element: KtElement, action: KaSession.() -> Unit) {
  val analyzeElement = { analyze(element, action) }
  if (ApplicationManager.getApplication().isDispatchThread) {
    allowAnalysisOnEdt(analyzeElement)
  } else {
    analyzeElement()
  }
}
