// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.intentions.contributions

import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.openapi.util.TextRange
import dev.zacsweers.metro.idea.intentions.addMetroAnnotation
import dev.zacsweers.metro.idea.metroIdeState
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Detached choices retained between picker steps. Live template fields belong to the final edit.
 */
internal data class ContributionEdit(
  val kind: ContributionKind,
  val boundType: ContributionBoundType,
  val scope: ContributionScope,
  val mapKey: ContributionMapKeyChoice? = null,
)

/** Rechecks chosen types and keys before producing one undoable annotation edit. */
internal fun applyContribution(
  context: ActionContext,
  owner: KtClassOrObject,
  edit: ContributionEdit,
): ModCommand {
  val candidate = contributionCandidate(owner) ?: return ModCommand.nop()
  if (edit.boundType !in candidate.boundTypes) return ModCommand.nop()
  val existingKey = candidate.existingMapKey || edit.boundType.hasMapKey
  if (edit.kind != ContributionKind.MAP && existingKey) return ModCommand.nop()
  if (!edit.scope.editable) {
    val scopeId = edit.scope.classId ?: return ModCommand.nop()
    if (edit.scope != contributionScope(owner, scopeId)) return ModCommand.nop()
  }
  val key = edit.mapKey
  val hasMapKey = existingKey || key != null
  if (edit.kind == ContributionKind.MAP && !hasMapKey) return ModCommand.nop()
  if (key != null) {
    val options = owner.metroIdeState().options
    val currentKey = analyze(owner) { contributionMapKeyChoice(owner, options, key.classId) }
    if (key != currentKey) return ModCommand.nop()
  }
  val annotationText = buildString {
    append("@dev.zacsweers.metro.").append(edit.kind.annotationName)
    append('(').append(edit.scope.className).append("::class")
    if (!edit.boundType.implicit) {
      append(", binding = dev.zacsweers.metro.binding<")
        .append(edit.boundType.renderedType)
        .append(">()")
    }
    append(')')
  }
  return ModCommand.psiUpdate(context) { updater ->
    val writable = updater.getWritable(owner)
    val mapAnnotation =
      key
        ?.annotationText
        ?.takeIf { it.isNotEmpty() }
        ?.let {
          addMetroAnnotation(writable, it, updater)
        }
    val annotation = addMetroAnnotation(writable, annotationText, updater)
    val hasKeyFields = mapAnnotation != null && key.editableArguments.isNotEmpty()
    if (!edit.scope.editable && !hasKeyFields) return@psiUpdate
    val template = updater.templateBuilder()
    if (edit.scope.editable) {
      val scopeArgument =
        annotation.valueArguments.first().getArgumentExpression() as KtClassLiteralExpression
      val receiver = checkNotNull(scopeArgument.receiverExpression)
      template.field(receiver, "scope", ConstantNode(receiver.text))
    }
    if (mapAnnotation != null) {
      for (argument in mapAnnotation.valueArguments) {
        val name = argument.getArgumentName()?.asName?.asString() ?: continue
        if (name !in key.editableArguments) continue
        val expression = argument.getArgumentExpression() ?: continue
        if (expression is KtStringTemplateExpression) {
          val range = TextRange(1, expression.textLength - 1)
          template.field(
            expression,
            range,
            "mapKey_$name",
            ConstantNode(range.substring(expression.text)),
          )
        } else {
          template.field(expression, "mapKey_$name", ConstantNode(expression.text))
        }
      }
    }
    template.finishAt(writable.nameIdentifier?.textRange?.endOffset ?: writable.textRange.endOffset)
  }
}
