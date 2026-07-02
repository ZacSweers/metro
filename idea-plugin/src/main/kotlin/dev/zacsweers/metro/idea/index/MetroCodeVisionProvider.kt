// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.settings.CodeVisionGroupSettingProvider
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.idea.MetroSettings
import dev.zacsweers.metro.idea.metroIdeState
import dev.zacsweers.metro.idea.model.BindingIndex
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

internal const val METRO_CODE_VISION_GROUP_ID = "metro"

/**
 * Code vision headers above Metro declarations: consumer counts above bindings and contribution
 * counts above graphs. Clicking opens the same grouped navigation popup as the gutter icons.
 */
class MetroCodeVisionProvider : DaemonBoundCodeVisionProvider {

  override val id: String = "metro.bindings"
  override val name: String = "Metro bindings"
  override val groupId: String = METRO_CODE_VISION_GROUP_ID
  override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top
  override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()

  override fun computeForEditor(
    editor: Editor,
    file: PsiFile,
  ): List<Pair<TextRange, CodeVisionEntry>> {
    val ktFile = file as? KtFile ?: return emptyList()
    if (!MetroSettings.getInstance(ktFile.project).state.enableBindingResolution) {
      return emptyList()
    }
    if (!ktFile.metroIdeState().options.enabled) return emptyList()
    val index = ktFile.project.service<MetroResolutionService>().index(ktFile)

    val entries = mutableListOf<Pair<TextRange, CodeVisionEntry>>()
    ktFile.accept(
      object : KtTreeVisitorVoid() {
        override fun visitDeclaration(dcl: KtDeclaration) {
          super.visitDeclaration(dcl)
          // Parameters are too fine-grained for headers; they keep their gutter icons only.
          if (dcl !is KtNamedDeclaration || dcl is KtParameter) return
          collectFor(dcl, index, entries)
        }
      }
    )
    return entries
  }

  private fun collectFor(
    declaration: KtNamedDeclaration,
    index: BindingIndex,
    entries: MutableList<Pair<TextRange, CodeVisionEntry>>,
  ) {
    val bindingEntries = index.bindingEntriesAt(declaration)
    if (bindingEntries.isNotEmpty()) {
      val consumers = index.consumersFor(bindingEntries)
      val key = bindingEntries.first().typeKey.render(short = true)
      entries +=
        declaration.textRange to
          entry(
            text = countText(consumers.size, "consumer"),
            tooltip = "Metro consumers of $key",
            targets = consumers.map { it.pointer },
            popupTitle = "Consumers of $key",
          )
    }

    val graph = (declaration as? KtClassOrObject)?.let { index.graphEntryAt(it) }
    if (graph != null) {
      val contexts = index.contextsFor(graph)
      val contributions = contexts.flatMap { index.contributionsFor(it) }.distinct()
      val inherited = contexts.flatMap { index.inheritedContributionsFor(it) }.distinct()
      val scopes = graph.scopeKeys.joinToString { it.shortClassName.asString() }
      val text = buildString {
        append(countText(contributions.size, "contribution"))
        if (inherited.isNotEmpty()) {
          append(" (+")
          append(inherited.size)
          append(" inherited)")
        }
      }
      entries +=
        declaration.textRange to
          entry(
            text = text,
            tooltip = "Metro contributions to $scopes",
            targets = (contributions + inherited).map { it.pointer },
            popupTitle = "Contributions to $scopes",
          )
    }
  }

  private fun countText(count: Int, noun: String): String {
    return if (count == 1) "1 $noun" else "$count ${noun}s"
  }

  private fun entry(
    text: String,
    tooltip: String,
    targets: List<SmartPsiElementPointer<out KtElement>>,
    popupTitle: String,
  ): CodeVisionEntry {
    return ClickableTextCodeVisionEntry(
      text = text,
      providerId = id,
      onClick = { _, editor -> navigate(editor, targets, popupTitle) },
      tooltip = tooltip,
    )
  }

  private fun navigate(
    editor: Editor,
    targets: List<SmartPsiElementPointer<out KtElement>>,
    popupTitle: String,
  ) {
    val elements = targets.mapNotNull { it.element }
    when {
      elements.isEmpty() -> {}
      elements.size == 1 -> (elements.single() as? Navigatable)?.navigate(true)
      else -> PsiTargetNavigator(elements.toTypedArray()).navigate(editor, popupTitle)
    }
  }

  override fun handleClick(editor: Editor, textRange: TextRange, entry: CodeVisionEntry) {
    (entry as? ClickableTextCodeVisionEntry)?.onClick?.invoke(null, editor)
  }
}

/** Groups Metro's code vision entries under a "Metro" section in Settings > Inlay Hints. */
class MetroCodeVisionGroupSettingProvider : CodeVisionGroupSettingProvider {
  override val groupId: String = METRO_CODE_VISION_GROUP_ID
  override val groupName: String = "Metro"
  override val description: String =
    "Shows consumer counts above Metro bindings and contribution counts above dependency graphs."
}
