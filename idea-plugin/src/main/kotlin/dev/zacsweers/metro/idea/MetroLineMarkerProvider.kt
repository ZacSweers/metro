// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInsight.daemon.GutterIconDescriptor
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter

/**
 * Adds provider/consumer/graph gutter icons to Metro declarations, with navigation to the
 * counterpart binding sites. Each marker type can be toggled in Settings > Editor > General >
 * Gutter Icons.
 *
 * Classification is a [MetroBindingIndex] lookup by PSI identity. Navigation targets are captured
 * as smart pointers at marker creation (background pass) so clicking never triggers resolution on
 * the EDT.
 */
class MetroLineMarkerProvider : RelatedItemLineMarkerProvider() {

  override fun getName(): String = "Metro bindings"

  override fun getOptions(): Array<GutterIconDescriptor.Option> =
    arrayOf(PROVIDER_OPTION, CONSUMER_OPTION, GRAPH_OPTION)

  override fun collectNavigationMarkers(
    element: PsiElement,
    result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
  ) {
    if (element !is LeafPsiElement || element.elementType != KtTokens.IDENTIFIER) return
    val declaration = element.parent as? KtNamedDeclaration ?: return
    if (declaration.nameIdentifier !== element) return
    if (!MetroSettings.getInstance(element.project).state.enableBindingResolution) return
    if (!declaration.metroIdeState().options.enabled) return

    val index = MetroResolutionService.getInstance(element.project).index(declaration)

    if (GRAPH_OPTION.isEnabled) {
      (declaration as? KtClassOrObject)?.let { index.graphEntryAt(it) }?.let { graph ->
        result += graphMarker(element, graph, index)
      }
    }

    if (PROVIDER_OPTION.isEnabled) {
      val providerEntries = index.providerEntriesAt(declaration)
      if (providerEntries.isNotEmpty()) {
        result += providerMarker(element, providerEntries, index)
      }
    }

    if (CONSUMER_OPTION.isEnabled) {
      index.consumerEntryAt(declaration)?.let { consumer ->
        result += consumerMarker(element, consumer, index)
      }
    }

  }

  private fun providerMarker(
    anchor: PsiElement,
    entries: List<MetroProviderEntry>,
    index: MetroBindingIndex,
  ): RelatedItemLineMarkerInfo<*> {
    val targets = index.consumersFor(entries).map { it.pointer }
    val tooltip =
      entries.joinToString(separator = "\n") { entry ->
        buildString {
          append("Metro ")
          append(entry.kind.label)
          append(": ")
          append(entry.key.render(short = true))
          entry.scope?.let {
            append(" · scoped ")
            append(it.render(short = true))
          }
        }
      }
    return navMarker(
      anchor = anchor,
      icon = MetroIcons.PROVIDER,
      tooltip = tooltip,
      popupTitle = "Consumers of ${entries.first().key.render(short = true)}",
      emptyText = "No Metro consumers found",
      targets = targets,
    )
  }

  private fun consumerMarker(
    anchor: PsiElement,
    consumer: MetroConsumerEntry,
    index: MetroBindingIndex,
  ): RelatedItemLineMarkerInfo<*> {
    val resolution = index.resolveConsumer(consumer)
    val providers = resolution.effective
    val targets = providers.map { it.pointer }
    val tooltip = buildString {
      append("Metro dependency: ")
      append(consumer.key.render(short = true))
      providers.singleOrNull()?.let { provider ->
        provider.implementationName
          // An implementation matching the declared type adds nothing
          ?.takeIf { it != consumer.key.render(short = true, includeQualifier = false) }
          ?.let {
            append(" · resolved to ")
            append(it)
          }
        resolution.perGraph.keys.singleOrNull()?.name?.let {
          append(" · in ")
          append(it)
        }
      }
      if (providers.size > 1) {
        append(" · ")
        append(providers.size)
        append(" bindings")
        if (resolution.perGraph.size > 1) {
          append(" across ")
          append(resolution.perGraph.size)
          append(" graphs")
        }
      }
      if (providers.isEmpty()) {
        append(" · no binding found in project sources (may come from a library, generated code,")
        append(" or an instance binding)")
      }
    }
    return navMarker(
      anchor = anchor,
      icon = if (providers.isEmpty()) MetroIcons.CONSUMER_UNRESOLVED else MetroIcons.CONSUMER,
      tooltip = tooltip,
      popupTitle = "Bindings for ${consumer.key.render(short = true)}",
      emptyText = "No Metro binding found for ${consumer.key.render(short = true)}",
      targets = targets,
    )
  }

  private fun graphMarker(
    anchor: PsiElement,
    graph: MetroGraphEntry,
    index: MetroBindingIndex,
  ): RelatedItemLineMarkerInfo<*> {
    val targets = index.contributionsFor(index.contextFor(graph)).map { it.pointer }
    val scopesDisplay = graph.scopeKeys.joinToString { it.shortClassName.asString() }
    val tooltip = buildString {
      append("Metro dependency graph")
      if (graph.scopeKeys.isNotEmpty()) {
        append(" · aggregating ")
        append(scopesDisplay)
      }
    }
    return navMarker(
      anchor = anchor,
      icon = MetroIcons.GRAPH,
      tooltip = tooltip,
      popupTitle =
        if (graph.scopeKeys.isEmpty()) "Contributions" else "Contributions to $scopesDisplay",
      emptyText = "No contributions found",
      targets = targets,
    )
  }

  private fun navMarker(
    anchor: PsiElement,
    icon: Icon,
    tooltip: String,
    popupTitle: String,
    emptyText: String,
    targets: List<SmartPsiElementPointer<out PsiElement>>,
  ): RelatedItemLineMarkerInfo<*> {
    return NavigationGutterIconBuilder.create(icon)
      .setTargets(NotNullLazyValue.lazy { targets.mapNotNull { it.element } })
      .setTooltipText(tooltip)
      .setPopupTitle(popupTitle)
      .setEmptyPopupText(emptyText)
      .setTargetRenderer { MetroTargetRenderer() }
      .createLineMarkerInfo(anchor)
  }

  companion object {
    private val PROVIDER_OPTION =
      GutterIconDescriptor.Option("metro.provider", "Metro provider", MetroIcons.PROVIDER)
    private val CONSUMER_OPTION =
      GutterIconDescriptor.Option("metro.consumer", "Metro consumer", MetroIcons.CONSUMER)
    private val GRAPH_OPTION =
      GutterIconDescriptor.Option("metro.graph", "Metro graph", MetroIcons.GRAPH)
  }
}

/** Renders navigation popup rows as the declaration text plus its grayed container location. */
internal class MetroTargetRenderer : PsiTargetPresentationRenderer<PsiElement>() {
  override fun getElementText(element: PsiElement): String {
    return when (element) {
      is KtCallableDeclaration -> {
        val type = element.typeReference?.text
        if (type != null) "${element.name}: $type" else element.name ?: element.text
      }
      is KtNamedDeclaration -> element.name ?: element.text
      else -> element.text
    }
  }

  override fun getContainerText(element: PsiElement): String? {
    val owner =
      PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java)
        ?: PsiTreeUtil.getParentOfType(element.parent, KtClassOrObject::class.java)
    if (owner != null) {
      return owner.fqName?.asString() ?: owner.name
    }
    return element.containingFile?.name
  }
}
