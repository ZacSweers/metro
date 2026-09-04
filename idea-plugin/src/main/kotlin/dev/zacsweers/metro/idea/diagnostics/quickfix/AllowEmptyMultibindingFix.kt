// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.diagnostics.quickfix

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandQuickFix
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.idea.diagnostics.MetroEditorDiagnostic
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.model.KaBinding
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Uses the annotation identity captured by indexing without resolving annotations in highlighting.
 */
internal fun allowEmptyMultibindingFix(diagnostic: MetroEditorDiagnostic): LocalQuickFix? {
  if (diagnostic.diagnostic.id != MetroDiagnosticId.EMPTY_MULTIBINDING) return null
  val binding =
    diagnostic.diagnostic.related.singleOrNull() as? KaBinding.Multibinding ?: return null
  val pointer = binding.metroMultibindsAnnotation ?: return null
  val annotation = editableAnnotation(pointer) ?: return null
  return AllowEmptyMultibindingFix(pointer, annotation.text, diagnostic.result)
}

/**
 * The platform applies this source-only change as an undoable command and provides its diff
 * preview.
 */
private class AllowEmptyMultibindingFix(
  private val pointer: SmartPsiElementPointer<KtAnnotationEntry>,
  private val expectedText: String,
  private val result: KaGraphValidationResult.Completed,
) : ModCommandQuickFix() {
  override fun getFamilyName(): String = "Allow an empty multibinding"

  override fun perform(project: Project, descriptor: ProblemDescriptor): ModCommand {
    // A source edit can change an import alias or the graph that made this annotation relevant.
    val current =
      project.service<MetroGraphValidationService>().retainedResults().any {
        !it.stale && it.result === result
      }
    if (!current) return ModCommand.nop()
    val annotation = editableAnnotation(pointer) ?: return ModCommand.nop()
    if (annotation.text != expectedText) return ModCommand.nop()
    // The descriptor supplies the preview file when the platform asks for an intention preview.
    return ModCommand.psiUpdate(ActionContext.from(descriptor)) { updater ->
      val writable = updater.getWritable(annotation)
      val factory = KtPsiFactory(project)
      val arguments = writable.valueArgumentList
      when {
        arguments == null -> writable.add(factory.createCallArguments("(allowEmpty = true)"))
        arguments.arguments.isEmpty() -> {
          arguments.addBefore(
            factory.createArgument("allowEmpty = true"),
            arguments.rightParenthesis,
          )
        }
        else -> {
          val expression = checkNotNull(arguments.arguments.single().getArgumentExpression())
          expression.replace(factory.createExpression("true"))
        }
      }
    }
  }
}

/** Keeps the edit limited to omitted arguments and a single literal `false` argument. */
private fun editableAnnotation(
  pointer: SmartPsiElementPointer<KtAnnotationEntry>
): KtAnnotationEntry? {
  val annotation = pointer.element ?: return null
  // ModCommand requests write access when the user applies the fix.
  if (!annotation.isValid) return null
  val file = annotation.containingFile as? KtFile ?: return null
  if (file.isCompiled) return null
  val virtualFile = file.virtualFile ?: return null
  if (!ProjectFileIndex.getInstance(annotation.project).isInSourceContent(virtualFile)) return null
  if (PsiTreeUtil.findChildOfType(annotation, PsiErrorElement::class.java) != null) return null
  val arguments = annotation.valueArgumentList ?: return annotation
  if (arguments.rightParenthesis == null) return null
  if (arguments.arguments.isEmpty()) return annotation
  val argument = arguments.arguments.singleOrNull() ?: return null
  if (argument.getSpreadElement() != null) return null
  val argumentName = argument.getArgumentName()?.asName?.asString()
  if (argumentName != null && argumentName != "allowEmpty") return null
  val expression = argument.getArgumentExpression() as? KtConstantExpression ?: return null
  return annotation.takeIf { expression.text == "false" }
}
