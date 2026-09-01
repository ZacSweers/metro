// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.codeInsight.daemon.GutterIconDescriptor
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.navigation.GotoRelatedItem
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.awt.RelativePoint
import dev.zacsweers.metro.idea.GraphContextPinService
import dev.zacsweers.metro.idea.MetroIcons
import dev.zacsweers.metro.idea.MetroNavigationService
import dev.zacsweers.metro.idea.MetroSettings
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ConsumerResolution
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaAnnotationValueSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.presentableName
import dev.zacsweers.metro.idea.toolwindow.ValidateMetroGraphAction
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.Icon
import kotlinx.coroutines.Job
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction

private val BINDING_OPTION =
  GutterIconDescriptor.Option("metro.provider", "Metro binding", MetroIcons.PROVIDER)
private val CONSUMER_OPTION =
  GutterIconDescriptor.Option("metro.consumer", "Metro consumer", MetroIcons.CONSUMER)
private val GRAPH_OPTION =
  GutterIconDescriptor.Option("metro.graph", "Metro graph contributions", MetroIcons.CONTRIBUTED)
private val VALIDATE_OPTION =
  GutterIconDescriptor.Option("metro.validate", "Metro graph validation", MetroIcons.GRAPH)

/**
 * Adds binding/consumer/graph gutter icons to Metro declarations, with navigation to the
 * counterpart binding sites. Each marker type can be toggled in Settings > Editor > General >
 * Gutter Icons.
 *
 * Classification is a [dev.zacsweers.metro.idea.model.BindingIndex] lookup by PSI identity.
 * Navigation targets remain smart pointers during marker collection. Clicking resolves and orders
 * them in a background read action.
 */
class MetroLineMarkerProvider : RelatedItemLineMarkerProvider() {

  override fun getName(): String = "Metro bindings"

  override fun getOptions(): Array<Option> =
    arrayOf(BINDING_OPTION, CONSUMER_OPTION, GRAPH_OPTION, VALIDATE_OPTION)

  override fun collectNavigationMarkers(
    element: PsiElement,
    result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
  ) {
    if (element !is LeafPsiElement || element.elementType != KtTokens.IDENTIFIER) return
    val declaration = element.parent as? KtNamedDeclaration ?: return
    if (declaration.nameIdentifier !== element) return
    if (!MetroSettings.getInstance(element.project).state.enableBindingResolution) return
    val bundle =
      element.project.service<MetroResolutionService>().presentationBundle(declaration) ?: return
    val presentation = bundle.declaration(declaration) ?: return

    if (GRAPH_OPTION.isEnabled || VALIDATE_OPTION.isEnabled) {
      if (declaration is KtClassOrObject) {
        presentation.graph?.let { graphPresentation ->
          val graph = graphPresentation.graph
          if (GRAPH_OPTION.isEnabled) {
            result += graphMarker(element, graphPresentation)
          }
          // Validation is addressed by ClassId, so graphs without one (local declarations)
          // get no validate marker
          val classId = graph.classId
          if (VALIDATE_OPTION.isEnabled && classId != null) {
            result += validateMarker(element, declaration, graphPresentation, classId)
          }
        }
      }
    }

    if (BINDING_OPTION.isEnabled) {
      val bindingEntries = presentation.bindingEntries
      if (bindingEntries.isNotEmpty()) {
        result += bindingMarker(element, bindingEntries, presentation.reverseUsage)
      }
    }

    if (CONSUMER_OPTION.isEnabled) {
      val consumerEntries = presentation.consumerEntries
      when {
        consumerEntries.size == 1 -> {
          val consumer = consumerEntries.single()
          if (consumer.graphRequestKind == ConsumerEntry.GraphRequestKind.MEMBERS_INJECTOR) {
            result += injectorMarker(element, declaration, consumerEntries, presentation)
          } else {
            result +=
              consumerMarker(
                element,
                consumer,
                presentation.consumerResolutions.getValue(consumer),
              )
          }
        }
        consumerEntries.size > 1 -> {
          // Injector members like `fun inject(target: Foo)` anchor one entry per injected key.
          val injectorEntries = consumerEntries.filter {
            it.graphRequestKind == ConsumerEntry.GraphRequestKind.MEMBERS_INJECTOR
          }
          if (injectorEntries.isNotEmpty()) {
            result += injectorMarker(element, declaration, injectorEntries, presentation)
          } else {
            result += specializedConsumerMarker(element, consumerEntries, presentation, bundle)
          }
        }
      }
    }
  }

  /** One inherited generic parameter remains an ordinary consumer across graph specializations. */
  private fun specializedConsumerMarker(
    anchor: PsiElement,
    consumers: List<ConsumerEntry>,
    presentation: FileDeclarationPresentation,
    bundle: FilePresentationBundle,
  ): RelatedItemLineMarkerInfo<*> {
    pinnedSpecializedConsumerMarker(anchor, consumers, presentation, bundle)?.let {
      return it
    }

    val contexts = linkedSetOf<String>()
    val bindings = linkedSetOf<KaBinding>()
    val firstContextKey = consumers.first().contextKey
    var firstResolution: Set<Any>? = null
    var requestedKeysDiffer = false
    var resolutionsDiffer = false
    var hasMissingRequiredContext = false
    for (consumer in consumers) {
      if (consumer.contextKey != firstContextKey) requestedKeysDiffer = true
      val resolution = presentation.consumerResolutions.getValue(consumer)
      contexts +=
        resolution.perContext.keys.map { context ->
          context.path.toString()
        }
      for (contextBindings in resolution.perContext.values) {
        bindings += contextBindings
        if (resolutionsDiffer) continue
        val identities = bundle.bindingResolutionIdentities(contextBindings)
        val previousResolution = firstResolution
        if (previousResolution == null) {
          firstResolution = identities
        } else if (identities != previousResolution) {
          resolutionsDiffer = true
        }
      }
      if (!consumer.isOptional && resolution.emptyContexts.isNotEmpty()) {
        hasMissingRequiredContext = true
      }
    }
    val renderedKeys = consumers.map { it.key.render(short = true) }.distinct()
    val distinctBindings = bundle.distinctBindingDeclarations(bindings)
    val bindingsDiffer = requestedKeysDiffer || resolutionsDiffer || hasMissingRequiredContext
    val tooltip = buildString {
      append("Metro dependency: ")
      append(renderedKeys.joinToString(" / "))
      append(if (bindingsDiffer) " · bindings differ across " else " · available in ")
      append(contexts.size)
      append(" graph contexts")
      if (distinctBindings.size > 1) {
        append(" · ")
        append(distinctBindings.size)
        append(" candidates")
      }
    }
    return navMarker(
      anchor = anchor,
      icon = if (hasMissingRequiredContext) MetroIcons.CONSUMER_UNRESOLVED else MetroIcons.CONSUMER,
      tooltip = tooltip,
      popupTitle = "Bindings across graph contexts",
      emptyText = "No Metro bindings found across graph contexts",
      targets = distinctBindings.map { it.pointer },
    )
  }

  private fun pinnedSpecializedConsumerMarker(
    anchor: PsiElement,
    consumers: List<ConsumerEntry>,
    presentation: FileDeclarationPresentation,
    bundle: FilePresentationBundle,
  ): RelatedItemLineMarkerInfo<*>? {
    val pinService = anchor.project.service<GraphContextPinService>()
    val resolutions = consumers.mapNotNull { consumer ->
      val entry =
        pinService.matchingEntry(presentation.consumerResolutions.getValue(consumer).perContext)
          ?: return@mapNotNull null
      PinnedConsumerResolution(consumer, entry.key, entry.value)
    }
    if (resolutions.isEmpty()) return null

    val context = resolutions.maxBy { it.context.path.segments.size }.context
    val renderedKeys = resolutions.map { it.consumer.key.render(short = true) }.distinct()
    val bindings = bundle.distinctBindingDeclarations(resolutions.flatMap { it.bindings })
    val missingRequired = resolutions.any { !it.consumer.isOptional && it.bindings.isEmpty() }
    val contributions = bindings.count { it.multibindingId != null }
    val tooltip = buildString {
      append("Metro dependency: ")
      append(renderedKeys.joinToString(" / "))
      when {
        missingRequired -> append(" · no binding found")
        contributions > 0 -> {
          append(" · ")
          append(contributions)
          append(if (contributions == 1) " contribution" else " contributions")
        }
        bindings.size > 1 -> {
          append(" · ")
          append(bindings.size)
          append(" bindings")
        }
        bindings.isEmpty() -> append(" · optional, uses its default")
        else -> {
          bindings.single().implementationName?.let {
            append(" · provided by ")
            append(it)
          }
        }
      }
      append(" in ")
      append(context.presentableName())
    }
    return navMarker(
      anchor = anchor,
      icon = if (missingRequired) MetroIcons.CONSUMER_UNRESOLVED else MetroIcons.CONSUMER,
      tooltip = tooltip,
      popupTitle =
        "Bindings for ${renderedKeys.joinToString(" / ")} in ${context.presentableName()}",
      emptyText = "No Metro bindings found in ${context.presentableName()}",
      targets = bindings.map { it.pointer },
    )
  }

  /** Injector members like `fun inject(target: Foo)` navigate to the target's injected members. */
  private fun injectorMarker(
    anchor: PsiElement,
    declaration: KtNamedDeclaration,
    entries: List<ConsumerEntry>,
    presentation: FileDeclarationPresentation,
  ): RelatedItemLineMarkerInfo<*> {
    val pinService = declaration.project.service<GraphContextPinService>()
    var pinnedContext: GraphContext? = null
    var missingInSomeContexts = 0
    var unresolved = 0
    for (entry in entries) {
      if (entry.isOptional) continue
      val resolution = presentation.consumerResolutions.getValue(entry)
      val pinned = pinService.matchingEntry(resolution.perContext)
      if (pinned != null) {
        pinnedContext = pinned.key
        if (pinned.value.isEmpty()) unresolved++
        continue
      }
      when {
        resolution.uniformBindings == null && resolution.emptyContexts.isNotEmpty() ->
          missingInSomeContexts++
        resolution.uniformBindings?.isEmpty() == true -> unresolved++
      }
    }
    val typeReference =
      (declaration as? KtNamedFunction)?.valueParameters?.singleOrNull()?.typeReference
    val targetName = typeReference?.text ?: "target"
    val targets = entries.mapNotNull { it.injectedMemberPointer }.distinct()
    val tooltip = buildString {
      append("Metro injector: injects ")
      append(entries.size)
      append(" dependencies into ")
      append(targetName)
      pinnedContext?.let {
        append(" in ")
        append(it.presentableName())
      }
      if (missingInSomeContexts > 0) {
        append(" · ")
        append(missingInSomeContexts)
        append(" missing in some graph contexts")
      }
      if (unresolved > 0) {
        append(" · ")
        append(unresolved)
        append(" unresolved")
      }
    }
    return navMarker(
      anchor = anchor,
      icon =
        if (missingInSomeContexts > 0 || unresolved > 0) {
          MetroIcons.CONSUMER_UNRESOLVED
        } else {
          MetroIcons.CONSUMER
        },
      tooltip = tooltip,
      popupTitle = "Injected members of $targetName",
      emptyText = "No injected members found in $targetName",
      targets = targets,
    )
  }

  private fun bindingMarker(
    anchor: PsiElement,
    entries: List<KaBinding>,
    reverseUsage: ReverseUsagePresentation?,
  ): RelatedItemLineMarkerInfo<*> {
    val graphPath = anchor.project.service<GraphContextPinService>().pinnedPath
    val targets = reverseUsage?.consumersFor(graphPath).orEmpty().map { it.pointer }
    val tooltip =
      entries.joinToString(separator = "\n") { entry ->
        buildString {
          append("Metro ")
          append(entry.label)
          append(": ")
          append(entry.typeKey.render(short = true))
          entry.scope?.let {
            append(" · scoped to ")
            append(scopeDisplay(it))
          }
        }
      }
    return navMarker(
      anchor = anchor,
      icon = MetroIcons.PROVIDER,
      tooltip = tooltip,
      popupTitle = "Consumers of ${entries.first().typeKey.render(short = true)}",
      emptyText = "No Metro consumers found",
      targets = targets,
    )
  }

  private fun consumerMarker(
    anchor: PsiElement,
    consumer: ConsumerEntry,
    resolution: ConsumerResolution,
  ): RelatedItemLineMarkerInfo<*> {
    val pinned =
      anchor.project.service<GraphContextPinService>().matchingEntry(resolution.perContext)
    val presentedBindings = pinned?.value ?: resolution.uniformBindings
    val isContextDependent = pinned == null && presentedBindings == null
    val bindings = presentedBindings.orEmpty()
    val navigationBindings = presentedBindings ?: resolution.candidateBindings
    val targets = navigationBindings.map { it.pointer }
    val contributions = bindings.count { it.multibindingId != null }
    val tooltip = buildString {
      append("Metro dependency: ")
      append(consumer.key.render(short = true))
      if (isContextDependent) {
        val emptyContextCount = resolution.emptyContexts.size
        if (emptyContextCount > 0) {
          append(" · binding found in ")
          append(resolution.perContext.size - emptyContextCount)
          append(" of ")
          append(resolution.perContext.size)
          append(" graph contexts")
        } else {
          append(" · bindings differ across ")
          append(resolution.perContext.size)
          append(" graph contexts")
        }
        if (resolution.candidateBindings.size > 1) {
          append(" · ")
          append(resolution.candidateBindings.size)
          append(" candidates")
        }
      } else {
        if (contributions > 0) {
          append(" · ")
          append(contributions)
          append(if (contributions == 1) " contribution" else " contributions")
        }
        bindings.singleOrNull()?.let { binding ->
          binding.implementationName
            // An implementation matching the declared type adds nothing
            ?.takeIf { it != consumer.key.render(short = true, includeQualifier = false) }
            ?.let {
              append(" · provided by ")
              append(it)
            }
          resolution.perContext.keys
            .singleOrNull()
            ?.graph
            ?.name
            ?.takeIf { pinned == null }
            ?.let {
              append(" in ")
              append(it)
            }
        }
        if (bindings.size > 1 && contributions == 0) {
          append(" · ")
          append(bindings.size)
          append(" bindings")
          if (resolution.perContext.size > 1) {
            append(" across ")
            append(resolution.perContext.size)
            append(" graph contexts")
          }
        }
        if (bindings.isEmpty()) {
          if (consumer.isOptional) {
            // Optional bindings use their default value when no binding is available.
            append(" · optional, uses its default")
          } else {
            append(" · no binding found in project sources (may be in a library or generated)")
          }
        }
      }
      pinned?.key?.let {
        append(" in ")
        append(it.presentableName())
      }
    }
    val missingRequiredContext =
      !consumer.isOptional &&
        if (pinned != null) pinned.value.isEmpty() else resolution.emptyContexts.isNotEmpty()
    val unresolvedEverywhere = !consumer.isOptional && presentedBindings?.isEmpty() == true
    val icon =
      if (missingRequiredContext || unresolvedEverywhere) {
        MetroIcons.CONSUMER_UNRESOLVED
      } else {
        MetroIcons.CONSUMER
      }
    return navMarker(
      anchor = anchor,
      icon = icon,
      tooltip = tooltip,
      popupTitle =
        when {
          isContextDependent ->
            "Bindings for ${consumer.key.render(short = true)} across graph contexts"
          pinned != null ->
            "Bindings for ${consumer.key.render(short = true)} in ${pinned.key.presentableName()}"
          contributions > 0 -> "Contributions to ${consumer.key.render(short = true)}"
          else -> "Bindings for ${consumer.key.render(short = true)}"
        },
      emptyText = "No Metro binding found for ${consumer.key.render(short = true)}",
      targets = targets,
    )
  }

  private fun graphMarker(
    anchor: PsiElement,
    presentation: GraphPresentation,
  ): RelatedItemLineMarkerInfo<*> {
    val graph = presentation.graph
    val allContexts = presentation.contexts.map { it.context }
    val pinned = anchor.project.service<GraphContextPinService>().matchingContext(allContexts)
    val contexts = pinned?.let(::listOf) ?: allContexts
    val selected = presentation.contexts.filter { it.context in contexts }
    val contributions = selected.flatMap { it.contributions }.distinct()
    val inherited = selected.flatMap { it.inheritedContributions }.distinct()
    val targets = (contributions + inherited).map { it.pointer }
    val scopesDisplay = graph.scopeKeys.joinToString { it.shortClassName.asString() }
    val tooltip = buildString {
      if (graph.scopeKeys.isEmpty()) {
        append("Metro graph contributions")
      } else {
        append("Contributions to ")
        append(scopesDisplay)
      }
      contexts.firstOrNull()?.chain?.getOrNull(1)?.let { parent ->
        append(" · extends ")
        append(parent.name ?: "parent graph")
      }
      pinned?.let {
        append(" · in ")
        append(it.presentableName())
      }
    }
    return GraphLineMarkerInfo(
      anchor = anchor,
      tooltip = tooltip,
      popupTitle =
        if (graph.scopeKeys.isEmpty()) "Contributions" else "Contributions to $scopesDisplay",
      targets = targets,
      graphClassId = graph.classId,
    )
  }

  /**
   * The graph icon on graph declarations, badged with the last validation outcome. Clicking
   * validates the graph in the tool window.
   */
  private fun validateMarker(
    anchor: PsiElement,
    declaration: KtNamedDeclaration,
    presentation: GraphPresentation,
    classId: ClassId,
  ): RelatedItemLineMarkerInfo<PsiElement> {
    val graph = presentation.graph
    val allContexts = presentation.contexts.map { it.context }
    val pinned = declaration.project.service<GraphContextPinService>().matchingContext(allContexts)
    val contexts = pinned?.let(::listOf) ?: allContexts
    val cached = contexts.mapNotNull { context ->
      declaration.project.service<MetroGraphValidationService>().cachedResult(declaration, context)
    }
    val internalErrorCount = cached.count { it.result is KaGraphValidationResult.InternalError }
    val incompleteResults = cached.mapNotNull { it.result as? KaGraphValidationResult.Incomplete }
    val problemCount = cached.sumOf {
      (it.result as? KaGraphValidationResult.Completed)?.diagnostics?.size ?: 0
    }
    val allContextsAttempted = cached.size == contexts.size
    val allContextsValidated =
      allContextsAttempted && cached.all { it.result is KaGraphValidationResult.Completed }
    val icon =
      when {
        internalErrorCount > 0 -> MetroIcons.GRAPH_PROBLEMS
        incompleteResults.isNotEmpty() -> MetroIcons.GRAPH_PROBLEMS
        problemCount > 0 -> MetroIcons.GRAPH_PROBLEMS
        allContextsValidated -> MetroIcons.GRAPH_VALIDATED
        else -> MetroIcons.GRAPH
      }
    val tooltip = buildString {
      append("Validate Metro graph")
      pinned?.let {
        append(" in ")
        append(it.presentableName())
      }
      if (cached.isNotEmpty()) {
        append(" · last run: ")
        val summaries = mutableListOf<String>()
        if (internalErrorCount > 0) {
          val noun =
            if (internalErrorCount == 1) "internal Metro plugin error"
            else "internal Metro plugin errors"
          summaries += "$internalErrorCount $noun"
        }
        if (incompleteResults.isNotEmpty()) {
          val reasons = incompleteResults.map { it.reason }.distinct().joinToString("; ")
          summaries += "analysis incomplete: $reasons"
        }
        if (problemCount > 0) {
          val noun = if (problemCount == 1) "problem" else "problems"
          summaries += "$problemCount $noun"
        }
        if (summaries.isEmpty()) summaries += "no problems found"
        append(summaries.joinToString(" · "))
        if (!allContextsAttempted) {
          append(" in ")
          append(cached.size)
          append(" of ")
          append(contexts.size)
          append(" contexts")
        }
        if (cached.any { it.stale }) append(" · code changed since")
      }
    }
    val file = declaration.containingFile?.virtualFile
    return RelatedItemLineMarkerInfo(
      anchor,
      anchor.textRange,
      icon,
      { tooltip },
      { _, element -> ValidateMetroGraphAction.openAndValidate(element.project, classId, file) },
      GutterIconRenderer.Alignment.LEFT,
      { emptyList<GotoRelatedItem>() },
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
    return MetroNavigationLineMarkerInfo(
      anchor,
      icon,
      tooltip,
      popupTitle,
      emptyText,
      targets,
    )
  }
}

private data class PinnedConsumerResolution(
  val consumer: ConsumerEntry,
  val context: GraphContext,
  val bindings: List<KaBinding>,
)

/** Resolves line marker targets only when navigation is requested. */
private class MetroNavigationLineMarkerInfo(
  anchor: PsiElement,
  icon: Icon,
  tooltip: String,
  popupTitle: String,
  emptyText: String,
  targets: List<SmartPsiElementPointer<out PsiElement>>,
) :
  RelatedItemLineMarkerInfo<PsiElement>(
    anchor,
    anchor.textRange,
    icon,
    { tooltip },
    { event, element ->
      showTargets(event, element.project, popupTitle, emptyText, targets)
    },
    GutterIconRenderer.Alignment.LEFT,
    { relatedItems(targets) },
  )

/**
 * The graph gutter marker. Clicking lists the graph's contributions. The right-click menu offers
 * graph actions such as validation.
 */
private class GraphLineMarkerInfo(
  anchor: PsiElement,
  tooltip: String,
  popupTitle: String,
  targets: List<SmartPsiElementPointer<out PsiElement>>,
  private val graphClassId: ClassId?,
) :
  RelatedItemLineMarkerInfo<PsiElement>(
    anchor,
    anchor.textRange,
    MetroIcons.CONTRIBUTED,
    { tooltip },
    { event, element ->
      showTargets(event, element.project, popupTitle, "No Metro contributions found", targets)
    },
    GutterIconRenderer.Alignment.RIGHT,
    { relatedItems(targets) },
  ) {

  override fun createGutterRenderer(): GutterIconRenderer {
    val file = element?.containingFile?.virtualFile
    return object : LineMarkerGutterIconRenderer<PsiElement>(this) {
      override fun getPopupMenuActions(): ActionGroup? {
        val classId = graphClassId ?: return null
        return DefaultActionGroup(
          object : AnAction("Validate Metro Graph", null, MetroIcons.GRAPH) {
            override fun actionPerformed(e: AnActionEvent) {
              e.project?.let { ValidateMetroGraphAction.openAndValidate(it, classId, file) }
            }
          }
        )
      }
    }
  }
}

/** Related-item factories resolve and order targets in the platform's background read action. */
private fun relatedItems(
  targets: List<SmartPsiElementPointer<out PsiElement>>
): List<GotoRelatedItem> {
  val elements = targets.mapNotNull { pointer ->
    ProgressManager.checkCanceled()
    pointer.element
  }
  return orderNavigationTargets(elements).map(::GotoRelatedItem)
}

/** `@SingleIn(AppScope::class)` reads as its scope argument; marker-only scopes as themselves. */
private fun scopeDisplay(scope: KaAnnotationSnapshot): String {
  val classArg =
    scope.arguments.firstNotNullOfOrNull { (_, value) ->
      (value as? KaAnnotationValueSnapshot.KClassRef)?.classId?.shortClassName?.asString()
    }
  return classArg ?: scope.classId.shortClassName.asString()
}

private fun showTargets(
  event: MouseEvent?,
  project: Project,
  title: String,
  emptyText: String,
  targets: List<SmartPsiElementPointer<out PsiElement>>,
) {
  val relativePoint = event?.let(::RelativePoint)
  val eventComponent = event?.component
  resolveLineMarkerTargets(event, project, targets) { elements ->
    if (eventComponent != null && !eventComponent.isShowing) return@resolveLineMarkerTargets
    when {
      elements.isEmpty() -> {
        val popup = JBPopupFactory.getInstance().createMessage(emptyText)
        if (relativePoint != null) popup.show(relativePoint) else popup.showInFocusCenter()
      }
      elements.size == 1 -> (elements.single() as? Navigatable)?.navigate(true)
      else -> {
        val popup =
          PsiTargetNavigator(elements.toTypedArray())
            .presentationProvider(MetroTargetRenderer())
            .createPopup(project, title)
        if (relativePoint != null) {
          popup.show(relativePoint)
        } else {
          popup.showInFocusCenter()
        }
      }
    }
  }
}

/** Groups clicks by editor so a newer click replaces pending navigation from that editor. */
internal fun resolveLineMarkerTargets(
  event: MouseEvent?,
  project: Project,
  targets: List<SmartPsiElementPointer<out PsiElement>>,
  onResolved: (List<PsiElement>) -> Unit,
): Job? {
  val eventComponent = event?.component
  val editor =
    eventComponent?.let(::editorForComponent)
      ?: FileEditorManager.getInstance(project).selectedTextEditor
  val navigation = project.service<MetroNavigationService>()
  return if (editor != null) {
    navigation.resolveTargets(editor, targets, ::orderNavigationTargets, onResolved)
  } else {
    navigation.resolveTargets(project, targets, ::orderNavigationTargets, onResolved)
  }
}

/** Finds the clicked gutter's editor, including editors outside the selected file tab. */
private fun editorForComponent(component: Component): Editor? {
  val dataContext = DataManager.getInstance().getDataContext(component)
  return CommonDataKeys.EDITOR.getData(dataContext)
}

private data class NavigationTargetOrder(
  val element: PsiElement,
  val sourceSetDepth: Int,
  val moduleName: String,
  val declarationName: String,
  val originalIndex: Int,
)

/** Orders resolved targets under read access, retaining their input order for equal sort keys. */
private fun orderNavigationTargets(targets: List<PsiElement>): List<PsiElement> {
  if (targets.size < 2) return targets
  return targets
    .mapIndexed { index, element ->
      ProgressManager.checkCanceled()
      NavigationTargetOrder(
        element = element,
        sourceSetDepth = sourceSetDepth(element),
        moduleName = ModuleUtilCore.findModuleForPsiElement(element)?.name.orEmpty(),
        declarationName = (element as? KtNamedDeclaration)?.name.orEmpty(),
        originalIndex = index,
      )
    }
    .sortedWith(
      Comparator { left, right ->
        ProgressManager.checkCanceled()
        navigationTargetOrder.compare(left, right)
      }
    )
    .map(NavigationTargetOrder::element)
}

private val navigationTargetOrder: Comparator<NavigationTargetOrder> =
  compareBy(
    NavigationTargetOrder::sourceSetDepth,
    NavigationTargetOrder::moduleName,
    NavigationTargetOrder::declarationName,
    NavigationTargetOrder::originalIndex,
  )

/**
 * The element's position in the KMP source-set hierarchy: 0 for commonMain, increasing through
 * intermediate source sets (nativeMain) to leaf platforms (iosArm64Main), via `dependsOn` edges.
 */
private fun sourceSetDepth(element: PsiElement): Int {
  val module =
    KaModuleProvider.getModule(element.project, element, useSiteModule = null) as? KaSourceModule
      ?: return 0
  return module.transitiveDependsOnDependencies.size
}

/**
 * Renders navigation popup rows as the declaration text plus its grayed container location, with
 * the owning module (KMP source set) right-aligned.
 */
internal class MetroTargetRenderer : PsiTargetPresentationRenderer<PsiElement>() {
  override fun getPresentation(element: PsiElement): TargetPresentation {
    val builder =
      TargetPresentation.builder(getElementText(element))
        .containerText(getContainerText(element))
        .icon(element.getIcon(0))
    val module = ModuleUtilCore.findModuleForPsiElement(element)
    return if (module != null) {
      builder.locationText(module.name, AllIcons.Nodes.Module).presentation()
    } else {
      builder.presentation()
    }
  }

  override fun getElementText(element: PsiElement): String {
    return when (element) {
      is KtNamedFunction -> {
        buildString {
          if (element.annotationEntries.any { it.shortName?.asString() == "Composable" }) {
            append("@Composable ")
          }
          append(element.name ?: element.text)
          append(if (element.valueParameters.isEmpty()) "()" else "(...)")
        }
      }
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
