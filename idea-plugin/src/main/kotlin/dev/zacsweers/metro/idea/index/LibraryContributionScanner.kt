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
import dev.zacsweers.metro.idea.index.snapshot.SnapshotReadExecutor
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.GraphDeclarationId
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
) {
  private val pointerManager = SmartPointerManager.getInstance(project)
  private val processedLibraryContributionScopes = hashSetOf<ContributionHintId>()
  private val scannedScopes = hashSetOf<ClassId>()

  /**
   * Each hint owns its visibility checks and extracted metadata. The caller merges successful reads
   * in index order, preserving the first visible declaration for each class and scope.
   */
  suspend fun scan(
    scopeIds: Set<ClassId>,
    executor: SnapshotReadExecutor,
  ): LibraryContributionScan {
    val scopes = scopeIds.filter { scannedScopes.add(it) }
    if (scopes.isEmpty()) {
      return LibraryContributionScan.EMPTY
    }
    val batch = executor.read { discoverHints(scopes) }
    val captured =
      executor.map(batch.hints, ::describeHint) { hint -> captureHint(hint, batch.useSites) }
    val bindings = mutableListOf<KaBinding>()
    val contributions = mutableListOf<ContributionEntry>()
    val graphInterfaces = mutableListOf<GraphInterfaceSurface>()
    val references = mutableListOf<LibraryGraphRequest>()
    val dependencies = SourceClassDependencies.Builder(pointerManager, batch.dependencies)
    for (hint in captured) {
      ProgressManager.checkCanceled()
      dependencies.include(hint.dependencies)
      val id = hint.id ?: continue
      if (!processedLibraryContributionScopes.add(id)) {
        continue
      }
      bindings += hint.metadata.bindings
      contributions += hint.metadata.contributions
      graphInterfaces += hint.metadata.graphInterfaces
      references += hint.references
    }
    return LibraryContributionScan(
      LibraryContributions(bindings, contributions, graphInterfaces),
      references,
      dependencies.build(),
    )
  }

  /**
   * Discovers contributions from compiled dependencies the way the compiler does for classpath
   * merging (`ContributionHintFirGenerator` / `ContributedInterfaceSupertypeGenerator`): scanning
   * top-level hint functions in the `metro.hints` package, named after the scope class, whose
   * single parameter type is the contributing class. Only pointers leave this discovery read.
   */
  private fun discoverHints(scopeIds: List<ClassId>): HintBatch {
    val fileIndex = ProjectFileIndex.getInstance(project)
    val allScope = GlobalSearchScope.allScope(project)
    val hints = mutableListOf<LibraryHint>()
    for (scopeId in scopeIds) {
      ProgressManager.checkCanceled()
      val hintFqName = MetroHints.hintCallableId(scopeId).asSingleFqName().asString()
      for (hintFunction in KotlinTopLevelFunctionFqnNameIndex[hintFqName, project, allScope]) {
        ProgressManager.checkCanceled()
        val virtualFile = hintFunction.containingFile.virtualFile ?: continue
        // Project-source contributions are already covered by the annotation sweeps; hints only
        // exist as generated declarations in binaries.
        if (fileIndex.isInContent(virtualFile)) {
          continue
        }
        hints +=
          LibraryHint(
            scopeId,
            pointerManager.createSmartPsiElementPointer(hintFunction),
            hintFunction.hasModifier(KtTokens.INTERNAL_KEYWORD) ||
              hintFunction.hasModifier(KtTokens.PRIVATE_KEYWORD),
          )
      }
    }
    val dependencies = SourceClassDependencies.Builder(pointerManager)
    val useSites =
      sourceUseSitesByModule(project, graphs, sourceContributions, consumers).map { (module, site)
        ->
        val file = site.containingFile
        dependencies.recordContext(file)
        HintUseSite(module, ptr(site))
      }
    // Retain these stamps even when no hints exist or a context disappears before its worker runs.
    return HintBatch(hints, useSites, dependencies.build())
  }

  private fun describeHint(hint: LibraryHint): IndexBuildFile {
    val function = hint.pointer.element
    val file = function?.containingFile?.virtualFile
    return IndexBuildFile(
      hint.scopeId.asSingleFqName().asString(),
      file?.presentableUrl ?: "Contribution hint",
    )
  }

  /** Resolves one hint's complete visibility and declaration within a single retryable read. */
  private fun captureHint(hint: LibraryHint, useSites: List<HintUseSite>): CapturedHint {
    val dependencies = SourceClassDependencies.Builder(pointerManager)
    // Visibility can reject a hint or fail to resolve its type. Those reads still depend on the
    // source contexts from which the binary declaration was examined.
    for (site in useSites) {
      val file = site.context.element?.containingFile ?: continue
      dependencies.recordContext(file)
    }
    fun empty(): CapturedHint =
      CapturedHint(
        null,
        LibraryContributions(emptyList(), emptyList(), emptyList()),
        emptyList(),
        dependencies.build(),
      )
    val function = hint.pointer.element ?: return empty()
    val visibleSites = visibleUseSites(hint, function, useSites)
    val context = visibleSites.firstOrNull()?.context?.element ?: return empty()
    val availability =
      if (hint.isNonPublic) {
        HintAvailability(visibleSites.mapTo(linkedSetOf()) { it.module })
      } else {
        null
      }
    return processLibraryHint(function, hint.scopeId, context, availability, dependencies)
      ?: empty()
  }

  private fun processLibraryHint(
    hintFunction: KtNamedFunction,
    scopeId: ClassId,
    context: KtElement,
    hintAvailability: HintAvailability?,
    dependencies: SourceClassDependencies.Builder,
  ): CapturedHint? =
    analyze(context) {
      val bindings = mutableListOf<KaBinding>()
      val contributions = mutableListOf<ContributionEntry>()
      val graphInterfaces = mutableListOf<GraphInterfaceSurface>()
      val references = mutableListOf<LibraryGraphRequest>()
      val fileIndex = ProjectFileIndex.getInstance(project)
      val recordFile: (PsiFile) -> Unit = { file ->
        val virtualFile = file.virtualFile
        if (virtualFile != null && fileIndex.isInContent(virtualFile)) {
          dependencies.record(file, context.containingFile?.virtualFile)
        }
      }
      val recordReference: (GraphReference) -> Unit = { reference ->
        references += LibraryGraphRequest.capture(project, pointerManager, reference, context)
      }
      val symbol = hintFunction.symbol as? KaNamedFunctionSymbol ?: return@analyze null
      val contributedType =
        symbol.valueParameters.singleOrNull()?.returnType?.fullyExpandedType ?: return@analyze null
      val classSymbol = (contributedType as? KaClassType)?.symbol as? KaNamedClassSymbol
      val ktClass = classSymbol?.psi as? KtClassOrObject ?: return@analyze null
      recordFile(ktClass.containingFile)
      val id =
        ContributionHintId(
          GraphDeclarationId(classSymbol.classId, ktClass.containingFile.virtualFile),
          scopeId,
        )
      fun captured(): CapturedHint =
        CapturedHint(
          id,
          LibraryContributions(bindings, contributions, graphInterfaces),
          references,
          dependencies.build(),
        )

      // Contribution-provider containers carry @Origin pointing back at the real contributing
      // class; prefer it for presentation and as the contribution anchor.
      val originClassId =
        classSymbol.annotations
          .firstOrNull { it.classId in options.originAnnotations }
          ?.arguments
          ?.firstOrNull { it.name.asString() == "value" }
          ?.let { classLiteralClassId(it.expression) }
      val originPsi = originClassId?.let { findClass(it)?.psi as? KtClassOrObject }
      originPsi?.containingFile?.let(recordFile)
      val contributionAnchor = originPsi ?: ktClass

      val contributedClassId = originClassId ?: ktClass.getClassId()
      val classReplaces =
        classSymbol.annotations
          .filter { it.classId in options.allContributesAnnotations }
          .flatMapToSet { classListArgument(it, "replaces") }
      val originSymbol =
        if (originPsi != null && originPsi != ktClass) {
          originPsi.symbol as? KaNamedClassSymbol
        } else {
          classSymbol
        }
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
      if (childReference != null) {
        recordReference(childReference)
      }
      if (contribution.kind == ContributionEntry.Kind.GRAPH_INTERFACE) {
        val interfaceType = contributedType
        val graphMembers =
          GraphMemberExtractor(options, pointerManager, bindings, recordFile, { _, _ -> }, {})
        val surface = graphMembers.interfaceSurface(this, contribution, interfaceType)
        graphInterfaces += surface
        for (reference in surface.extensionCreations) {
          recordReference(reference)
        }
        return@analyze captured()
      }
      val classBindings = ktClass.bindingData(this, options, recordFile)
      val originBindings =
        if (originPsi != null && originPsi != ktClass) {
          originPsi.bindingData(this, options, recordFile)
        } else {
          emptyList()
        }
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
      captured()
    }

  /**
   * Public hints stop at the first module containing the declaration. Internal/private hints retain
   * every visible use site so friend and source-set rules remain authoritative. Classpath filtering
   * keeps unrelated module/hint pairs outside Analysis API sessions.
   */
  @OptIn(KaExperimentalApi::class, KaPlatformInterface::class)
  private fun visibleUseSites(
    hint: LibraryHint,
    function: KtNamedFunction,
    useSites: List<HintUseSite>,
  ): List<HintUseSite> {
    val result = mutableListOf<HintUseSite>()
    for (site in useSites) {
      ProgressManager.checkCanceled()
      val context = site.context.element ?: continue
      val resolutionScope = KaResolutionScope.forModule(site.module)
      if (!resolutionScope.contains(function)) {
        continue
      }
      if (!hint.isNonPublic) {
        return listOf(site)
      }
      val visible =
        analyze(context) {
          val checker =
            createUseSiteVisibilityChecker(
              useSiteFile = context.containingKtFile.symbol,
              receiverExpression = null,
              position = context,
            )
          val symbol = function.symbol as? KaNamedFunctionSymbol
          symbol != null && checker.isVisible(symbol)
        }
      if (visible) {
        result += site
      }
    }
    return result
  }

  private fun ptr(element: KtElement): SmartPsiElementPointer<KtElement> {
    return pointerManager.createSmartPsiElementPointer(element)
  }

  private class LibraryHint(
    val scopeId: ClassId,
    val pointer: SmartPsiElementPointer<KtNamedFunction>,
    val isNonPublic: Boolean,
  )

  private class HintUseSite(val module: KaModule, val context: SmartPsiElementPointer<KtElement>)

  private class HintBatch(
    val hints: List<LibraryHint>,
    val useSites: List<HintUseSite>,
    val dependencies: SourceClassDependencies,
  )

  private data class ContributionHintId(val declaration: GraphDeclarationId, val scopeId: ClassId)

  /** A successful read owns all of its output until ordered acceptance. */
  private class CapturedHint(
    val id: ContributionHintId?,
    val metadata: LibraryContributions,
    val references: List<LibraryGraphRequest>,
    val dependencies: SourceClassDependencies,
  )
}

/** Detached metadata and graph work discovered by one ordered hint batch. */
internal class LibraryContributionScan(
  val metadata: LibraryContributions,
  val references: List<LibraryGraphRequest>,
  val dependencies: SourceClassDependencies,
) {
  companion object {
    val EMPTY =
      LibraryContributionScan(
        LibraryContributions(emptyList(), emptyList(), emptyList()),
        emptyList(),
        SourceClassDependencies.EMPTY,
      )
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
    if (element !is KtElement) {
      return
    }
    val virtualFile = element.containingFile?.virtualFile ?: return
    if (!fileIndex.isInContent(virtualFile)) {
      return
    }
    val module = KaModuleProvider.getModule(project, element, useSiteModule = null)
    result.putIfAbsent(module, element)
  }

  graphs.forEach { addUseSite(it.pointer.element) }
  contributions.forEach { addUseSite(it.pointer.element) }
  consumers.forEach { addUseSite(it.pointer.element) }
  return result
}
