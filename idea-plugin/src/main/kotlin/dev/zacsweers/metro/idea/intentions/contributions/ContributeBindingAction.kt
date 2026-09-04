// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.intentions.contributions

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.Presentation
import com.intellij.modcommand.PsiBasedModCommandAction
import dev.zacsweers.metro.idea.metroIdeState
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtClassOrObject

/** The three binding contribution annotations share scope and bound-type selection. */
internal enum class ContributionKind(val annotationName: String) {
  BINDING("ContributesBinding"),
  SET("ContributesIntoSet"),
  MAP("ContributesIntoMap"),
}

/** Collects choices before changing source. Each step checks that its source class is unchanged. */
internal class ContributeBindingAction(owner: KtClassOrObject) :
  PsiBasedModCommandAction<KtClassOrObject>(owner) {
  override fun getFamilyName(): String = "Contribute Metro binding"

  override fun getPresentation(context: ActionContext, element: KtClassOrObject): Presentation? {
    if (contributionCandidate(element) == null) return null
    return Presentation.of(familyName)
  }

  override fun generatePreview(
    context: ActionContext,
    element: KtClassOrObject,
  ): IntentionPreviewInfo {
    return contributionPickerPreview()
  }

  override fun perform(context: ActionContext, element: KtClassOrObject): ModCommand {
    val candidate = contributionCandidate(element) ?: return ModCommand.nop()
    val expectedText = element.text
    val kinds =
      if (candidate.existingMapKey) listOf(ContributionKind.MAP) else ContributionKind.entries
    return ModCommand.chooseAction(
      "Contribution kind",
      kinds
        .filter { kind ->
          kind == ContributionKind.MAP || candidate.boundTypes.any { !it.hasMapKey }
        }
        .map { kind ->
          step(element, expectedText, kind.annotationName, hasRemainingChoices = true) {
            nextContext,
            current ->
            chooseBoundType(nextContext, current, candidate, kind, expectedText)
          }
        },
    )
  }

  private fun chooseBoundType(
    context: ActionContext,
    owner: KtClassOrObject,
    candidate: ContributionCandidate,
    kind: ContributionKind,
    expectedText: String,
  ): ModCommand {
    val types = candidate.boundTypes.filter { kind == ContributionKind.MAP || !it.hasMapKey }
    if (types.isEmpty()) return ModCommand.nop()
    val defaultType = types.singleOrNull { it.isDefault }
    if (defaultType != null)
      return chooseScope(context, owner, candidate, kind, defaultType, expectedText)
    if (types.size == 1)
      return chooseScope(context, owner, candidate, kind, types.single(), expectedText)
    val labelCounts = types.groupingBy { it.label }.eachCount()
    return ModCommand.chooseAction(
      "Bound type",
      types.map { type ->
        val label = if (labelCounts.getValue(type.label) > 1) type.renderedType else type.label
        step(owner, expectedText, label, hasRemainingChoices = true) { nextContext, current ->
          chooseScope(nextContext, current, candidate, kind, type, expectedText)
        }
      },
    )
  }

  private fun chooseScope(
    context: ActionContext,
    owner: KtClassOrObject,
    candidate: ContributionCandidate,
    kind: ContributionKind,
    type: ContributionBoundType,
    expectedText: String,
  ): ModCommand {
    val scopes = contributionScopes(owner, candidate.existingScope)
    val requiresKey = kind == ContributionKind.MAP && !candidate.existingMapKey && !type.hasMapKey
    return ModCommand.chooseAction(
      "Contribution scope",
      scopes.map { scope ->
        step(owner, expectedText, scope.label, hasRemainingChoices = requiresKey) {
          nextContext,
          current ->
          chooseMapKey(
            nextContext,
            current,
            candidate,
            ContributionEdit(kind, type, scope),
            expectedText,
          )
        }
      },
    )
  }

  private fun chooseMapKey(
    context: ActionContext,
    owner: KtClassOrObject,
    candidate: ContributionCandidate,
    edit: ContributionEdit,
    expectedText: String,
  ): ModCommand {
    val hasMapKey = candidate.existingMapKey || edit.boundType.hasMapKey
    val requiresKey = edit.kind == ContributionKind.MAP && !hasMapKey
    if (!requiresKey) return applyContribution(context, owner, edit)
    val options = owner.metroIdeState().options
    val keys = analyze(owner) { contributionMapKeyChoices(owner, options) }
    return ModCommand.chooseAction(
      "Map key",
      keys.map { key ->
        step(owner, expectedText, key.label, hasRemainingChoices = false) { nextContext, current ->
          applyContribution(nextContext, current, edit.copy(mapKey = key))
        }
      },
    )
  }

  private fun step(
    owner: KtClassOrObject,
    expectedText: String,
    title: String,
    hasRemainingChoices: Boolean,
    next: (ActionContext, KtClassOrObject) -> ModCommand,
  ) =
    ContributionPickerStep(
      owner,
      expectedText,
      title,
      hasRemainingChoices,
      next,
    )
}
