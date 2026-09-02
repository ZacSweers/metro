// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.idea.MetroIdeModuleState
import dev.zacsweers.metro.idea.MetroIdeProjectService
import dev.zacsweers.metro.idea.index.DYNAMIC_GRAPH_CALLABLES
import dev.zacsweers.metro.idea.index.FileShard
import dev.zacsweers.metro.idea.index.FileShardBuilder
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.index.LibraryIndexPostProcessor
import dev.zacsweers.metro.idea.metroIdeState
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingIndexBuilder
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.IndexGenerationToken
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.SourceAssistedFactoryIdentity
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker
import org.jetbrains.kotlin.idea.stubindex.KotlinAnnotationsIndex
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Constructs source and dependency snapshots for one resolution coordinator.
 *
 * Calls are serialized by that coordinator. Preparation runs in its smart read; sealing uses only
 * captured data after the read. The builder owns reusable binary shards and never accepts or
 * publishes a generation. The callbacks keep invalidation fingerprints and presentation anchors
 * with the coordinator that owns their lifetime.
 */
internal class ResolutionSnapshotBuilder(
  private val project: Project,
  private val onShardRead: (KtFile, FileShard) -> Unit,
  private val captureResolutionInputs: (BindingIndexBuilder, Set<VirtualFile>) -> Unit,
) {
  private val libraryShards =
    object : LinkedHashMap<LibraryCacheKey, LibraryShard>(8, 0.75f, true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<LibraryCacheKey, LibraryShard>
      ): Boolean = size > MAX_CACHED_LIBRARY_SHARDS
    }

  /** Runs inside the coordinator's smart read and returns privately owned build inputs. */
  fun prepare(
    previous: SourceSnapshot?,
    inputs: IndexInputs,
    targets: List<ResolutionSnapshotTarget>,
    pending: SourceSnapshotChanges,
    coldSweep: Boolean,
    progress: IndexBuildProgressReporter,
    generationToken: IndexGenerationToken,
    checkCurrent: () -> Unit,
  ): PreparedResolutionSnapshot {
    check(!DumbService.isDumb(project))
    ProgressManager.checkCanceled()
    if (targets.isEmpty()) {
      return PreparedResolutionSnapshot(
        source = null,
        inputs = inputs,
        buildersByKey = emptyMap(),
        keysByModule = emptyMap(),
      )
    }

    val collectedSource =
      if (coldSweep) {
        coldSweep(
          targets.first().key.fingerprint.options,
          inputs,
          pending,
          progress,
        )
      } else {
        incremental(checkNotNull(previous), inputs, pending, progress)
      }
    checkCurrent()

    progress.phase(IndexBuildPhase.COMBINING_DECLARATIONS)
    val rawSource = aggregateSource(collectedSource, progress)
    progress.phase(IndexBuildPhase.RESOLVING_ASSISTED_FACTORIES)
    val summary =
      collectedSource.librarySummary
        ?: buildFinalizedSourceLibrarySummary(
          project,
          rawSource,
          buildSourceOwnershipIndex(rawSource),
        )
    val finalizedSource = collectedSource.withLibrarySummary(summary)
    val source = rawSource.withAddedFactories(summary.sourceFactories.addedBindings)
    val buildersByKey = linkedMapOf<SnapshotKey, BindingIndexBuilder>()
    val keysByModule = linkedMapOf<Module, SnapshotKey>()
    val declarationSignatureFiles = finalizedSource.shardOrder.toSet()
    for ((key, modules) in targets) {
      ProgressManager.checkCanceled()
      checkCurrent()
      val library =
        if (key.resolveFromLibraries) {
          progress.phase(IndexBuildPhase.READING_DEPENDENCY_METADATA)
          libraryShardFor(key.fingerprint, inputs.roots, source, summary)
        } else {
          LibraryShard.EMPTY
        }
      progress.phase(IndexBuildPhase.BUILDING_GRAPH_INDEX)
      val indexBuilder =
        BindingIndexBuilder(generationToken).apply {
          bindings += source.bindings + library.bindings
          consumers += source.consumers
          graphs += source.graphs
          contributions += source.contributions + library.contributions
          assistedSites += source.assistedSites
          bindingContainers += source.bindingContainers
          incompleteAssistedFactories +=
            if (key.resolveFromLibraries) library.incompleteFactories
            else summary.sourceFactories.incompleteFactories
          dynamicGraphs += source.dynamicGraphs
        }
      captureResolutionInputs(indexBuilder, declarationSignatureFiles)
      buildersByKey[key] = indexBuilder
      for (module in modules) {
        keysByModule[module] = key
      }
    }
    return PreparedResolutionSnapshot(
      source = finalizedSource,
      inputs = inputs,
      buildersByKey = buildersByKey,
      keysByModule = keysByModule,
    )
  }

  private fun buildSourceOwnershipIndex(source: SourceAggregate): BindingIndex {
    val builder =
      BindingIndexBuilder().apply {
        bindings += source.bindings
        consumers += source.consumers
        graphs += source.graphs
        contributions += source.contributions
        assistedSites += source.assistedSites
        bindingContainers += source.bindingContainers
        dynamicGraphs += source.dynamicGraphs
      }
    captureResolutionInputs(builder, emptySet())
    return builder.build()
  }

  private fun coldSweep(
    options: MetroOptions,
    inputs: IndexInputs,
    pending: SourceSnapshotChanges,
    progress: IndexBuildProgressReporter?,
  ): SourceSnapshot {
    progress?.phase(IndexBuildPhase.DISCOVERING_SOURCE_FILES)
    val annotationIds = projectSweepAnnotationIds(options)
    val shortNames = annotationIds.mapToSet { it.shortClassName.asString() }
    val transaction = SourceSnapshotTransaction()
    val candidates = candidateFiles(annotationIds, shortNames)
    val total = candidates.size + pending.requested.size
    var completed = 0
    progress?.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, completed, total)
    for (file in candidates) {
      ProgressManager.checkCanceled()
      try {
        val virtualFile = file.virtualFile ?: continue
        transaction.applyShard(
          virtualFile,
          shardFor(file, forceRebuild = pending.forcesRebuild(virtualFile)),
        )
      } finally {
        completed++
        progress?.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, completed, total)
      }
    }
    // Stub loading can surface requested files before their annotations reach the stub index.
    for (virtualFile in pending.requested) {
      ProgressManager.checkCanceled()
      try {
        if (!virtualFile.isValid || transaction.containsShard(virtualFile)) {
          continue
        }
        val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: continue
        if (containsRelevantAnnotation(file, shortNames)) {
          transaction.applyShard(
            virtualFile,
            shardFor(file, forceRebuild = pending.forcesRebuild(virtualFile)),
          )
        }
      } finally {
        completed++
        progress?.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, completed, total)
      }
    }
    return transaction.snapshot(inputs, moduleFingerprints(), shortNames)
  }

  private fun incremental(
    prev: SourceSnapshot,
    inputs: IndexInputs,
    pending: SourceSnapshotChanges,
    progress: IndexBuildProgressReporter?,
  ): SourceSnapshot {
    val dirty =
      if (pending.forceAll) {
        buildSet {
          addAll(prev.shardOrder)
          addAll(pending.dirty)
        }
      } else {
        pending.dirty
      }
    if (dirty.isEmpty() && pending.requested.isEmpty()) {
      // Output-only compiler-option changes update inputs without touching any shard.
      return if (prev.inputs == inputs) prev else prev.withInputs(inputs)
    }
    val transaction = SourceSnapshotTransaction(prev)
    val total = dirty.size + pending.requested.size
    var completed = 0
    progress?.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, completed, total)
    for (virtualFile in dirty) {
      ProgressManager.checkCanceled()
      try {
        if (!virtualFile.isValid) {
          transaction.removeShard(virtualFile)
          continue
        }
        val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
        if (file == null || !file.isValid || !containsRelevantAnnotation(file, prev.shortNames)) {
          transaction.removeShard(virtualFile)
          continue
        }
        transaction.applyShard(
          virtualFile,
          shardFor(file, forceRebuild = pending.forcesRebuild(virtualFile)),
        )
      } finally {
        completed++
        progress?.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, completed, total)
      }
    }
    // Requested files were enqueued before their stubs or directory events settled. Draining
    // them here keeps them from lingering until a cold sweep.
    for (virtualFile in pending.requested) {
      ProgressManager.checkCanceled()
      try {
        if (!virtualFile.isValid || transaction.containsShard(virtualFile)) {
          continue
        }
        val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: continue
        if (containsRelevantAnnotation(file, prev.shortNames)) {
          transaction.applyShard(
            virtualFile,
            shardFor(file, forceRebuild = pending.forcesRebuild(virtualFile)),
          )
        }
      } finally {
        completed++
        progress?.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, completed, total)
      }
    }
    return transaction.snapshot(
      inputs,
      prev.moduleFingerprints,
      prev.shortNames,
      sourceModulesMayHaveChanged = pending.forceRebuildFiles.isNotEmpty(),
    )
  }

  private fun libraryShardFor(
    fingerprint: IndexOptionsFingerprint,
    rootsGeneration: Long,
    source: SourceAggregate,
    summary: FinalizedSourceLibrarySummary,
  ): LibraryShard {
    val key = LibraryCacheKey(fingerprint, rootsGeneration, summary.inputs)
    libraryShards[key]?.let {
      return it
    }

    val bindings = source.bindings.toMutableList()
    val contributions = source.contributions.toMutableList()
    val incompleteFactories =
      LibraryIndexPostProcessor(
          project,
          fingerprint.options,
          bindings,
          source.consumers,
          source.graphs,
          contributions,
          summary.sourceFactories.factoryUseSites,
          summary.consumerOwnership,
          summary.sourceFactories,
        )
        .postProcess()
    val shard =
      LibraryShard(
        bindings.drop(source.bindings.size),
        contributions.drop(source.contributions.size),
        incompleteFactories,
      )
    libraryShards[key] = shard
    return shard
  }

  private fun projectSweepAnnotationIds(fallbackOptions: MetroOptions): Set<ClassId> {
    val ids = linkedSetOf<ClassId>()
    ids += sweepAnnotationIds(fallbackOptions)
    val service = project.service<MetroIdeProjectService>()
    for (module in ModuleManager.getInstance(project).modules) {
      ProgressManager.checkCanceled()
      val state = service.state(module)
      if (state.isEnabled) ids += sweepAnnotationIds(state.options)
    }
    return ids
  }

  fun projectSweepShortNames(fallbackOptions: MetroOptions): Set<String> {
    return projectSweepAnnotationIds(fallbackOptions).mapToSet { it.shortClassName.asString() }
  }

  /** Compiler output/report settings do not change semantic fingerprints or source declarations. */
  fun moduleFingerprints(): Map<Module, IndexOptionsFingerprint> {
    val service = project.service<MetroIdeProjectService>()
    return buildMap {
      for (module in ModuleManager.getInstance(project).modules) {
        ProgressManager.checkCanceled()
        val state = service.state(module)
        if (state.isEnabled) put(module, fingerprintFor(state))
      }
    }
  }

  fun fingerprintFor(state: MetroIdeModuleState): IndexOptionsFingerprint {
    return IndexOptionsFingerprint(state.options)
  }

  /** Files containing any Metro-relevant annotation or an exact aliased import, via indexes. */
  private fun candidateFiles(annotationIds: Set<ClassId>, shortNames: Set<String>): Set<KtFile> {
    val searchScope = GlobalSearchScope.projectScope(project)
    val files = LinkedHashSet<KtFile>()
    for (shortName in shortNames.sorted()) {
      ProgressManager.checkCanceled()
      for (entry in KotlinAnnotationsIndex[shortName, project, searchScope]) {
        ProgressManager.checkCanceled()
        files += entry.containingKtFile
      }
    }

    // Searching a distinctive package component limits this pass to import and package occurrences.
    val idsBySearchWord = annotationIds.groupBy { annotationId ->
      annotationId.packageFqName.pathSegments().maxByOrNull { it.asString().length }?.asString()
        ?: annotationId.shortClassName.asString()
    }
    val searchHelper = PsiSearchHelper.getInstance(project)
    for (callableId in DYNAMIC_GRAPH_CALLABLES.keys) {
      val callableName = callableId.callableName.asString()
      searchHelper.processElementsWithWord(
        { element, _ ->
          (element.containingFile as? KtFile)?.let(files::add)
          true
        },
        searchScope,
        callableName,
        UsageSearchContext.IN_CODE,
        true,
      )
    }
    for ((searchWord, matchingIds) in idsBySearchWord) {
      ProgressManager.checkCanceled()
      val canonicalNames = matchingIds.mapToSet { it.asSingleFqName() }
      searchHelper.processElementsWithWord(
        { element, _ ->
          ProgressManager.checkCanceled()
          val directive = PsiTreeUtil.getParentOfType(element, KtImportDirective::class.java, false)
          val file = directive?.containingFile as? KtFile
          if (
            directive?.aliasName != null &&
              directive.importedFqName in canonicalNames &&
              file != null
          ) {
            files += file
          }
          true
        },
        searchScope,
        searchWord,
        UsageSearchContext.IN_CODE,
        true,
      )
    }
    return files
  }

  fun containsRelevantAnnotation(file: KtFile, shortNames: Set<String>): Boolean {
    var hasAliasedImport = false
    for (directive in file.importDirectives) {
      ProgressManager.checkCanceled()
      if (directive.aliasName != null) {
        hasAliasedImport = true
        break
      }
    }
    val names =
      if (hasAliasedImport) {
        shortNames +
          file.annotationShortNamesIncludingAliases(
            sweepAnnotationIds(file.metroIdeState().options)
          )
      } else {
        shortNames
      }
    var hasRelevantAnnotation = false
    PsiTreeUtil.processElements(file) { element ->
      ProgressManager.checkCanceled()
      if (element is KtAnnotationEntry && element.shortName?.asString() in names) {
        hasRelevantAnnotation = true
        false
      } else {
        true
      }
    }
    if (hasRelevantAnnotation) return true

    val dynamicGraphNames = buildSet {
      for (callableId in DYNAMIC_GRAPH_CALLABLES.keys) {
        ProgressManager.checkCanceled()
        add(callableId.callableName.asString())
        for (directive in file.importDirectives) {
          ProgressManager.checkCanceled()
          if (directive.importedFqName == callableId.asSingleFqName()) {
            directive.aliasName?.let(::add)
          }
        }
      }
    }
    var hasDynamicGraphCall = false
    PsiTreeUtil.processElements(file) { element ->
      ProgressManager.checkCanceled()
      if (element is KtCallExpression && element.calleeExpression?.text in dynamicGraphNames) {
        hasDynamicGraphCall = true
        false
      } else {
        true
      }
    }
    return hasDynamicGraphCall
  }

  private fun shardFor(file: KtFile, forceRebuild: Boolean = false): FileShard {
    // Forced rebuilds go through the same cached value so later non-force lookups can never
    // revert to a stale pre-force shard. The per-file tracker invalidates the stored value.
    if (forceRebuild) {
      forceTracker(file).incModificationCount()
    }
    val cached =
      CachedValuesManager.getCachedValue(file) {
        // Shards use their owning module's options. Explicit dependency files cover inherited graph
        // members and factory includes even when those files contain no Metro annotations
        // themselves.
        val state = file.metroIdeState()
        val builder = if (state.isEnabled) FileShardBuilder(file.project, state.options) else null
        val shard = builder?.buildShard(file) ?: FileShard.EMPTY
        // Register dependency PSI with the platform cache. The shard and service store virtual
        // files so they do not keep those PSI trees alive.
        CachedValueProvider.Result.create(
          shard,
          file,
          forceTracker(file),
          KotlinCompilerSettingsTracker.getInstance(file.project),
          ProjectRootModificationTracker.getInstance(file.project),
          *(builder?.psiDependencies ?: emptySet()).toTypedArray(),
        )
      }
    if (!forceRebuild && cached === FileShard.EMPTY && file.textLength > 0) {
      val state = file.metroIdeState()
      if (state.isEnabled) {
        // The cached value was computed while the module read as disabled, usually a stub-loading
        // race. Recompute through the force tracker so the fresh result is stored and later
        // passes stop re-analyzing.
        return shardFor(file, forceRebuild = true)
      }
    }
    onShardRead(file, cached)
    return cached
  }

  /** Stored on the file so the tracker and the cached value share one lifetime. */
  private fun forceTracker(file: KtFile): SimpleModificationTracker {
    file.getUserData(FORCE_TRACKER_KEY)?.let {
      return it
    }
    return (file as UserDataHolderEx).putUserDataIfAbsent(
      FORCE_TRACKER_KEY,
      SimpleModificationTracker(),
    )
  }

  /** Drops stale library data without changing the published presentation generation. */
  fun evictLibraryShards(
    currentRoots: Long,
    activeFingerprints: Set<IndexOptionsFingerprint>? = null,
  ) {
    libraryShards.keys.removeIf { key ->
      key.rootsGeneration != currentRoots ||
        (activeFingerprints != null && key.fingerprint !in activeFingerprints)
    }
  }

  /** Disabling dependency resolution discards its reusable binary shards. */
  fun clearLibraryShards() {
    libraryShards.clear()
  }

  private companion object {
    const val MAX_CACHED_LIBRARY_SHARDS = 8
  }
}

private val FORCE_TRACKER_KEY = Key.create<SimpleModificationTracker>("metro.shard.force.tracker")

private data class LibraryCacheKey(
  val fingerprint: IndexOptionsFingerprint,
  val rootsGeneration: Long,
  val inputs: LibraryInputs,
)

private data class LibraryShard(
  val bindings: List<KaBinding>,
  val contributions: List<ContributionEntry>,
  val incompleteFactories: Map<KaModule, Map<SourceAssistedFactoryIdentity, String>> = emptyMap(),
) {
  companion object {
    val EMPTY = LibraryShard(emptyList(), emptyList())
  }
}
