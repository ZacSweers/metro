// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.diagnostics

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.idea.diagnostics.quickfix.allowEmptyMultibindingFix
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.metroIdeState
import java.util.IdentityHashMap
import org.jetbrains.kotlin.psi.KtFile

/** Publishes completed Metro diagnostics through the editor and the platform's Problems view. */
internal class MetroGraphInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val file = holder.file as? KtFile ?: return PsiElementVisitor.EMPTY_VISITOR
    if (file.isCompiled || DumbService.isDumb(file.project)) return PsiElementVisitor.EMPTY_VISITOR
    if (!file.metroIdeState().isEnabled) return PsiElementVisitor.EMPTY_VISITOR
    val results = file.project.service<MetroGraphValidationService>().retainedResults()
    val diagnostics = metroDiagnosticsForFile(file, results)
    if (diagnostics.isEmpty()) return PsiElementVisitor.EMPTY_VISITOR
    val byAnchor = IdentityHashMap<PsiElement, MutableList<MetroEditorDiagnostic>>()
    for (diagnostic in diagnostics) byAnchor.getOrPut(diagnostic.anchor) { mutableListOf() } +=
      diagnostic
    return object : PsiElementVisitor() {
      override fun visitElement(element: PsiElement) {
        for (diagnostic in byAnchor[element].orEmpty()) {
          val highlight =
            when (diagnostic.diagnostic.severity) {
              MetroSeverity.ERROR -> ProblemHighlightType.ERROR
              MetroSeverity.WARNING -> ProblemHighlightType.WARNING
            }
          val fixes = listOfNotNull(allowEmptyMultibindingFix(diagnostic)).toTypedArray()
          holder.registerProblem(element, diagnostic.description, highlight, *fixes)
        }
      }
    }
  }
}
