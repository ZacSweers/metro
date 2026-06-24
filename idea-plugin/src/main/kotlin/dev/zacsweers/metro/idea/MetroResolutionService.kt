// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroHints
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.circuit.CircuitClassIds
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.graph.computeMultibindingId
import dev.zacsweers.metro.compiler.graph.createMapBindingId
import dev.zacsweers.metro.idea.model.AssistedSite
import dev.zacsweers.metro.idea.model.BindingContainerEntry
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingKind
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaAnnotationValueSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphNode
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import java.util.Collections
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker
import org.jetbrains.kotlin.idea.stubindex.KotlinAnnotationsIndex
import org.jetbrains.kotlin.idea.stubindex.KotlinTopLevelFunctionFqnNameIndex
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.types.Variance

/**
 * Shared resolution service powering Metro's line markers, code vision, and inlay hints.
 *
 * Builds a project-wide [dev.zacsweers.metro.idea.model.BindingIndex] from Kotlin stub indexes plus
 * the K2 Analysis API and caches it by Metro option fingerprint. Resolution is key-based across the
 * whole project, then filtered through graph contexts for membership-sensitive editor features.
 */
@Service(Service.Level.PROJECT)
class MetroResolutionService(private val project: Project) {

  companion object {
    fun getInstance(project: Project): MetroResolutionService = project.service()
  }

  // Project-wide indexes deduped by options fingerprint: projects typically share one Metro
  // config across modules, so caching per module would build and retain the same index once per
  // edited module. LRU-bounded so fingerprints orphaned by options changes don't retain their
  // last aggregate index forever.
  private val indexCaches: MutableMap<List<String>, CachedValue<BindingIndex>> =
    Collections.synchronizedMap(
      object : LinkedHashMap<List<String>, CachedValue<BindingIndex>>(8, 0.75f, true) {
        override fun removeEldestEntry(
          eldest: MutableMap.MutableEntry<List<String>, CachedValue<BindingIndex>>
        ): Boolean = size > 4
      }
    )

  /**
   * Returns the cached binding index for the module owning [context], or [BindingIndex.EMPTY] when
   * Metro is disabled or the element has no module.
   *
   * Must be called under a read action — building the index performs Analysis API resolution.
   * Normally that happens on background highlighting passes; EDT analysis is permitted for the
   * platform flows (and tests) that compute markers on the EDT.
   */
  internal fun index(context: PsiElement): BindingIndex {
    if (!MetroSettings.getInstance(project).state.enableBindingResolution) {
      return BindingIndex.EMPTY
    }
    val module = ModuleUtilCore.findModuleForPsiElement(context) ?: return BindingIndex.EMPTY
    val state = project.service<MetroIdeProjectService>().state(module)
    if (!state.options.enabled) return BindingIndex.EMPTY
    val options = state.options
    return allowAnalysisOnEdt {
      indexCaches
        .computeIfAbsent(state.optionsFingerprint) {
          CachedValuesManager.getManager(project).createCachedValue {
            // The aggregate invalidates on any PSI change (out-of-block trackers are
            // platform-internal Analysis API), but per-file shards only re-analyze the files that
            // actually changed; a rebuild is mostly cache hits plus a merge.
            CachedValueProvider.Result.create(
              buildProjectIndex(options),
              PsiModificationTracker.MODIFICATION_COUNT,
              KotlinCompilerSettingsTracker.getInstance(project),
              MetroSettings.getInstance(project).asModificationTracker(),
            )
          }
        }
        .value
    }
  }

  /**
   * Aggregates per-file shards (each cached against its own file's modification stamp) and runs the
   * cross-file passes on the merged result.
   */
  private fun buildProjectIndex(options: MetroOptions): BindingIndex {
    val bindings = mutableListOf<KaBinding>()
    val consumers = mutableListOf<ConsumerEntry>()
    val graphs = mutableListOf<KaGraphNode>()
    val contributions = mutableListOf<ContributionEntry>()
    val assistedSites = mutableListOf<AssistedSite>()
    val bindingContainers = mutableListOf<BindingContainerEntry>()
    for (file in candidateFiles(options)) {
      ProgressManager.checkCanceled()
      val shard = shardFor(file)
      bindings += shard.bindings
      consumers += shard.consumers
      graphs += shard.graphs
      contributions += shard.contributions
      assistedSites += shard.assistedSites
      bindingContainers += shard.bindingContainers
    }
    if (MetroSettings.getInstance(project).state.resolveFromLibraries) {
      IndexBuilder(project, options, bindings, consumers, graphs, contributions).postProcess()
    }
    return BindingIndex(
      bindings,
      consumers,
      graphs,
      contributions,
      assistedSites,
      bindingContainers,
    )
  }

  /** Files containing any Metro-relevant annotation by short name, via stub indexes. */
  private fun candidateFiles(options: MetroOptions): Set<KtFile> {
    val shortNames =
      projectSweepAnnotationIds(options).mapTo(sortedSetOf()) { it.shortClassName.asString() }
    val searchScope = GlobalSearchScope.projectScope(project)
    val files = LinkedHashSet<KtFile>()
    for (shortName in shortNames) {
      ProgressManager.checkCanceled()
      for (entry in KotlinAnnotationsIndex[shortName, project, searchScope]) {
        files += entry.containingKtFile
      }
    }
    return files
  }

  private fun projectSweepAnnotationIds(fallbackOptions: MetroOptions): Set<ClassId> {
    val ids = linkedSetOf<ClassId>()
    ids += sweepAnnotationIds(fallbackOptions)
    val service = project.service<MetroIdeProjectService>()
    for (module in ModuleManager.getInstance(project).modules) {
      val state = service.state(module)
      if (state.options.enabled) {
        ids += sweepAnnotationIds(state.options)
      }
    }
    return ids
  }

  private fun shardFor(file: KtFile): FileShard {
    return CachedValuesManager.getCachedValue(file) {
      // Shards use the owning file's module options, so files keep their own module's semantics
      // even when the requesting module's config differs.
      val state = file.metroIdeState()
      val shard =
        if (state.options.enabled) {
          IndexBuilder(file.project, state.options).buildShard(file)
        } else {
          FileShard.EMPTY
        }
      CachedValueProvider.Result.create(
        shard,
        file,
        KotlinCompilerSettingsTracker.getInstance(file.project),
      )
    }
  }
}

private fun sweepAnnotationIds(options: MetroOptions): Set<ClassId> {
  return buildSet {
    addAll(options.providesAnnotations)
    addAll(options.bindsAnnotations)
    addAll(options.multibindsAnnotations)
    addAll(options.injectAnnotations)
    addAll(options.assistedInjectAnnotations)
    addAll(options.allContributesAnnotations)
    addAll(options.dependencyGraphAnnotations)
    addAll(options.graphExtensionAnnotations)
    addAll(options.assistedFactoryAnnotations)
    addAll(options.bindingContainerAnnotations)
    add(CircuitClassIds.CircuitInject)
  }
}

/** Key plus display metadata for a consuming site. */
internal class ConsumedSite(
  val contextKey: KaContextualTypeKey,
  val isAbstractType: Boolean,
  /** For `Set`/`Map` aggregate sites, the multibinding id collecting contributed elements. */
  val multibindingId: String? = null,
  /** The consumed type's class, when it is a class type. */
  val typeClassId: ClassId? = null,
) {
  val key: KaTypeKey
    get() = contextKey.typeKey
}

/** Key plus display metadata for a single binding originated by a provider declaration. */
internal class BindingData(
  val key: KaTypeKey,
  val kind: BindingKind,
  val scope: KaAnnotationSnapshot?,
  val implementationName: String?,
  /** For `@Binds`-style bindings, the key of the source/impl binding this delegates to. */
  val consumedKey: KaTypeKey? = null,
  /** For multibinding contributions, the aggregate binding id. See [KaBinding]. */
  val multibindingId: String? = null,
  /** See [KaBinding.originClassId]. */
  val originClassId: ClassId? = null,
  /** See [KaBinding.replaces]. */
  val replaces: Set<ClassId> = emptySet(),
  /** See [KaBinding.contributionScopes]. */
  val contributionScopes: Set<ClassId> = emptySet(),
)

// ---------------------------------------------------------------------------------------------
// Index building
// ---------------------------------------------------------------------------------------------

/** The Metro declarations extracted from a single file, cached against that file's PSI. */
internal class FileShard(
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
  val graphs: List<KaGraphNode>,
  val contributions: List<ContributionEntry>,
  val assistedSites: List<AssistedSite>,
  val bindingContainers: List<BindingContainerEntry>,
) {
  companion object {
    val EMPTY =
      FileShard(
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
      )
  }
}

/**
 * Two-phase index construction: [buildShard] extracts one file's declarations (cached per file by
 * the resolution service), and [postProcess] runs the cross-file library passes over the merged
 * shard accumulators passed in via the constructor.
 */
private class IndexBuilder(
  private val project: Project,
  private val options: MetroOptions,
  private val bindings: MutableList<KaBinding> = mutableListOf(),
  private val consumers: MutableList<ConsumerEntry> = mutableListOf(),
  private val graphs: MutableList<KaGraphNode> = mutableListOf(),
  private val contributions: MutableList<ContributionEntry> = mutableListOf(),
  private val assistedSites: MutableList<AssistedSite> = mutableListOf(),
  private val bindingContainerEntries: MutableList<BindingContainerEntry> = mutableListOf(),
) {
  private val pointerManager = SmartPointerManager.getInstance(project)

  private val processedBindingCallables = HashSet<KtDeclaration>()
  private val processedInjectClasses = HashSet<KtClassOrObject>()
  private val processedMemberInjects = HashSet<KtDeclaration>()
  private val processedContributions = HashSet<KtClassOrObject>()
  private val processedGraphs = HashSet<KtClassOrObject>()
  private val processedCircuitInjects = HashSet<KtDeclaration>()
  private val processedAssistedFactories = HashSet<KtClassOrObject>()
  private val processedContainers = HashSet<KtClassOrObject>()

  fun buildShard(file: KtFile): FileShard {
    val bindingCallableNames =
      shortNames(
        options.providesAnnotations + options.bindsAnnotations + options.multibindsAnnotations
      )
    val injectNames = shortNames(options.injectAnnotations + options.assistedInjectAnnotations)
    val contributesNames = shortNames(options.allContributesAnnotations)
    val graphNames =
      shortNames(options.dependencyGraphAnnotations + options.graphExtensionAnnotations)
    val assistedFactoryNames = shortNames(options.assistedFactoryAnnotations)
    val containerNames = shortNames(options.bindingContainerAnnotations)
    val circuitName = CircuitClassIds.CircuitInject.shortClassName.asString()

    for (entry in PsiTreeUtil.collectElementsOfType(file, KtAnnotationEntry::class.java)) {
      ProgressManager.checkCanceled()
      val shortName = entry.shortName?.asString() ?: continue
      val declaration = entry.getStrictParentOfType<KtDeclaration>() ?: continue
      if (shortName in bindingCallableNames) processBindingCallable(declaration)
      if (shortName in injectNames) processInjectAnnotated(declaration)
      if (shortName in contributesNames) processContribution(declaration)
      if (shortName in graphNames) processGraph(declaration)
      if (shortName in assistedFactoryNames) processAssistedFactory(declaration)
      if (shortName in containerNames) processBindingContainer(declaration)
      if (options.enableCircuitCodegen && shortName == circuitName) {
        processCircuitInject(declaration)
      }
    }
    return FileShard(
      bindings,
      consumers,
      graphs,
      contributions,
      assistedSites,
      bindingContainerEntries,
    )
  }

  private fun shortNames(classIds: Set<ClassId>): Set<String> {
    return classIds.mapTo(mutableSetOf()) { it.shortClassName.asString() }
  }

  /** Runs the cross-file library passes over previously merged shard accumulators. */
  fun postProcess() {
    // Seed dedup with classes already contributed from project sources
    contributions.mapNotNullTo(processedContributions) { it.pointer.element as? KtClassOrObject }
    scanLibraryContributionHints()
    resolveLibraryInjectBindings()
  }

  /**
   * Discovers contributions from compiled dependencies the way the compiler does for classpath
   * merging (`ContributionHintFirGenerator` / `ContributedInterfaceSupertypeGenerator`): scanning
   * top-level hint functions in the `metro.hints` package, named after the scope class, whose
   * single parameter type is the contributing class.
   */
  private fun scanLibraryContributionHints() {
    val scopeIds = buildSet {
      graphs.forEach { addAll(it.scopeKeys) }
      contributions.forEach { addAll(it.scopeKeys) }
    }
    if (scopeIds.isEmpty()) return
    // Analyze from a project-source use site: library-use-site sessions deserialize annotation
    // values differently (e.g. unresolved class literal ids).
    val context =
      graphs.firstNotNullOfOrNull { it.pointer.element }
        ?: contributions.firstNotNullOfOrNull { it.pointer.element }
        ?: return
    val useSiteModulesByScope = useSiteModulesByScope()
    val fileIndex = ProjectFileIndex.getInstance(project)
    val allScope = GlobalSearchScope.allScope(project)
    for (scopeId in scopeIds) {
      ProgressManager.checkCanceled()
      val hintFqName = MetroHints.hintCallableId(scopeId).asSingleFqName().asString()
      for (hintFunction in KotlinTopLevelFunctionFqnNameIndex[hintFqName, project, allScope]) {
        val virtualFile = hintFunction.containingFile.virtualFile ?: continue
        // Project-source contributions are already covered by the annotation sweeps; hints only
        // exist as generated declarations in binaries.
        if (fileIndex.isInContent(virtualFile)) continue
        // Mirrors the compiler's visibility filtering: internal hints are visible only from their
        // own module and formal friend/associated compilations.
        if (
          hintFunction.hasModifier(KtTokens.INTERNAL_KEYWORD) &&
            !isVisibleInternalHint(hintFunction, useSiteModulesByScope[scopeId].orEmpty())
        ) {
          continue
        }
        processLibraryHint(hintFunction, scopeId, context)
      }
    }
  }

  private fun useSiteModulesByScope(): Map<ClassId, Set<KaModule>> {
    val result = mutableMapOf<ClassId, MutableSet<KaModule>>()

    fun addUseSite(element: PsiElement?, scopeKeys: Set<ClassId>) {
      if (element !is KtElement) return
      val module = KaModuleProvider.getModule(project, element, useSiteModule = null)
      for (scopeKey in scopeKeys) {
        result.getOrPut(scopeKey, ::mutableSetOf) += module
      }
    }

    graphs.forEach { addUseSite(it.pointer.element, it.scopeKeys) }
    contributions.forEach { addUseSite(it.pointer.element, it.scopeKeys) }
    return result
  }

  private fun processLibraryHint(
    hintFunction: KtNamedFunction,
    scopeId: ClassId,
    context: KtElement,
  ) {
    analyze(context) {
      val symbol = hintFunction.symbol as? KaNamedFunctionSymbol ?: return@analyze
      val contributedType =
        symbol.valueParameters.singleOrNull()?.returnType?.fullyExpandedType ?: return@analyze
      val classSymbol = (contributedType as? KaClassType)?.symbol as? KaNamedClassSymbol
      val ktClass = classSymbol?.psi as? KtClassOrObject ?: return@analyze
      if (!processedContributions.add(ktClass)) return@analyze

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
      contributions +=
        ContributionEntry(
          pointerManager.createSmartPsiElementPointer(contributionAnchor),
          setOf(scopeId),
          contributedClassId,
        )
      val classReplaces =
        classSymbol.annotations
          .filter { it.classId in options.allContributesAnnotations }
          .flatMapTo(hashSetOf()) { classListArgument(it, "replaces") }
      for (data in bindingData(ktClass, options)) {
        bindings +=
          KaBinding(
            ptr(ktClass),
            data.key,
            data.kind,
            data.scope,
            data.implementationName,
            data.multibindingId,
            originClassId = data.originClassId ?: contributedClassId,
            replaces = data.replaces + classReplaces,
            contributionScopes = data.contributionScopes.ifEmpty { setOf(scopeId) },
          )
      }
      // Generated members hold the machine-readable binding declarations that annotation
      // arguments in binaries can't carry (e.g. binding<T>() type args): contribution-provider
      // containers hold @Provides members directly, and contributed classes hold nested
      // MetroContribution interfaces with @Binds members.
      val memberHolders = listOf(ktClass) + ktClass.declarations.filterIsInstance<KtClassOrObject>()
      for (holder in memberHolders) {
        for (member in holder.declarations.filterIsInstance<KtCallableDeclaration>()) {
          for (data in bindingData(member, options)) {
            bindings +=
              KaBinding(
                ptr(member),
                data.key,
                data.kind,
                data.scope,
                data.implementationName ?: originClassId?.shortClassName?.asString(),
                data.multibindingId,
                originClassId = contributedClassId,
                replaces = classReplaces,
                contributionScopes = setOf(scopeId),
              )
          }
        }
      }
    }
  }

  /** Whether an internal [hintFunction] is visible from a formal friend/associated use site. */
  private fun isVisibleInternalHint(
    hintFunction: KtNamedFunction,
    useSiteModules: Set<KaModule>,
  ): Boolean {
    for (useSiteModule in useSiteModules) {
      val hintModule = KaModuleProvider.getModule(project, hintFunction, useSiteModule)
      if (hintModule == useSiteModule) return true
      if (useSiteModule is KaSourceModule && hintModule in useSiteModule.directFriendDependencies) {
        return true
      }
    }
    return false
  }

  /**
   * Demand-driven resolution of constructor-injected classes from compiled dependencies: the
   * annotation sweeps only cover project sources, but inject annotations survive in library
   * metadata. For each consumer key with no project-source binding, checks whether the consumed
   * class itself is injectable and synthesizes a binding entry targeting the library declaration.
   */
  private fun resolveLibraryInjectBindings() {
    val bindingKeys = bindings.mapTo(HashSet()) { it.key }
    val unresolved =
      consumers
        .filter {
          it.multibindingId == null &&
            it.typeClassId != null &&
            it.key.qualifier == null &&
            it.key !in bindingKeys
        }
        .groupBy { it.key }
    if (unresolved.isEmpty()) return

    val fileIndex = ProjectFileIndex.getInstance(project)
    for ((key, sites) in unresolved) {
      ProgressManager.checkCanceled()
      val context = sites.firstNotNullOfOrNull { it.pointer.element } ?: continue
      analyze(context) {
        val classSymbol =
          findClass(sites.first().typeClassId!!) as? KaNamedClassSymbol ?: return@analyze
        val psi = classSymbol.psi ?: return@analyze
        // Project sources were already swept; finding nothing there was authoritative
        val virtualFile = psi.containingFile?.virtualFile ?: return@analyze
        if (fileIndex.isInContent(virtualFile)) return@analyze
        if (classSymbol.classKind != KaClassKind.CLASS) return@analyze

        val constructors = classSymbol.memberScope.constructors.toList()
        val hasInject =
          classSymbol.hasAnyAnnotation(options.injectAnnotations) ||
            constructors.any { it.hasAnyAnnotation(options.injectAnnotations) }
        val isAssisted =
          classSymbol.hasAnyAnnotation(options.assistedInjectAnnotations) ||
            constructors.any { it.hasAnyAnnotation(options.assistedInjectAnnotations) }
        if (!hasInject || isAssisted) return@analyze

        bindings +=
          KaBinding(
            pointerManager.createSmartPsiElementPointer(psi),
            key,
            BindingKind.INJECT,
            scopeAnnotation(classSymbol, options),
            classSymbol.name.asString(),
          )
      }
    }
  }

  private fun ptr(element: KtElement): SmartPsiElementPointer<KtElement> {
    return pointerManager.createSmartPsiElementPointer(element)
  }

  /** `@Provides`/`@Binds`/`@Multibinds` callables, including instance-binding factory params. */
  private fun processBindingCallable(declaration: KtDeclaration) {
    val target =
      when (declaration) {
        is KtPropertyAccessor -> declaration.property
        else -> declaration
      }
    when (target) {
      is KtNamedFunction,
      is KtProperty,
      is KtParameter -> {
        if (!processedBindingCallables.add(target)) return
        val containerId =
          when (target) {
            // Instance bindings belong to the factory's owning graph
            is KtParameter -> {
              val factory = (target.ownerFunction as? KtNamedFunction)?.containingClassOrObject
              (factory?.containingClassOrObject ?: factory)?.getClassId()
            }
            is KtCallableDeclaration -> target.containingClassOrObject?.getClassId()
            else -> null
          }
        analyze(target) {
          for (data in bindingData(target, options)) {
            bindings +=
              KaBinding(
                ptr(target),
                data.key,
                data.kind,
                data.scope,
                data.implementationName,
                data.multibindingId,
                originClassId = data.originClassId,
                containerId = containerId,
                replaces = data.replaces,
                contributionScopes = data.contributionScopes,
              )
            // The @Binds source/impl side is itself a consumer of the impl binding.
            if (data.consumedKey != null) {
              val consumerAnchor =
                (target as? KtNamedFunction)?.valueParameters?.singleOrNull()
                  ?: (target as? KtCallableDeclaration)?.receiverTypeReference
                  ?: target
              consumers += ConsumerEntry(ptr(consumerAnchor), data.consumedKey)
            }
          }
          // Provider function parameters are consumers themselves.
          if (target is KtNamedFunction && !target.hasAnyOfAnnotations(options.bindsAnnotations)) {
            for (parameter in target.valueParameters) {
              addParameterConsumer(parameter)
            }
          }
        }
      }
      else -> {}
    }
  }

  /** `@Inject`/`@AssistedInject` on classes, constructors, and members. */
  private fun processInjectAnnotated(declaration: KtDeclaration) {
    when (declaration) {
      is KtConstructor<*> -> processInjectClass(declaration.getContainingClassOrObject())
      is KtClassOrObject -> processInjectClass(declaration)
      is KtProperty -> {
        // Member injection site
        if (declaration.isLocal || !processedMemberInjects.add(declaration)) return
        analyze(declaration) {
          val symbol = declaration.symbol as? KaCallableSymbol
          if (symbol != null && symbol.hasAnyAnnotation(options.allInjectAnnotations)) {
            addConsumer(declaration, symbol)
          }
        }
      }
      is KtNamedFunction -> {
        // Member/function injection: parameters are consumers
        if (declaration.isLocal || !processedMemberInjects.add(declaration)) return
        analyze(declaration) {
          val symbol = declaration.symbol as? KaNamedFunctionSymbol
          if (symbol != null && symbol.hasAnyAnnotation(options.allInjectAnnotations)) {
            for (parameter in declaration.valueParameters) {
              addParameterConsumer(parameter)
            }
          }
        }
      }
      else -> {}
    }
  }

  private fun processInjectClass(ktClass: KtClassOrObject) {
    if (!processedInjectClasses.add(ktClass)) return
    analyze(ktClass) {
      val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return@analyze
      // bindingData verifies injectability/contributions itself; classes without an explicit
      // primary constructor still provide their own type.
      for (data in bindingData(ktClass, options)) {
        bindings +=
          KaBinding(
            ptr(ktClass),
            data.key,
            data.kind,
            data.scope,
            data.implementationName,
            data.multibindingId,
            originClassId = data.originClassId,
            replaces = data.replaces,
            contributionScopes = data.contributionScopes,
          )
      }
      val injectConstructor = findInjectConstructor(ktClass, classSymbol)
      for (parameter in injectConstructor?.valueParameters.orEmpty()) {
        addParameterConsumer(parameter)
      }
    }
  }

  private fun processContribution(declaration: KtDeclaration) {
    val ktClass = declaration as? KtClassOrObject ?: return
    if (!processedContributions.add(ktClass)) return
    analyze(ktClass) {
      val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return@analyze
      val scopeKeys = scopeKeysFor(classSymbol, options.allContributesAnnotations) ?: return@analyze
      contributions +=
        ContributionEntry(
          pointerManager.createSmartPsiElementPointer(ktClass),
          scopeKeys,
          ktClass.getClassId(),
        )
    }
    // Binding-like contributions also originate bindings (and constructor consumers when
    // contributesAsInject treats them as injected).
    processInjectClass(ktClass)
  }

  private fun processGraph(declaration: KtDeclaration) {
    val ktClass = declaration as? KtClassOrObject ?: return
    if (!processedGraphs.add(ktClass)) return
    analyze(ktClass) {
      val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return@analyze
      val graphAnnotations =
        classSymbol.annotations.filter { it.classId in options.dependencyGraphAnnotations }
      val extensionAnnotations =
        classSymbol.annotations.filter { it.classId in options.graphExtensionAnnotations }
      val annotations = graphAnnotations + extensionAnnotations
      if (annotations.isEmpty()) return@analyze
      val scopeKeys = annotations.flatMapTo(mutableSetOf()) { annotationScopeKeys(it) }
      val excludes = annotations.flatMapTo(mutableSetOf()) { classListArgument(it, "excludes") }
      val containerIds =
        annotations.flatMapTo(mutableSetOf()) { classListArgument(it, "bindingContainers") }
      val graphClassId = ktClass.getClassId()
      val factoryAnnotations =
        options.dependencyGraphFactoryAnnotations + options.graphExtensionFactoryAnnotations
      val nestedClassIds = mutableSetOf<ClassId>()
      val includedDependencies = mutableSetOf<ClassId>()
      val accessorTypeIds = mutableSetOf<ClassId>()

      for (member in ktClass.declarations) {
        when (member) {
          is KtClassOrObject -> {
            val memberClassId = member.getClassId() ?: continue
            nestedClassIds += memberClassId
            // Factory @Includes params wire graph dependencies whose accessors join the graph
            val memberSymbol = member.symbol as? KaClassSymbol ?: continue
            if (!memberSymbol.hasAnyAnnotation(factoryAnnotations)) continue
            for (function in member.declarations.filterIsInstance<KtNamedFunction>()) {
              for (parameter in function.valueParameters) {
                val paramSymbol = parameter.symbol as? KaValueParameterSymbol ?: continue
                if (!paramSymbol.hasAnyAnnotation(setOf(MetroClassIds.includes))) continue
                val depType = paramSymbol.returnType.fullyExpandedType as? KaClassType ?: continue
                includedDependencies += depType.classId
                registerIncludedDependencyAccessors(depType.classId)
              }
            }
          }
          is KtCallableDeclaration -> {
            // Abstract accessor members are consumers of their return type.
            if (member is KtNamedFunction && member.valueParameters.isNotEmpty()) continue
            if (member !is KtNamedFunction && member !is KtProperty) continue
            if (member.receiverTypeReference != null) continue
            val symbol = member.symbol as? KaCallableSymbol ?: continue
            if (symbol.modality != KaSymbolModality.ABSTRACT) continue
            if (symbol.hasAnyAnnotation(nonAccessorCallableAnnotations(options))) continue
            if (symbol.returnType.isUnitType) continue
            // Accessors of a @GraphExtension (or its factory) are creation points of the child
            // graph, not dependencies on a binding; they only anchor the extension's parent chain.
            val returnClassType = symbol.returnType.fullyExpandedType as? KaClassType
            val returnClassSymbol = returnClassType?.symbol
            if (
              returnClassSymbol != null &&
                returnClassSymbol.hasAnyAnnotation(
                  options.graphExtensionAnnotations + options.graphExtensionFactoryAnnotations
                )
            ) {
              accessorTypeIds += returnClassType.classId
              continue
            }
            val site = consumedSite(symbol, options)
            consumers +=
              ConsumerEntry(
                ptr(member),
                site.key,
                site.isAbstractType,
                site.multibindingId,
                site.typeClassId,
              )
            site.typeClassId?.let(accessorTypeIds::add)
          }
          else -> {}
        }
      }

      // Each aggregation scope implicitly conveys @SingleIn(scope) on the graph, alongside any
      // explicitly declared scope annotations
      val scopingAnnotations = buildSet {
        scopeKeys.mapTo(this, ::implicitSingleInAnnotation)
        addAll(scopeAnnotations(classSymbol, options))
      }

      graphs +=
        KaGraphNode(
          pointerManager.createSmartPsiElementPointer(ktClass),
          scopeKeys,
          classId = graphClassId,
          excludes = excludes,
          bindingContainers = containerIds,
          includedDependencies = includedDependencies,
          isExtension = graphAnnotations.isEmpty(),
          selfIds = setOfNotNull(graphClassId) + nestedClassIds,
          accessorTypeIds = accessorTypeIds,
          scopingAnnotations = scopingAnnotations,
        )
    }
  }

  /** `@Includes` graph dependencies expose their accessors as bindings in the including graph. */
  private fun KaSession.registerIncludedDependencyAccessors(depClassId: ClassId) {
    val depSymbol = findClass(depClassId) as? KaNamedClassSymbol ?: return
    for (callable in depSymbol.declaredMemberScope.callables) {
      if (callable !is KaPropertySymbol && callable !is KaNamedFunctionSymbol) continue
      if (callable is KaNamedFunctionSymbol && callable.valueParameters.isNotEmpty()) continue
      if (callable.returnType.isUnitType) continue
      val psi = callable.psi as? KtElement ?: continue
      bindings +=
        KaBinding(
          ptr(psi),
          typeKey(callable.returnType, qualifierAnnotation(callable, options)),
          BindingKind.INCLUDED,
          null,
          null,
          containerId = depClassId,
        )
    }
  }

  /** `@AssistedFactory` declarations provide their own type, creating their SAM's return type. */
  private fun processAssistedFactory(declaration: KtDeclaration) {
    val ktClass = declaration as? KtClassOrObject ?: return
    if (!processedAssistedFactories.add(ktClass)) return
    analyze(ktClass) {
      val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return@analyze
      if (!classSymbol.hasAnyAnnotation(options.assistedFactoryAnnotations)) return@analyze
      val samFunction =
        classSymbol.declaredMemberScope.callables
          .filterIsInstance<KaNamedFunctionSymbol>()
          .firstOrNull { it.modality == KaSymbolModality.ABSTRACT }
      val createdName =
        (samFunction?.returnType?.fullyExpandedType as? KaClassType)
          ?.classId
          ?.shortClassName
          ?.asString()
      bindings +=
        KaBinding(
          ptr(ktClass),
          typeKey(classSymbol.defaultType, qualifierAnnotation(classSymbol, options)),
          BindingKind.ASSISTED_FACTORY,
          scopeAnnotation(classSymbol, options),
          createdName,
          originClassId = ktClass.getClassId(),
        )
    }
  }

  /** `@BindingContainer` classes and the containers they transitively include. */
  private fun processBindingContainer(declaration: KtDeclaration) {
    val ktClass = declaration as? KtClassOrObject ?: return
    if (!processedContainers.add(ktClass)) return
    val classId = ktClass.getClassId() ?: return
    analyze(ktClass) {
      val classSymbol = ktClass.symbol as? KaClassSymbol ?: return@analyze
      val containerAnnotation =
        classSymbol.annotations.firstOrNull { it.classId in options.bindingContainerAnnotations }
          ?: return@analyze
      bindingContainerEntries +=
        BindingContainerEntry(
          classId,
          classListArgument(containerAnnotation, "includes").toSet(),
        )
    }
  }

  /**
   * Scope class ids from [annotated]'s annotations matching [annotationClassIds], or null when none
   * of the annotations are present.
   */
  private fun scopeKeysFor(
    annotated: KaAnnotated,
    annotationClassIds: Set<ClassId>,
  ): Set<ClassId>? {
    val annotations = annotated.annotations.filter { it.classId in annotationClassIds }
    if (annotations.isEmpty()) return null
    return annotations.flatMapTo(mutableSetOf()) { annotationScopeKeys(it) }
  }

  /**
   * Mirrors the compiler's Metro-native Circuit codegen (`CircuitContributionExtension` and
   * `CircuitFirExtension`): a `@CircuitInject(screen, scope)` declaration generates a factory
   * contributed into `Set<Ui.Factory>`/`Set<Presenter.Factory>` at the scope, with the
   * declaration's non-circuit-provided parameters injected through it.
   */
  private fun processCircuitInject(declaration: KtDeclaration) {
    when (declaration) {
      is KtNamedFunction -> {
        if (!declaration.isTopLevel || !processedCircuitInjects.add(declaration)) return
        analyze(declaration) {
          val symbol = declaration.symbol as? KaNamedFunctionSymbol ?: return@analyze
          val annotation =
            symbol.annotations.firstOrNull { it.classId == CircuitClassIds.CircuitInject }
              ?: return@analyze

          // Presenters return CircuitUiState subtypes; UI functions are Unit-returning Composables
          val factoryClassId =
            when {
              symbol.returnType.isUnitType -> CircuitClassIds.UiFactory
              isCircuitProvidedType(symbol.returnType) -> CircuitClassIds.PresenterFactory
              else -> return@analyze
            }

          addCircuitContribution(declaration, annotation, factoryClassId)

          for (parameter in declaration.valueParameters) {
            addCircuitParameterConsumer(parameter)
          }
        }
      }
      is KtClassOrObject -> {
        if (!processedCircuitInjects.add(declaration)) return
        analyze(declaration) {
          val classSymbol = declaration.symbol as? KaNamedClassSymbol ?: return@analyze
          val annotation =
            classSymbol.annotations.firstOrNull { it.classId == CircuitClassIds.CircuitInject }
              ?: return@analyze
          val supertypeIds =
            classSymbol.defaultType.allSupertypes.mapNotNull { (it as? KaClassType)?.classId }
          val factoryClassId =
            when {
              CircuitClassIds.Ui in supertypeIds -> CircuitClassIds.UiFactory
              CircuitClassIds.Presenter in supertypeIds -> CircuitClassIds.PresenterFactory
              else -> return@analyze
            }
          addCircuitContribution(declaration, annotation, factoryClassId)
        }
        // Constructor dependencies are covered by the regular inject sweep when annotated
        processInjectClass(declaration)
      }
      else -> {}
    }
  }

  private fun KaSession.addCircuitContribution(
    declaration: KtDeclaration,
    annotation: KaAnnotation,
    factoryClassId: ClassId,
  ) {
    val scopes = annotationScopeKeys(annotation)
    contributions += ContributionEntry(ptr(declaration), scopes)
    val factoryType = (findClass(factoryClassId) as? KaNamedClassSymbol)?.defaultType ?: return
    val elementKey = typeKey(factoryType, null)
    bindings +=
      KaBinding(
        ptr(declaration),
        elementKey,
        BindingKind.MULTIBINDING_CONTRIBUTION,
        null,
        declaration.name,
        multibindingId = elementKey.computeMultibindingId(),
        contributionScopes = scopes,
      )
  }

  private fun KaSession.addCircuitParameterConsumer(parameter: KtParameter) {
    val symbol = parameter.symbol as? KaValueParameterSymbol ?: return
    if (symbol.hasAnyAnnotation(options.assistedAnnotations)) {
      assistedSites += AssistedSite(ptr(parameter), "@Assisted", isImplicit = false)
      return
    }
    if (isCircuitProvidedType(symbol.returnType)) {
      assistedSites += AssistedSite(ptr(parameter), "Circuit", isImplicit = true)
      return
    }
    addConsumer(parameter, symbol)
  }

  /**
   * Whether the type is supplied by Circuit at factory `create()` time rather than injected:
   * `Screen`/`CircuitUiState` subtypes, or exact `Navigator`/`Modifier`/`CircuitContext`.
   */
  private fun KaSession.isCircuitProvidedType(type: KaType): Boolean {
    val expanded = type.fullyExpandedType
    val classId = (expanded as? KaClassType)?.classId ?: return false
    when (classId) {
      CircuitClassIds.Navigator,
      CircuitClassIds.Modifier,
      CircuitClassIds.CircuitContext,
      CircuitClassIds.Screen,
      CircuitClassIds.CircuitUiState -> return true
      else -> {}
    }
    return expanded.allSupertypes.any { supertype ->
      val supertypeId = (supertype as? KaClassType)?.classId
      supertypeId == CircuitClassIds.Screen || supertypeId == CircuitClassIds.CircuitUiState
    }
  }

  private fun KaSession.findInjectConstructor(
    ktClass: KtClassOrObject,
    classSymbol: KaNamedClassSymbol,
  ): KtConstructor<*>? {
    val injectish = options.allInjectAnnotations
    val classLevel =
      classSymbol.hasAnyAnnotation(injectish) ||
        (options.contributesAsInject &&
          classSymbol.annotations.any { it.classId in bindingContributionAnnotations(options) })
    val constructors = listOfNotNull(ktClass.primaryConstructor) + ktClass.secondaryConstructors
    val annotatedConstructor = constructors.firstOrNull { ctor ->
      ctor.symbol.hasAnyAnnotation(injectish)
    }
    return annotatedConstructor ?: if (classLevel) ktClass.primaryConstructor else null
  }

  private fun KaSession.addParameterConsumer(parameter: KtParameter) {
    val symbol = parameter.symbol as? KaValueParameterSymbol ?: return
    if (symbol.hasAnyAnnotation(options.assistedAnnotations)) {
      assistedSites += AssistedSite(ptr(parameter), "@Assisted", isImplicit = false)
      return
    }
    if (symbol.hasAnyAnnotation(options.providesAnnotations)) return // instance binding param
    addConsumer(parameter, symbol)
  }

  private fun KaSession.addConsumer(element: KtElement, symbol: KaCallableSymbol) {
    val site = consumedSite(symbol, options)
    consumers +=
      ConsumerEntry(
        ptr(element),
        site.key,
        site.isAbstractType,
        site.multibindingId,
        site.typeClassId,
      )
  }

  private fun KtCallableDeclaration.hasAnyOfAnnotations(classIds: Set<ClassId>): Boolean {
    // Cheap PSI pre-check by short name only; used to avoid registering @Binds params as consumers
    // twice (the analysis-verified path already handles them).
    val shortNames = classIds.mapTo(mutableSetOf()) { it.shortClassName.asString() }
    return annotationEntries.any { it.shortName?.asString() in shortNames }
  }
}

// ---------------------------------------------------------------------------------------------
// Shared Analysis API helpers
// ---------------------------------------------------------------------------------------------

internal val SET_CLASS_ID = ClassId.fromString("kotlin/collections/Set")
internal val MAP_CLASS_ID = ClassId.fromString("kotlin/collections/Map")
private val COLLECTION_LIKE_CLASS_IDS =
  setOf(
    SET_CLASS_ID,
    ClassId.fromString("kotlin/collections/Collection"),
    ClassId.fromString("kotlin/collections/List"),
    ClassId.fromString("kotlin/collections/Iterable"),
  )

internal fun bindingContributionAnnotations(options: MetroOptions): Set<ClassId> {
  return buildSet {
    addAll(options.contributesBindingAnnotations)
    addAll(options.contributesIntoSetAnnotations)
    addAll(options.customContributesIntoSetAnnotations)
    addAll(options.contributesIntoMapAnnotations)
  }
}

private fun nonAccessorCallableAnnotations(options: MetroOptions): Set<ClassId> {
  return buildSet {
    addAll(options.bindsAnnotations)
    addAll(options.providesAnnotations)
    addAll(options.multibindsAnnotations)
  }
}

internal fun KaAnnotated.hasAnyAnnotation(classIds: Set<ClassId>): Boolean {
  return annotations.any { it.classId in classIds }
}

/** Renders a type as a stable, fully-qualified binding key string. */
internal fun KaSession.renderKeyType(type: KaType): String {
  return type.fullyExpandedType.render(
    KaTypeRendererForSource.WITH_QUALIFIED_NAMES,
    position = Variance.INVARIANT,
  )
}

/** Renders a type with short names for display purposes. */
internal fun KaSession.renderShortKeyType(type: KaType): String {
  return type.fullyExpandedType.render(
    KaTypeRendererForSource.WITH_SHORT_NAMES,
    position = Variance.INVARIANT,
  )
}

/** Builds a [KaTypeKey] for [type], capturing a restorable pointer and both renderings. */
internal fun KaSession.typeKey(type: KaType, qualifier: KaAnnotationSnapshot?): KaTypeKey {
  return KaTypeKey(typeSnapshot(type), qualifier)
}

/** Builds a session-free type snapshot for the current analysis session. */
internal fun KaSession.typeSnapshot(type: KaType): KaTypeSnapshot {
  val expanded = type.fullyExpandedType
  return KaTypeSnapshot(
    expanded.createPointer(),
    renderKeyType(expanded),
    renderShortKeyType(expanded),
    (expanded as? KaClassType)?.classId,
  )
}

/** Builds a contextual key that preserves provider/lazy/map wrapper structure. */
internal fun KaSession.contextualTypeKey(
  type: KaType,
  qualifier: KaAnnotationSnapshot?,
  options: MetroOptions,
): KaContextualTypeKey {
  val declaredType = type.fullyExpandedType
  val rawSnapshot = typeSnapshot(declaredType)
  val wrappedType = declaredType.asWrappedType(options)
  val keySnapshot =
    when (wrappedType) {
      is WrappedType.Canonical -> wrappedType.type
      is WrappedType.Map -> rawSnapshot
      else -> wrappedType.canonicalType()
    }
  return KaContextualTypeKey(
    typeKey = KaTypeKey(keySnapshot, qualifier),
    wrappedType = wrappedType,
    rawType = rawSnapshot,
  )
}

context(session: KaSession)
private fun KaType.asWrappedType(options: MetroOptions): WrappedType<KaTypeSnapshot> {
  val expanded = with(session) { fullyExpandedType }
  val rawSnapshot = session.typeSnapshot(expanded)
  val classType = expanded as? KaClassType ?: return WrappedType.Canonical(rawSnapshot)
  val classId = classType.classId ?: return WrappedType.Canonical(rawSnapshot)

  if (classId == MAP_CLASS_ID) {
    val keyType = classType.typeArguments.getOrNull(0)?.type
    val valueType = classType.typeArguments.getOrNull(1)?.type
    if (keyType != null && valueType != null) {
      return WrappedType.Map(session.typeSnapshot(keyType), valueType.asWrappedType(options)) {
        rawSnapshot
      }
    }
  }

  if (classId in options.providerTypes) {
    val innerType = classType.typeArguments.firstOrNull()?.type
    if (innerType != null) {
      return WrappedType.Provider(innerType.asWrappedType(options), classId)
    }
  }

  if (classId in options.lazyTypes) {
    val innerType = classType.typeArguments.firstOrNull()?.type
    if (innerType != null) {
      return WrappedType.Lazy(innerType.asWrappedType(options), classId)
    }
  }

  return WrappedType.Canonical(rawSnapshot)
}

internal fun KaSession.consumedSite(
  symbol: KaCallableSymbol,
  options: MetroOptions,
): ConsumedSite {
  val returnType = symbol.returnType.fullyExpandedType
  val qualifier = qualifierAnnotation(symbol, options)
  val contextKey = contextualTypeKey(returnType, qualifier, options)
  val classSymbol = contextKey.typeKey.type.classId?.let { findClass(it) } as? KaClassSymbol
  val isAbstract =
    classSymbol != null &&
      (classSymbol.classKind == KaClassKind.INTERFACE ||
        classSymbol.modality == KaSymbolModality.ABSTRACT)
  return ConsumedSite(
    contextKey,
    isAbstract,
    aggregateMultibindingId(returnType as? KaClassType, contextKey, options),
    contextKey.typeKey.type.classId,
  )
}

/** Computes the multibinding id collected by a `Set<E>`/`Map<K, V>` consumer site, if any. */
private fun KaSession.aggregateMultibindingId(
  classType: KaClassType?,
  contextKey: KaContextualTypeKey,
  options: MetroOptions,
): String? {
  if (classType == null) return null
  return when (classType.classId) {
    SET_CLASS_ID -> {
      val elementType = classType.typeArguments.firstOrNull()?.type ?: return null
      val elementKeyType = elementType.asWrappedType(options).canonicalType()
      contextKey.typeKey.copy(type = elementKeyType).computeMultibindingId()
    }
    MAP_CLASS_ID -> {
      val wrappedMap = contextKey.wrappedType as? WrappedType.Map ?: return null
      val valueType = wrappedMap.valueType.canonicalType()
      createMapBindingId(wrappedMap.keyType.renderedType, contextKey.typeKey.copy(type = valueType))
    }
    else -> null
  }
}

/**
 * Resolves the map key type of an `@IntoMap` contribution from its map key annotation, mirroring
 * the compiler's `mapKeyType`: the annotation's single member type when the `@MapKey`
 * meta-annotation has `unwrapValue = true` (the default), otherwise the annotation type itself.
 */
internal fun KaSession.mapKeyType(annotated: KaAnnotated, options: MetroOptions): String? {
  for (annotation in annotated.annotations) {
    val classId = annotation.classId ?: continue
    val annotationClass = findClass(classId) as? KaNamedClassSymbol ?: continue
    val mapKeyMeta =
      annotationClass.annotations.firstOrNull { it.classId in options.mapKeyAnnotations }
        ?: continue
    val unwrapValue =
      mapKeyMeta.arguments
        .firstOrNull { it.name.asString() == "unwrapValue" }
        ?.let { (it.expression as? KaAnnotationValue.ConstantValue)?.value?.value } != false
    val keyType =
      if (unwrapValue) {
        val constructor =
          annotationClass.memberScope.constructors.firstOrNull { it.isPrimary } ?: continue
        constructor.valueParameters.firstOrNull()?.returnType ?: continue
      } else {
        annotationClass.defaultType
      }
    return renderKeyType(keyType)
  }
  return null
}

/** Converts a resolved [KaAnnotation] to its session-free structured snapshot. */
internal fun KaAnnotation.toKaAnnotationSnapshot(): KaAnnotationSnapshot? {
  val classId = classId ?: return null
  return KaAnnotationSnapshot(classId, arguments.map { it.name to it.expression.toValueSnapshot() })
}

private fun KaAnnotationValue.toValueSnapshot(): KaAnnotationValueSnapshot {
  return when (this) {
    is KaAnnotationValue.ConstantValue -> KaAnnotationValueSnapshot.Literal(value.value)
    is KaAnnotationValue.EnumEntryValue -> KaAnnotationValueSnapshot.EnumEntry(callableId)
    // classId may be unpopulated for binary-deserialized values; the type still carries it
    is KaAnnotationValue.ClassLiteralValue ->
      KaAnnotationValueSnapshot.KClassRef(classId ?: (type as? KaClassType)?.classId)
    is KaAnnotationValue.ArrayValue ->
      KaAnnotationValueSnapshot.Array(values.map { it.toValueSnapshot() })
    is KaAnnotationValue.NestedAnnotationValue ->
      annotation.toKaAnnotationSnapshot()?.let { KaAnnotationValueSnapshot.Nested(it) }
        ?: KaAnnotationValueSnapshot.Unsupported
    else -> KaAnnotationValueSnapshot.Unsupported
  }
}

/** Finds the first annotation whose class is meta-annotated with any of [metaAnnotations]. */
private fun KaSession.findMetaAnnotated(
  annotated: KaAnnotated,
  metaAnnotations: Set<ClassId>,
): KaAnnotationSnapshot? = findAllMetaAnnotated(annotated, metaAnnotations).firstOrNull()

/** Finds all annotations whose classes are meta-annotated with any of [metaAnnotations]. */
private fun KaSession.findAllMetaAnnotated(
  annotated: KaAnnotated,
  metaAnnotations: Set<ClassId>,
): List<KaAnnotationSnapshot> {
  return annotated.annotations.mapNotNull { annotation ->
    val classId = annotation.classId ?: return@mapNotNull null
    val annotationClass = findClass(classId) ?: return@mapNotNull null
    if (annotationClass.annotations.any { it.classId in metaAnnotations }) {
      annotation.toKaAnnotationSnapshot()
    } else {
      null
    }
  }
}

/** Finds the first qualifier annotation (an annotation meta-annotated with `@Qualifier`). */
internal fun KaSession.qualifierAnnotation(
  annotated: KaAnnotated,
  options: MetroOptions,
): KaAnnotationSnapshot? = findMetaAnnotated(annotated, options.qualifierAnnotations)

/** Finds the first scope annotation (an annotation meta-annotated with `@Scope`). */
internal fun KaSession.scopeAnnotation(
  annotated: KaAnnotated,
  options: MetroOptions,
): KaAnnotationSnapshot? = findMetaAnnotated(annotated, options.scopeAnnotations)

/** Finds all scope annotations (annotations meta-annotated with `@Scope`). */
internal fun KaSession.scopeAnnotations(
  annotated: KaAnnotated,
  options: MetroOptions,
): List<KaAnnotationSnapshot> = findAllMetaAnnotated(annotated, options.scopeAnnotations)

/**
 * The `@SingleIn(scope)` implicitly conveyed by a graph annotation's aggregation [scopeClassId].
 */
internal fun implicitSingleInAnnotation(scopeClassId: ClassId): KaAnnotationSnapshot {
  return KaAnnotationSnapshot(
    MetroClassIds.singleIn,
    listOf(Name.identifier("scope") to KaAnnotationValueSnapshot.KClassRef(scopeClassId)),
  )
}

/** Extracts `scope`/`additionalScopes` class-literal arguments. */
internal fun annotationScopeKeys(annotation: KaAnnotation): Set<ClassId> {
  val result = mutableSetOf<ClassId>()
  for (argument in annotation.arguments) {
    when (argument.name.asString()) {
      "scope" -> classLiteralClassId(argument.expression)?.let(result::add)
      "additionalScopes",
      "scopes" -> {
        val values = (argument.expression as? KaAnnotationValue.ArrayValue)?.values.orEmpty()
        values.forEach { value -> classLiteralClassId(value)?.let(result::add) }
      }
    }
  }
  return result
}

private fun classLiteralClassId(value: KaAnnotationValue): ClassId? {
  val classLiteral = value as? KaAnnotationValue.ClassLiteralValue ?: return null
  // classId may be unpopulated for binary-deserialized values; the type still carries it
  return classLiteral.classId ?: (classLiteral.type as? KaClassType)?.classId
}

/**
 * Computes the bindings originated by [declaration]: `@Provides`/`@Binds`/`@Multibinds` callables,
 * injected classes, contributed bindings, and instance-binding factory parameters.
 */
internal fun KaSession.bindingData(
  declaration: KtDeclaration,
  options: MetroOptions,
): List<BindingData> {
  return when (declaration) {
    is KtPropertyAccessor -> bindingData(declaration.property, options)
    is KtNamedFunction,
    is KtProperty -> callableBindingData(declaration as KtCallableDeclaration, options)
    is KtParameter -> instanceBindingData(declaration, options)
    is KtClassOrObject -> classBindingData(declaration, options)
    is KtConstructor<*> -> classBindingData(declaration.getContainingClassOrObject(), options)
    else -> emptyList()
  }
}

private fun KaSession.callableBindingData(
  declaration: KtCallableDeclaration,
  options: MetroOptions,
): List<BindingData> {
  val symbol = declaration.symbol as? KaCallableSymbol ?: return emptyList()
  val getterSymbol = (symbol as? KaPropertySymbol)?.getter

  fun has(classIds: Set<ClassId>): Boolean {
    return symbol.hasAnyAnnotation(classIds) || getterSymbol?.hasAnyAnnotation(classIds) == true
  }

  val qualifier = qualifierAnnotation(symbol, options)
  val scope = scopeAnnotation(symbol, options)
  val returnType = symbol.returnType

  // Mirrors the compiler's transformIfIntoMultibinding: a contribution keeps its element key as
  // declared and joins its aggregate by id.
  fun multibindingId(elementKey: KaTypeKey): String? {
    return when {
      has(options.intoMapAnnotations) -> {
        val mapKeyType =
          mapKeyType(symbol, options)
            ?: getterSymbol?.let { mapKeyType(it, options) }
            ?: return null
        createMapBindingId(mapKeyType, elementKey)
      }
      has(options.intoSetAnnotations) || has(options.elementsIntoSetAnnotations) ->
        elementKey.computeMultibindingId()
      else -> null
    }
  }

  return when {
    has(options.bindsAnnotations) -> {
      val sourceType =
        symbol.receiverParameter?.returnType
          ?: (symbol as? KaNamedFunctionSymbol)?.valueParameters?.singleOrNull()?.returnType
          ?: return emptyList()
      val sourceParam = (symbol as? KaNamedFunctionSymbol)?.valueParameters?.singleOrNull()
      val consumedKey = typeKey(sourceType, sourceParam?.let { qualifierAnnotation(it, options) })
      val implementationName =
        (sourceType.fullyExpandedType as? KaClassType)?.classId?.shortClassName?.asString()
      val elementKey = typeKey(returnType, qualifier)
      val multibindingId = multibindingId(elementKey)
      listOf(
        BindingData(
          elementKey,
          if (multibindingId != null) {
            BindingKind.MULTIBINDING_CONTRIBUTION
          } else {
            BindingKind.BINDS
          },
          scope,
          implementationName,
          consumedKey,
          multibindingId,
        )
      )
    }
    has(options.multibindsAnnotations) ->
      listOf(
        BindingData(
          typeKey(returnType, qualifier),
          BindingKind.MULTIBINDING_DECLARATION,
          scope,
          null,
        )
      )
    has(options.providesAnnotations) -> {
      val elementType =
        if (has(options.elementsIntoSetAnnotations)) {
          val expanded = returnType.fullyExpandedType as? KaClassType ?: return emptyList()
          if (expanded.classId !in COLLECTION_LIKE_CLASS_IDS) return emptyList()
          expanded.typeArguments.firstOrNull()?.type ?: return emptyList()
        } else {
          returnType
        }
      val elementKey = typeKey(elementType, qualifier)
      val multibindingId = multibindingId(elementKey)
      listOf(
        BindingData(
          elementKey,
          if (multibindingId != null) {
            BindingKind.MULTIBINDING_CONTRIBUTION
          } else {
            BindingKind.PROVIDES
          },
          scope,
          null,
          multibindingId = multibindingId,
        )
      )
    }
    else -> emptyList()
  }
}

private fun KaSession.instanceBindingData(
  parameter: KtParameter,
  options: MetroOptions,
): List<BindingData> {
  val symbol = parameter.symbol as? KaValueParameterSymbol ?: return emptyList()
  if (!symbol.hasAnyAnnotation(options.providesAnnotations)) return emptyList()
  return listOf(
    BindingData(
      typeKey(symbol.returnType, qualifierAnnotation(symbol, options)),
      BindingKind.INSTANCE,
      null,
      null,
    )
  )
}

private fun KaSession.classBindingData(
  ktClass: KtClassOrObject,
  options: MetroOptions,
): List<BindingData> {
  val classSymbol = ktClass.symbol as? KaNamedClassSymbol ?: return emptyList()
  val result = mutableListOf<BindingData>()
  val qualifier = qualifierAnnotation(classSymbol, options)
  val scope = scopeAnnotation(classSymbol, options)
  val constructors = listOfNotNull(ktClass.primaryConstructor) + ktClass.secondaryConstructors

  fun hasOnClassOrConstructor(classIds: Set<ClassId>): Boolean {
    return classSymbol.hasAnyAnnotation(classIds) ||
      constructors.any { ctor ->
        ctor.symbol.hasAnyAnnotation(classIds)
      }
  }

  val isAssisted = hasOnClassOrConstructor(options.assistedInjectAnnotations)
  val hasInject = hasOnClassOrConstructor(options.injectAnnotations)
  val contributesAnnotations =
    classSymbol.annotations.filter { it.classId in bindingContributionAnnotations(options) }

  // Mirrors the compiler's findInjectConstructorsImpl: only regular, non-sealed classes are
  // injectable. Assisted-injected classes are consumed through their factory, not their own type.
  val isInjectableKind =
    classSymbol.classKind == KaClassKind.CLASS &&
      (classSymbol.modality == KaSymbolModality.FINAL ||
        classSymbol.modality == KaSymbolModality.OPEN)
  val isInjectable =
    isInjectableKind &&
      (hasInject || (options.contributesAsInject && contributesAnnotations.isNotEmpty()))
  val originClassId = ktClass.getClassId()
  if (isInjectable && !isAssisted) {
    result +=
      BindingData(
        typeKey(classSymbol.defaultType, qualifier),
        BindingKind.INJECT,
        scope,
        ktClass.name,
        originClassId = originClassId,
      )
  }

  val intoSetIds =
    options.contributesIntoSetAnnotations + options.customContributesIntoSetAnnotations
  for (annotation in contributesAnnotations) {
    val classId = annotation.classId ?: continue
    val boundType = contributedBoundType(ktClass, classSymbol, annotation) ?: continue
    val elementKey = typeKey(boundType, qualifier)
    val contributionScopes = annotationScopeKeys(annotation)
    val replaces = classListArgument(annotation, "replaces").toSet()
    when (classId) {
      in options.contributesBindingAnnotations ->
        result +=
          BindingData(
            elementKey,
            BindingKind.CONTRIBUTED,
            scope,
            ktClass.name,
            originClassId = originClassId,
            replaces = replaces,
            contributionScopes = contributionScopes,
          )

      in intoSetIds ->
        result +=
          BindingData(
            elementKey,
            BindingKind.MULTIBINDING_CONTRIBUTION,
            scope,
            ktClass.name,
            multibindingId = elementKey.computeMultibindingId(),
            originClassId = originClassId,
            replaces = replaces,
            contributionScopes = contributionScopes,
          )

      in options.contributesIntoMapAnnotations -> {
        val mapKeyType = mapKeyType(classSymbol, options) ?: continue
        result +=
          BindingData(
            elementKey,
            BindingKind.MULTIBINDING_CONTRIBUTION,
            scope,
            ktClass.name,
            multibindingId = createMapBindingId(mapKeyType, elementKey),
            originClassId = originClassId,
            replaces = replaces,
            contributionScopes = contributionScopes,
          )
      }
    }
  }
  return result
}

/**
 * Determines the bound type of a `@ContributesBinding`-style annotation: an explicit `binding<T>()`
 * (or Anvil-interop `boundType`) argument when present, otherwise the sole non-`Any` supertype.
 * Mirrors the compiler's `resolvedBindingArgument`.
 */
private fun KaSession.contributedBoundType(
  ktClass: KtClassOrObject,
  classSymbol: KaNamedClassSymbol,
  annotation: KaAnnotation,
): KaType? {
  // Anvil interop: boundType is a KClass argument, available structurally even from binaries
  val anvilBoundType =
    annotation.arguments
      .firstOrNull { it.name.asString() == "boundType" }
      ?.let { (it.expression as? KaAnnotationValue.ClassLiteralValue)?.type }
  if (anvilBoundType != null) return anvilBoundType

  // Metro's binding<T>() carries the bound type as a *type argument* of a nested annotation,
  // which the Analysis API doesn't expose structurally — read it from PSI. For binary classes
  // KaAnnotation.psi is null, but the decompiled class renders its annotation entries.
  val entryPsi =
    annotation.psi as? KtAnnotationEntry
      ?: ktClass.annotationEntries.firstOrNull {
        it.shortName == annotation.classId?.shortClassName
      }
  val explicitTypeRef =
    entryPsi?.valueArguments?.firstNotNullOfOrNull { argument ->
      val call = argument.getArgumentExpression() as? KtCallExpression
      if (call?.calleeExpression?.text == "binding") {
        call.typeArguments.firstOrNull()?.typeReference
      } else {
        null
      }
    }
  if (explicitTypeRef != null) {
    return explicitTypeRef.type
  }
  val superTypes = classSymbol.superTypes.filterNot { it.isAnyType }
  // A supertype's @DefaultBinding<T> supplies an implicit bound type, checked before the
  // sole-supertype fallback (mirrors ContributionsFirGenerator)
  for (superType in superTypes) {
    val supertypeSymbol =
      (superType.fullyExpandedType as? KaClassType)?.symbol as? KaClassSymbol ?: continue
    resolveDefaultBindingType(supertypeSymbol)?.let {
      return it
    }
  }
  return superTypes.singleOrNull()
}

/**
 * Resolves a supertype's `@DefaultBinding<T>` type argument: from source PSI when available, or
 * from the generated `DefaultBindingMirror.defaultBinding()` return type for binaries (annotation
 * type arguments don't survive into metadata).
 */
private fun KaSession.resolveDefaultBindingType(supertypeSymbol: KaClassSymbol): KaType? {
  val annotation =
    supertypeSymbol.annotations.firstOrNull { it.classId == MetroClassIds.defaultBinding }
      ?: return null
  (annotation.psi as? KtAnnotationEntry)?.typeArguments?.firstOrNull()?.typeReference?.let {
    return it.type
  }
  val mirror =
    supertypeSymbol.declaredMemberScope.classifiers
      .filterIsInstance<KaNamedClassSymbol>()
      .firstOrNull { it.name.asString() == "DefaultBindingMirror" } ?: return null
  return mirror.declaredMemberScope.callables
    .filterIsInstance<KaNamedFunctionSymbol>()
    .firstOrNull { it.name.asString() == "defaultBinding" }
    ?.returnType
}

/** Class-literal list argument values (e.g. `excludes`, `replaces`, `bindingContainers`). */
private fun classListArgument(annotation: KaAnnotation, name: String): List<ClassId> {
  val argument =
    annotation.arguments.firstOrNull { it.name.asString() == name } ?: return emptyList()
  return when (val value = argument.expression) {
    is KaAnnotationValue.ArrayValue -> value.values.mapNotNull { classLiteralClassId(it) }
    else -> listOfNotNull(classLiteralClassId(value))
  }
}
