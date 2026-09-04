// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.intentions.contributions

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.Presentation
import com.intellij.modcommand.PsiBasedModCommandAction
import org.jetbrains.kotlin.psi.KtClassOrObject

/** Unresolved pickers describe the flow without evaluating their first available choice. */
internal fun contributionPickerPreview(): IntentionPreviewInfo =
  IntentionPreviewInfo.Html(
    "<p>Choose a contribution kind and scope. Metro also asks for a bound type or map key when required. " +
      "The final choice previews the annotation change.</p>"
  )

/**
 * Keeps a chooser bound to its original source while final choices retain the native diff preview.
 */
internal class ContributionPickerStep(
  owner: KtClassOrObject,
  private val expectedText: String,
  private val title: String,
  private val hasRemainingChoices: Boolean,
  private val next: (ActionContext, KtClassOrObject) -> ModCommand,
) : PsiBasedModCommandAction<KtClassOrObject>(owner) {
  override fun getFamilyName(): String = title

  override fun getPresentation(context: ActionContext, element: KtClassOrObject): Presentation? {
    if (element.text != expectedText) return null
    return Presentation.of(title)
      .withHighlighting(element.nameIdentifier?.textRange ?: element.textRange)
  }

  override fun generatePreview(
    context: ActionContext,
    element: KtClassOrObject,
  ): IntentionPreviewInfo {
    if (element.text != expectedText) return IntentionPreviewInfo.EMPTY
    if (hasRemainingChoices) return contributionPickerPreview()
    return super.generatePreview(context, element)
  }

  override fun perform(context: ActionContext, element: KtClassOrObject): ModCommand {
    if (element.text != expectedText || contributionCandidate(element) == null)
      return ModCommand.nop()
    return next(context, element)
  }
}
