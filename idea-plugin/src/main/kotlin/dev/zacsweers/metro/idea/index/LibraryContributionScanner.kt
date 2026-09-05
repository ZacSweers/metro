// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import dev.zacsweers.metro.compiler.MetroHints
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.idea.annotationScopeKeys
import dev.zacsweers.metro.idea.classLiteralClassId
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.index.graph.GraphMemberExtractor
import dev.zacsweers.metro.idea.index.graph.graphExtensionFactoryTarget
import dev.zacsweers.metro.idea.index.graph.graphReference
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.GraphReference
import dev.zacsweers.metro.idea.model.HintAvailability
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.createUseSiteVisibilityChecker
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScope
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.stubindex.KotlinTopLevelFunctionFqnNameIndex
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Reads binary contribution declarations before their graph members seed dependency lookup. */
internal class LibraryContributionScanner(
  private val project: Project,
  private val options: MetroOptions,
  private val graphs: List<KaGraphDeclaration>,
  private val sourceContributions: List<ContributionEntry>,
  private val consumers: List<ConsumerEntry>,
  private val onGraphReference: (GraphReference, KtElement) -> Unit = { _, _ -> },
  private val onDeclarationFile: (PsiFile, KtElement) -> Unit = { _, _ -> },
) {
  private val pointerManager = SmartPointerManager.getInstance(project)
  private val bindings = mutableListOf<KaBinding>()
  private val contributions = mutableListOf<ContributionEntry>()
  private val graphInterfaces = mutableListOf<GraphInterfaceSurface>()
  private val processedLibraryContributionScopes = HashMap<KtClassOrObject, MutableSet<ClassId>>()
  private val scannedScopes = hashSetOf<ClassId>()
  // Every scope batch uses the same source snapshot inside one read action.
  private val useSites by
    lazy(LazyThreadSafetyMode.NONE) {
      sourceUseSitesByModule(project, graphs, sourceContributions, consumers)
    }

  /** Returns only metadata added by this scope batch; prior scopes stay memoized. */
  fun scan(scopeIds: Set<ClassId>): LibraryContributions {
    val bindingStart = bindings.size
    val contributionStart = contributions.size
    val interfaceStart = graphInterfaces.size
    scanLibraryContributionHints(scopeIds)
    return LibraryContributions(
      bindings.drop(bindingStart),
      contributions.drop(contributionStart),
      graphInterfaces.drop(interfaceStart),
    )
  }

  /**
   * Discovers contributions from compiled dependencies the way the compiler does for classpath
   * merging (`ContributionHintFirGenerator` / `ContributedInterfaceSupertypeGenerator`): scanning
   * top-level hint functions in the `metro.hints` package, named after the scope class, whose
   * single parameter type is the contributing class.
   */
  private fun scanLibraryContributionHints(scopeIds: Set<ClassId>) {
    if (scopeIds.isEmpty()) return
    val fileIndex = ProjectFileIndex.getInstance(project)
    val allScope = GlobalSearchScope.allScope(project)
    val hints = mutableListOf<LibraryHint>()
    for (scopeId in scopeIds) {
      ProgressManager.checkCanceled()
      if (!scannedScopes.add(scopeId)) continue
      val hintFqName = MetroHints.hintCallableId(scopeId).asSingleFqName().asString()
      for (hintFunction in KotlinTopLevelFunctionFqnNameIndex[hintFqName, project, allScope]) {
        ProgressManager.checkCanceled()
        val virtualFile = hintFunction.containingFile.virtualFile ?: continue
        // Project-source contributions are already covered by the annotation sweeps; hints only
        // exist as generated declarations in binaries.
        if (fileIndex.isInContent(virtualFile)) continue
        hints += LibraryHint(scopeId, hintFunction)
      }
    }
    if (hints.isEmpty()) return

    val visibleModulesByHint = visibleModulesByHint(hints, useSites)
    for (hint in hints) {
      ProgressManager.checkCanceled()
      val visibleModules = visibleModulesByHint.getValue(hint.function)
      if (visibleModules.isEmpty()) continue
      val hintAvailability = if (hint.isNonPublic) HintAvailability(visibleModules) else null
      val context = useSites.getValue(visibleModules.first())
      processLibraryHint(hint.function, hint.scopeId, context, hintAvailability)
    }
  }

  private fun processLibraryHint(
    hintFunction: KtNamedFunction,
    scopeId: ClassId,
    context: KtElement,
    hintAvailability: HintAvailability?,
  ) {
    analyze(context) {
      val recordFile: (PsiFile) -> Unit = { onDeclarationFile(it, context) }
      val symbol = hintFunction.symbol as? KaNamedFunctionSymbol ?: return@analyze
      val contributedType =
        symbol.valueParameters.singleOrNull()?.returnType?.fullyExpandedType ?: return@analyze
      val classSymbol = (contributedType as? KaClassType)?.symbol as? KaNamedClassSymbol
      val ktClass = classSymbol?.psi as? KtClassOrObject ?: return@analyze
      val processedScopes = processedLibraryContributionScopes.getOrPut(ktClass) { mutableSetOf() }
      if (!processedScopes.add(scopeId)) return@analyze

      // Contribution-provider containers carry @Origin pointing back at the real contributing
      // class; prefer it for presentation and as the contribution anchor.
      val originClassId =
        classSymbol.annotations
          .firstOrNull { it.classId in options.originAnnotations }
          ?.arguments
          ?.firstOrNull { it.name.asString() == "value" }
          ?.let { classLiteralClassId(it.expression) }
      val originPsi = originClassId?.let { findClass(it)?.psi as? KtClassOrObject }
      val contributionAnchor = originPsi ?: ktClass

      val contributedClassId = originClassId ?: ktClass.getClassId()
      val classReplaces =
        classSymbol.annotations
          .filter { it.classId in options.allContributesAnnotations }
          .flatMapToSet { classListArgument(it, "replaces") }
      val originSymbol =
        if (originPsi != null && originPsi != ktClass) originPsi.symbol as? KaNamedClassSymbol
        else classSymbol
      val contributionReplaces =
        originSymbol
          ?.annotations
          ?.filter { it.classId in options.allContributesAnnotations }
          ?.flatMapToSet { classListArgument(it, "replaces") }
          .orEmpty() + classReplaces
      val childType =
        if (classSymbol.hasAnyAnnotation(options.graphExtensionFactoryAnnotations)) {
          graphExtensionFactoryTarget(contributedType, options, recordFile)
        } else {
          null
        }
      val childReference = childType?.graphReference()
      val contribution =
        ContributionEntry(
          pointerManager.createSmartPsiElementPointer(contributionAnchor),
          setOf(scopeId),
          contributedClassId,
          hintAvailability,
          kind = (originSymbol ?: classSymbol).contributionKind(options),
          replaces = contributionReplaces,
          graphExtension = childReference,
        )
      contributions += contribution
      if (childReference != null) onGraphReference(childReference, context)
      if (contribution.kind == ContributionEntry.Kind.GRAPH_INTERFACE) {
        val interfaceType = contributedType
        val graphMembers =
          GraphMemberExtractor(options, pointerManager, bindings, recordFile, { _, _ -> }, {})
        val surface = graphMembers.interfaceSurface(this, contribution, interfaceType)
        graphInterfaces += surface
        for (reference in surface.extensionCreations) onGraphReference(reference, context)
        return@analyze
      }
      val classBindings = ktClass.bindingData(this, options, recordFile)
      val originBindings =
        if (originPsi != null && originPsi != ktClass)
          originPsi.bindingData(this, options, recordFile)
        else emptyList()
      val mapContributionAnnotations =
        options.contributesIntoMapAnnotations + options.customContributesIntoSetAnnotations
      val priorityAnnotations = options.contributesBindingAnnotations + mapContributionAnnotations
      val scopedPriorityAnnotations =
        originSymbol
          ?.annotations
          ?.filter { it.classId in priorityAnnotations }
          ?.filter { scopeId in annotationScopeKeys(it) }
          .orEmpty()
      // Explicit generated @Binds members are authoritative when a binary origin has multiple
      // supertypes and its contribution annotation's bound-type argument cannot be recovered.
      // A single scope-matched priority still belongs to those aliases without class BindingData.
      val classContributions =
        (classBindings + originBindings).filter { contribution ->
          (contribution.kind == BindingData.Kind.ALIAS ||
            contribution.kind == BindingData.Kind.PROVIDED) && contribution.isClassContribution
        }
      for (data in classBindings) {
        bindings +=
          data.toKaBinding(
            ptr(ktClass),
            originClassId = data.originClassId ?: contributedClassId,
            replaces = data.replaces + classReplaces,
            contributionScopes = data.contributionScopes.ifEmpty { setOf(scopeId) },
            hintAvailability = hintAvailability,
          )
      }
      // Generated members hold the machine-readable binding declarations that annotation
      // arguments in binaries can't carry, like binding<T>() type args. Contribution-provider
      // containers hold @Provides members directly, and contributed classes hold nested
      // MetroContribution interfaces with @Binds members.
      val memberHolders = listOf(ktClass) + ktClass.declarations.filterIsInstance<KtClassOrObject>()
      for (holder in memberHolders) {
        ProgressManager.checkCanceled()
        for (member in holder.declarations.filterIsInstance<KtCallableDeclaration>()) {
          for (data in member.bindingData(this, options, recordFile)) {
            val matchingContribution = classContributions.firstOrNull { contribution ->
              contribution.key == data.key &&
                contribution.multibindingId == data.multibindingId &&
                contribution.mapKeyValue == data.mapKeyValue &&
                scopeId in contribution.contributionScopes
            }
            val fallbackPriority =
              scopedPriorityAnnotations
                .filter { annotation ->
                  val annotationClassId = annotation.classId
                  val isBindingAnnotation =
                    annotationClassId in options.contributesBindingAnnotations
                  when {
                    data.multibindingId == null ->
                      isBindingAnnotation && !annotation.isMultibindingContribution()
                    data.mapKeyValue != null -> annotationClassId in mapContributionAnnotations
                    else -> false
                  }
                }
                .map { it.priority() }
                .singleOrNull()
            val inheritedPriority =
              when {
                matchingContribution != null ->
                  ExtractedPriority(
                    matchingContribution.priority,
                    matchingContribution.priorityFromAnvilRank,
                  )
                fallbackPriority != null -> fallbackPriority
                else ->
                  ExtractedPriority(
                    data.priority,
                    data.priorityFromAnvilRank,
                  )
              }
            val isMatchedClassContribution =
              matchingContribution != null || fallbackPriority != null
            bindings +=
              data.toKaBinding(
                ptr(member),
                originClassId = contributedClassId,
                implementationName =
                  data.implementationName ?: originClassId?.shortClassName?.asString(),
                replaces = classReplaces,
                contributionScopes = setOf(scopeId),
                priority = inheritedPriority.value,
                priorityFromAnvilRank = inheritedPriority.fromAnvilRank,
                isClassContribution = isMatchedClassContribution || data.isClassContribution,
                hintAvailability = hintAvailability,
              )
          }
        }
      }
    }
  }

  /**
   * Modules from which Kotlin considers each [LibraryHint] visible.
   *
   * Public hints need only one module whose classpath contains the declaration. Internal/private
   * hints retain their complete use-site visibility sets so friend and source-set rules remain
   * authoritative, but unrelated module/hint pairs never enter an Analysis API session.
   */
  @OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
  private fun visibleModulesByHint(
    hints: List<LibraryHint>,
    useSites: Map<KaModule, KtElement>,
  ): Map<KtNamedFunction, Set<KaModule>> {
    val result = hints.associateTo(linkedMapOf()) { it.function to linkedSetOf<KaModule>() }
    val pendingPublic = hints.filterTo(linkedSetOf()) { !it.isNonPublic }
    val nonPublic = hints.filter { it.isNonPublic }
    for ((module, useSite) in useSites) {
      ProgressManager.checkCanceled()
      val resolutionScope = KaResolutionScope.forModule(module)
      val publicIterator = pendingPublic.iterator()
      while (publicIterator.hasNext()) {
        ProgressManager.checkCanceled()
        val hint = publicIterator.next()
        if (!resolutionScope.contains(hint.function)) continue
        result.getValue(hint.function) += module
        publicIterator.remove()
      }

      val candidates = nonPublic.filter { resolutionScope.contains(it.function) }
      if (candidates.isEmpty()) continue
      analyze(useSite) {
        val checker =
          createUseSiteVisibilityChecker(
            useSiteFile = useSite.containingKtFile.symbol,
            receiverExpression = null,
            position = useSite,
          )
        for (hint in candidates) {
          ProgressManager.checkCanceled()
          val hintSymbol = hint.function.symbol as? KaNamedFunctionSymbol ?: continue
          if (checker.isVisible(hintSymbol)) {
            result.getValue(hint.function) += module
          }
        }
      }
    }
    return result
  }

  private fun ptr(element: KtElement): SmartPsiElementPointer<KtElement> {
    return pointerManager.createSmartPsiElementPointer(element)
  }

  private class LibraryHint(val scopeId: ClassId, val function: KtNamedFunction) {
    val isNonPublic: Boolean =
      function.hasModifier(KtTokens.INTERNAL_KEYWORD) ||
        function.hasModifier(KtTokens.PRIVATE_KEYWORD)
  }
}

/** Binary metadata captured without retaining Analysis API symbols or types. */
internal class LibraryContributions(
  val bindings: List<KaBinding>,
  val contributions: List<ContributionEntry>,
  val graphInterfaces: List<GraphInterfaceSurface>,
)

/** Keeps one source context per compilation module for classpath discovery and lookup. */
internal fun sourceUseSitesByModule(
  project: Project,
  graphs: List<KaGraphDeclaration>,
  contributions: List<ContributionEntry>,
  consumers: List<ConsumerEntry>,
): Map<KaModule, KtElement> {
  val result = linkedMapOf<KaModule, KtElement>()
  val fileIndex = ProjectFileIndex.getInstance(project)

  fun addUseSite(element: PsiElement?) {
    if (element !is KtElement) return
    val virtualFile = element.containingFile?.virtualFile ?: return
    if (!fileIndex.isInContent(virtualFile)) return
    val module = KaModuleProvider.getModule(project, element, useSiteModule = null)
    result.putIfAbsent(module, element)
  }

  graphs.forEach { addUseSite(it.pointer.element) }
  contributions.forEach { addUseSite(it.pointer.element) }
  consumers.forEach { addUseSite(it.pointer.element) }
  return result
}
