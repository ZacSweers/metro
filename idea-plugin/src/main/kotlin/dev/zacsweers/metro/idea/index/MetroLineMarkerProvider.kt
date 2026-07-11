// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.codeInsight.daemon.GutterIconDescriptor
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.codeInsight.navigation.impl.PsiTargetPresentationRenderer
import com.intellij.icons.AllIcons
import com.intellij.navigation.GotoRelatedItem
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.awt.RelativePoint
import dev.zacsweers.metro.idea.MetroIcons
import dev.zacsweers.metro.idea.MetroSettings
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.metroIdeState
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaAnnotationValueSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphNode
import dev.zacsweers.metro.idea.toolwindow.ValidateMetroGraphAction
import java.awt.event.MouseEvent
import javax.swing.Icon
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

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
 * Navigation targets are captured as smart pointers at marker creation (background pass) so
 * clicking never triggers resolution on the EDT.
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
    if (!declaration.metroIdeState().options.enabled) return

    val index = element.project.service<MetroResolutionService>().index(declaration)

    if (GRAPH_OPTION.isEnabled || VALIDATE_OPTION.isEnabled) {
      (declaration as? KtClassOrObject)
        ?.let { index.graphEntryAt(it) }
        ?.let { graph ->
          if (GRAPH_OPTION.isEnabled) {
            result += graphMarker(element, graph, index)
          }
          // Validation is addressed by ClassId, so graphs without one (local declarations)
          // get no validate marker
          val classId = graph.classId
          if (VALIDATE_OPTION.isEnabled && classId != null) {
            result += validateMarker(element, declaration, graph, classId, index)
          }
        }
    }

    if (BINDING_OPTION.isEnabled) {
      val bindingEntries = index.bindingEntriesAt(declaration)
      if (bindingEntries.isNotEmpty()) {
        result += bindingMarker(element, bindingEntries, index)
      }
    }

    if (CONSUMER_OPTION.isEnabled) {
      val consumerEntries = index.consumerEntriesAt(declaration)
      when {
        // Injector members like `fun inject(target: Foo)` anchor one entry per injected key
        consumerEntries.size > 1 ->
          result += injectorMarker(element, declaration, consumerEntries, index)
        consumerEntries.size == 1 ->
          result += consumerMarker(element, consumerEntries.single(), index)
      }
    }
  }

  /** Injector members like `fun inject(target: Foo)` navigate to the target's injected members. */
  private fun injectorMarker(
    anchor: PsiElement,
    declaration: KtNamedDeclaration,
    entries: List<ConsumerEntry>,
    index: BindingIndex,
  ): RelatedItemLineMarkerInfo<*> {
    var missingInSomeContexts = 0
    var unresolved = 0
    for (entry in entries) {
      if (entry.isOptional) continue
      val resolution = index.resolveConsumer(entry)
      when {
        resolution.uniformBindings == null && resolution.emptyContexts.isNotEmpty() ->
          missingInSomeContexts++
        resolution.uniformBindings?.isEmpty() == true -> unresolved++
      }
    }
    val typeReference =
      (declaration as? KtNamedFunction)?.valueParameters?.singleOrNull()?.typeReference
    val targetName = typeReference?.text ?: "target"
    val targets = injectedMembersOf(typeReference)
    val tooltip = buildString {
      append("Metro injector: injects ")
      append(entries.size)
      append(" dependencies into ")
      append(targetName)
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

  /** The `@Inject`-annotated member declarations of the class behind [typeReference]. */
  private fun injectedMembersOf(
    typeReference: KtTypeReference?
  ): List<SmartPsiElementPointer<out PsiElement>> {
    val reference =
      (typeReference?.typeElement as? KtUserType)?.referenceExpression?.mainReference
        ?: return emptyList()
    // Platform flows (and tests) compute markers on the EDT; background passes are the norm
    val targetClass =
      allowAnalysisOnEdt { reference.resolve() } as? KtClassOrObject ?: return emptyList()
    val injectShortNames =
      targetClass.metroIdeState().options.allInjectAnnotations.mapTo(mutableSetOf()) {
        it.shortClassName.asString()
      }
    val pointerManager = SmartPointerManager.getInstance(targetClass.project)
    return targetClass.declarations
      .filter { member ->
        member !is KtClassOrObject &&
          member.annotationEntries.any { it.shortName?.asString() in injectShortNames }
      }
      .map { pointerManager.createSmartPsiElementPointer<PsiElement>(it) }
  }

  private fun bindingMarker(
    anchor: PsiElement,
    entries: List<KaBinding>,
    index: BindingIndex,
  ): RelatedItemLineMarkerInfo<*> {
    val targets = index.consumersFor(entries).map { it.pointer }
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
    index: BindingIndex,
  ): RelatedItemLineMarkerInfo<*> {
    val resolution = index.resolveConsumer(consumer)
    val uniformBindings = resolution.uniformBindings
    val isContextDependent = uniformBindings == null
    val bindings = uniformBindings.orEmpty()
    val navigationBindings = uniformBindings ?: resolution.candidateBindings
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
          resolution.perContext.keys.singleOrNull()?.graph?.name?.let {
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
            // An absent optional binding is by design, not a missing-binding error.
            append(" · optional, uses its default")
          } else {
            append(" · no binding found in project sources (may be in a library or generated)")
          }
        }
      }
    }
    val missingRequiredContext = !consumer.isOptional && resolution.emptyContexts.isNotEmpty()
    val unresolvedEverywhere = !consumer.isOptional && uniformBindings?.isEmpty() == true
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
          contributions > 0 -> "Contributions to ${consumer.key.render(short = true)}"
          else -> "Bindings for ${consumer.key.render(short = true)}"
        },
      emptyText = "No Metro binding found for ${consumer.key.render(short = true)}",
      targets = targets,
    )
  }

  private fun graphMarker(
    anchor: PsiElement,
    graph: KaGraphNode,
    index: BindingIndex,
  ): RelatedItemLineMarkerInfo<*> {
    val contexts = index.contextsFor(graph)
    val queryContexts = contexts.mapNotNull(index::queryContext)
    val contributions = queryContexts.flatMap { index.contributionsFor(it) }.distinct()
    val inherited = queryContexts.flatMap { index.inheritedContributionsFor(it) }.distinct()
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
    graph: KaGraphNode,
    classId: ClassId,
    index: BindingIndex,
  ): RelatedItemLineMarkerInfo<PsiElement> {
    val contexts = index.contextsFor(graph)
    val cached = contexts.mapNotNull { context ->
      declaration.project.service<MetroGraphValidationService>().cachedResult(declaration, context)
    }
    val problemCount = cached.sumOf { it.result.diagnostics.size }
    val allContextsValidated = cached.size == contexts.size
    val icon =
      when {
        problemCount > 0 -> MetroIcons.GRAPH_PROBLEMS
        allContextsValidated -> MetroIcons.GRAPH_VALIDATED
        else -> MetroIcons.GRAPH
      }
    val tooltip = buildString {
      append("Validate Metro graph")
      if (cached.isNotEmpty()) {
        append(" · last run: ")
        when (problemCount) {
          0 -> append("no problems found")
          1 -> append("1 problem")
          else -> append("$problemCount problems")
        }
        if (!allContextsValidated) {
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
    return NavigationGutterIconBuilder.create(icon)
      .setTargets(
        NotNullLazyValue.lazy {
          // Cluster KMP source sets in hierarchy order: commonMain first, then intermediate
          // source sets like nativeMain, then leaf platforms, alphabetical within each.
          targets
            .mapNotNull { it.element }
            .sortedWith(
              compareBy(
                { sourceSetDepth(it) },
                { ModuleUtilCore.findModuleForPsiElement(it)?.name.orEmpty() },
                { (it as? KtNamedDeclaration)?.name.orEmpty() },
              )
            )
        }
      )
      .setTooltipText(tooltip)
      .setPopupTitle(popupTitle)
      .setEmptyPopupText(emptyText)
      .setTargetRenderer { MetroTargetRenderer() }
      .createLineMarkerInfo(anchor)
  }
}

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
    { event, element -> showTargets(event, element.project, popupTitle, targets) },
    GutterIconRenderer.Alignment.RIGHT,
    { targets.mapNotNull { it.element }.map(::GotoRelatedItem) },
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
  targets: List<SmartPsiElementPointer<out PsiElement>>,
) {
  val elements =
    targets
      .mapNotNull { it.element }
      .sortedWith(
        compareBy(
          { sourceSetDepth(it) },
          { ModuleUtilCore.findModuleForPsiElement(it)?.name.orEmpty() },
          { (it as? KtNamedDeclaration)?.name.orEmpty() },
        )
      )
  when {
    elements.isEmpty() -> {}
    elements.size == 1 -> (elements.single() as? Navigatable)?.navigate(true)
    else -> {
      val popup =
        PsiTargetNavigator(elements.toTypedArray())
          .presentationProvider(MetroTargetRenderer())
          .createPopup(project, title)
      if (event != null) {
        popup.show(RelativePoint(event))
      } else {
        popup.showInFocusCenter()
      }
    }
  }
}

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
