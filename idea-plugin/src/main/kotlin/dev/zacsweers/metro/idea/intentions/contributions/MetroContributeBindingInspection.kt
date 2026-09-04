// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.intentions.contributions

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElementVisitor
import dev.zacsweers.metro.idea.metroIdeState
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtVisitorVoid

/** Offers an editor suggestion for classes whose construction and bound types are already valid. */
internal class MetroContributeBindingInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val file = holder.file as? KtFile ?: return PsiElementVisitor.EMPTY_VISITOR
    if (!isOnTheFly || file.isCompiled || DumbService.isDumb(file.project))
      return PsiElementVisitor.EMPTY_VISITOR
    if (!file.metroIdeState().isEnabled) return PsiElementVisitor.EMPTY_VISITOR
    return object : KtVisitorVoid() {
      override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        val name = classOrObject.nameIdentifier ?: return
        if (contributionCandidate(classOrObject) == null) return
        val fix = LocalQuickFix.from(ContributeBindingAction(classOrObject), false) ?: return
        holder.registerProblem(
          name,
          "Class can contribute a Metro binding",
          ProblemHighlightType.INFORMATION,
          fix,
        )
      }
    }
  }
}
