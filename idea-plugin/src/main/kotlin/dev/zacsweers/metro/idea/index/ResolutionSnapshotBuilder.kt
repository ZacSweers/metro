// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.circuit.CircuitClassIds
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.idea.MetroIdeModuleState
import dev.zacsweers.metro.idea.MetroIdeProjectService
import dev.zacsweers.metro.idea.metroIdeState
import dev.zacsweers.metro.idea.model.AssistedSite
import dev.zacsweers.metro.idea.model.BindingContainerEntry
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingIndexBuilder
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.DynamicGraphCall
import dev.zacsweers.metro.idea.model.DynamicGraphId
import dev.zacsweers.metro.idea.model.GraphCallableReference
import dev.zacsweers.metro.idea.model.GraphCallableSignature
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.GraphDefaultImplementation
import dev.zacsweers.metro.idea.model.GraphExtensionFactoryAccessor
import dev.zacsweers.metro.idea.model.GraphReference
import dev.zacsweers.metro.idea.model.IndexGenerationToken
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import dev.zacsweers.metro.idea.model.SourceAssistedFactoryIdentity
import java.util.Collections
import java.util.IdentityHashMap
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker
import org.jetbrains.kotlin.idea.stubindex.KotlinAnnotationsIndex
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
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

  private fun aggregateSource(
    snapshot: SourceSnapshot,
    progress: IndexBuildProgressReporter?,
  ): SourceAggregate {
    val bindings = mutableListOf<KaBinding>()
    val consumers = mutableListOf<ConsumerEntry>()
    val graphs = mutableListOf<KaGraphDeclaration>()
    val contributions = mutableListOf<ContributionEntry>()
    val assistedSites = mutableListOf<AssistedSite>()
    val bindingContainers = mutableListOf<BindingContainerEntry>()
    val graphInterfaces = mutableListOf<GraphInterfaceSurface>()
    val dynamicGraphs = linkedMapOf<DynamicGraphId, DynamicGraphCall>()
    val factoryInputs = linkedMapOf<FactoryInputEntry.Id, FactoryInputEntry>()
    var factoryInputBindings: CanonicalFactoryInputBindings? = null
    var completed = 0
    progress?.counted(
      IndexBuildPhase.COMBINING_DECLARATIONS,
      completed,
      snapshot.shardOrder.size,
    )
    for (virtualFile in snapshot.shardOrder) {
      ProgressManager.checkCanceled()
      try {
        val shard = snapshot.shards[virtualFile] ?: continue
        if (shard.factoryInputs.isEmpty()) {
          bindings += shard.bindings
        } else {
          for (binding in shard.bindings) {
            val isOwnedFactoryInput =
              binding is KaBinding.BoundInstance &&
                binding.ownerGraphId != null &&
                (binding.isGraphInput || binding.isBindingContainerInput)
            if (!isOwnedFactoryInput) {
              bindings += binding
              continue
            }
            val instances =
              factoryInputBindings
                ?: CanonicalFactoryInputBindings(bindings).also { factoryInputBindings = it }
            instances.add(binding)
          }
        }
        consumers += shard.consumers
        graphs += shard.graphs
        contributions += shard.contributions
        assistedSites += shard.assistedSites
        bindingContainers += shard.bindingContainers
        graphInterfaces += shard.graphInterfaces
        for (dynamicGraph in shard.dynamicGraphs) {
          dynamicGraphs.putIfAbsent(dynamicGraph.id, dynamicGraph)
        }
        for (input in shard.factoryInputs) factoryInputs.putIfAbsent(input.id, input)
      } finally {
        completed++
        progress?.counted(
          IndexBuildPhase.COMBINING_DECLARATIONS,
          completed,
          snapshot.shardOrder.size,
        )
      }
    }
    factoryInputBindings?.finish()
    for (input in factoryInputs.values) {
      val sharedBindings = input.bindings
      if (sharedBindings.firstOrNull() is KaBinding.BoundInstance) {
        bindings.addAll(sharedBindings.subList(1, sharedBindings.size))
      } else {
        bindings += sharedBindings
      }
      consumers += input.consumers
    }
    attachGraphInterfaces(graphInterfaces, graphs, bindings, consumers)
    return SourceAggregate(
      bindings,
      consumers,
      graphs,
      contributions,
      assistedSites,
      bindingContainers,
      dynamicGraphs.values.toList(),
    )
  }

  /** Attaches interfaces with matching scopes. BindingIndex selects them for each graph path. */
  private fun attachGraphInterfaces(
    surfaces: List<GraphInterfaceSurface>,
    graphs: MutableList<KaGraphDeclaration>,
    bindings: MutableList<KaBinding>,
    consumers: MutableList<ConsumerEntry>,
  ) {
    if (surfaces.isEmpty()) return
    val surfacesByScope = linkedMapOf<ClassId, MutableList<GraphInterfaceSurface>>()
    for (surface in surfaces) {
      ProgressManager.checkCanceled()
      for (scope in surface.contribution.scopeKeys) {
        surfacesByScope.getOrPut(scope) { mutableListOf() } += surface
      }
    }
    for (graphIndex in graphs.indices) {
      ProgressManager.checkCanceled()
      val graph = graphs[graphIndex]
      val candidates = linkedSetOf<GraphInterfaceSurface>()
      for (scope in graph.scopeKeys) candidates += surfacesByScope[scope].orEmpty()
      if (candidates.isEmpty()) continue
      val interfaces = candidates.map { surface ->
        ProgressManager.checkCanceled()
        surface.forGraph(graph)
      }
      graphs[graphIndex] = graph.withContributedInterfaces(interfaces)
      for (contribution in interfaces) {
        bindings += contribution.bindings
        consumers += contribution.consumers
      }
    }
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

/** Module groups that can share one options-specific binding index. */
internal data class ResolutionSnapshotTarget(
  val key: SnapshotKey,
  val modules: List<Module>,
)

/**
 * Transfers one attempt's captured data from its read action to index sealing. The mutable builders
 * stay private, and callers receive complete indexes only after every target has finished.
 */
internal class PreparedResolutionSnapshot(
  val source: SourceSnapshot?,
  val inputs: IndexInputs,
  buildersByKey: Map<SnapshotKey, BindingIndexBuilder>,
  keysByModule: Map<Module, SnapshotKey>,
) {
  private val buildersByKey = buildersByKey.toMap()
  val targetKeys: Set<SnapshotKey> = buildersByKey.keys.toSet()
  val keysByModule: Map<Module, SnapshotKey> = keysByModule.toMap()

  /** Seals this attempt outside the read action and checks supersession before each target. */
  fun buildIndexes(checkCurrent: () -> Unit): Map<SnapshotKey, BindingIndex> {
    return buildMap(buildersByKey.size) {
      for ((key, builder) in buildersByKey) {
        checkCurrent()
        put(key, builder.build())
      }
    }
  }
}

/** Frozen source work captured by the coordinator for one snapshot attempt. */
internal data class SourceSnapshotChanges(
  val dirty: Set<VirtualFile>,
  val requested: Set<VirtualFile>,
  val forceRebuildFiles: Set<VirtualFile>,
  val forceAll: Boolean,
) {
  /** Rebuilds structurally changed files and their owners even when cached PSI stamps match. */
  fun forcesRebuild(file: VirtualFile): Boolean = forceAll || file in forceRebuildFiles
}

/** Options and dependency-resolution mode shared by one index target group. */
internal data class SnapshotKey(
  val fingerprint: IndexOptionsFingerprint,
  val resolveFromLibraries: Boolean,
)

/** Platform input versions captured together with source declarations. */
internal data class IndexInputs(val roots: Long, val compilerSettings: Long)

/** Keeps one factory instance per source parameter while retaining every exact graph owner. */
private class CanonicalFactoryInputBindings(private val bindings: MutableList<KaBinding>) {
  private val groups = LinkedHashMap<FactoryInputBindingIdentity, FactoryInputBindingGroup>()

  fun add(binding: KaBinding.BoundInstance) {
    val file = binding.pointer.virtualFile
    val range = binding.pointer.psiRange
    if (file == null || range == null) {
      bindings += binding
      return
    }

    val identity =
      FactoryInputBindingIdentity(
        binding.typeKey,
        file,
        range.startOffset,
        range.endOffset,
        binding.isGraphInput,
        binding.isBindingContainerInput,
      )
    val existing = groups[identity]
    if (existing == null) {
      groups[identity] = FactoryInputBindingGroup(bindings.size, binding)
      bindings += binding
      return
    }

    val ownerGraphId = binding.ownerGraphId
    if (ownerGraphId != null && ownerGraphId != existing.binding.ownerGraphId) {
      val owners =
        existing.additionalOwners
          ?: linkedSetOf<GraphDeclarationId>().also { existing.additionalOwners = it }
      owners += ownerGraphId
    }
    if (binding.additionalOwnerGraphIds.isNotEmpty()) {
      val owners =
        existing.additionalOwners
          ?: linkedSetOf<GraphDeclarationId>().also { existing.additionalOwners = it }
      owners += binding.additionalOwnerGraphIds
      existing.binding.ownerGraphId?.let(owners::remove)
    }
  }

  fun finish() {
    for (group in groups.values) {
      ProgressManager.checkCanceled()
      val owners = group.additionalOwners
      if (owners.isNullOrEmpty()) continue

      val binding = group.binding
      bindings[group.index] =
        KaBinding.BoundInstance(
          pointer = binding.pointer,
          typeKey = binding.typeKey,
          containerId = binding.containerId,
          isGraphInput = binding.isGraphInput,
          isBindingContainerInput = binding.isBindingContainerInput,
          isGraphPrivate = binding.isGraphPrivate,
          ownerGraphId = binding.ownerGraphId,
          additionalOwnerGraphIds = Collections.unmodifiableSet(LinkedHashSet(owners)),
        )
    }
  }
}

private data class FactoryInputBindingIdentity(
  val key: KaTypeKey,
  val file: VirtualFile,
  val startOffset: Int,
  val endOffset: Int,
  val isGraphInput: Boolean,
  val isBindingContainerInput: Boolean,
)

private class FactoryInputBindingGroup(
  val index: Int,
  val binding: KaBinding.BoundInstance,
  var additionalOwners: MutableSet<GraphDeclarationId>? = null,
)

private val FORCE_TRACKER_KEY = Key.create<SimpleModificationTracker>("metro.shard.force.tracker")

/** An immutable source view. Incremental passes copy it with only the changed shards replaced. */
internal class SourceSnapshot(
  val inputs: IndexInputs,
  val moduleFingerprints: Map<Module, IndexOptionsFingerprint>,
  val shortNames: Set<String>,
  val shards: PartitionedFileMap<FileShard>,
  /** Reused across ordinary replacements so declaration and duplicate ordering stays stable. */
  val shardOrder: List<VirtualFile>,
  /** Dependency file to the shard files that must rebuild when it changes. */
  val dependencyOwners: PartitionedFileMap<Set<VirtualFile>>,
  /** Maps shared declaration files to the shards that reference them. */
  val sharedDeclarationOwners: PartitionedFileMap<Set<VirtualFile>>,
  /** Reused while effective binary lookup inputs and source-module ownership remain unchanged. */
  val librarySummary: FinalizedSourceLibrarySummary?,
) {
  fun withInputs(newInputs: IndexInputs): SourceSnapshot =
    SourceSnapshot(
      newInputs,
      moduleFingerprints,
      shortNames,
      shards,
      shardOrder,
      dependencyOwners,
      sharedDeclarationOwners,
      librarySummary,
    )

  fun withLibrarySummary(summary: FinalizedSourceLibrarySummary): SourceSnapshot {
    if (librarySummary === summary) return this
    return SourceSnapshot(
      inputs,
      moduleFingerprints,
      shortNames,
      shards,
      shardOrder,
      dependencyOwners,
      sharedDeclarationOwners,
      summary,
    )
  }
}

/** Collects changed shards and dependency owners, then builds a snapshot sharing unchanged data. */
private class SourceSnapshotTransaction(private val previous: SourceSnapshot? = null) {
  private val shardChanges = linkedMapOf<VirtualFile, FileShard?>()
  private val ownerChanges = linkedMapOf<VirtualFile, MutableSet<VirtualFile>?>()
  private val sharedOwnerChanges = linkedMapOf<VirtualFile, MutableSet<VirtualFile>?>()

  fun containsShard(file: VirtualFile): Boolean = currentShard(file) != null

  fun applyShard(file: VirtualFile, shard: FileShard) {
    removeShard(file)
    if (shard === FileShard.EMPTY) return

    shardChanges[file] = shard
    for (dependencyFile in shard.dependencyFiles) {
      mutableOwners(dependencyFile).add(file)
    }
    for (sharedDeclarationFile in shard.sharedDeclarationFiles) {
      mutableSharedOwners(sharedDeclarationFile).add(file)
    }
  }

  fun removeShard(file: VirtualFile) {
    val existing = currentShard(file) ?: return
    shardChanges[file] = null
    for (dependencyFile in existing.dependencyFiles) {
      val owners = mutableOwners(dependencyFile)
      owners.remove(file)
      if (owners.isEmpty()) {
        ownerChanges[dependencyFile] = null
      }
    }
    for (sharedDeclarationFile in existing.sharedDeclarationFiles) {
      val owners = mutableSharedOwners(sharedDeclarationFile)
      owners.remove(file)
      if (owners.isEmpty()) {
        sharedOwnerChanges[sharedDeclarationFile] = null
      }
    }
  }

  /**
   * Preserves surviving file order and appends new files. Reuses the library summary when lookup
   * inputs and source-module ownership are unchanged.
   */
  fun snapshot(
    inputs: IndexInputs,
    moduleFingerprints: Map<Module, IndexOptionsFingerprint>,
    shortNames: Set<String>,
    sourceModulesMayHaveChanged: Boolean = false,
  ): SourceSnapshot {
    val previousShards = previous?.shards ?: PartitionedFileMap.empty()
    val previousOwners = previous?.dependencyOwners ?: PartitionedFileMap.empty()
    val previousSharedOwners = previous?.sharedDeclarationOwners ?: PartitionedFileMap.empty()
    val ownerUpdates = linkedMapOf<VirtualFile, Set<VirtualFile>?>()
    for ((file, owners) in ownerChanges) {
      ownerUpdates[file] = owners?.toSet()
    }
    val sharedOwnerUpdates = linkedMapOf<VirtualFile, Set<VirtualFile>?>()
    for ((file, owners) in sharedOwnerChanges) {
      sharedOwnerUpdates[file] = owners?.toSet()
    }
    val shards = previousShards.withChanges(shardChanges)
    val owners = previousOwners.withChanges(ownerUpdates)
    val sharedOwners = previousSharedOwners.withChanges(sharedOwnerUpdates)

    val existingOrder = previous?.shardOrder.orEmpty()
    val membershipChanged = shardChanges.any { (file, updated) ->
      val existed = previous?.shards?.get(file) != null
      existed != (updated != null)
    }
    val order =
      if (previous != null && !membershipChanged) {
        existingOrder
      } else {
        buildList {
          for (file in existingOrder) {
            if (file in shards) add(file)
          }
          for ((file, shard) in shardChanges) {
            if (shard != null && previous?.shards?.get(file) == null) add(file)
          }
        }
      }
    val previousSummary = previous?.librarySummary
    // File identity and declaration signatures survive moves between modules. The summary holds
    // captured module visibility, so a structural change also invalidates these lookup inputs.
    val libraryInputsChanged =
      sourceModulesMayHaveChanged ||
        previous == null ||
        shardChanges.any { (file, updated) ->
          val before = previous.shards[file]?.librarySignature()
          val after = updated?.librarySignature()
          before != after
        }
    val librarySummary = if (!libraryInputsChanged) previousSummary else null
    return SourceSnapshot(
      inputs,
      moduleFingerprints,
      shortNames,
      shards,
      order,
      owners,
      sharedOwners,
      librarySummary,
    )
  }

  /** Returns null for staged removals and uses the previous snapshot for unchanged files. */
  private fun currentShard(file: VirtualFile): FileShard? {
    if (shardChanges.containsKey(file)) return shardChanges[file]
    return previous?.shards?.get(file)
  }

  private fun mutableOwners(file: VirtualFile): MutableSet<VirtualFile> {
    if (ownerChanges.containsKey(file)) {
      val existing = ownerChanges[file]
      if (existing != null) return existing
      return linkedSetOf<VirtualFile>().also { ownerChanges[file] = it }
    }
    val existing = previous?.dependencyOwners?.get(file).orEmpty()
    return LinkedHashSet(existing).also { ownerChanges[file] = it }
  }

  private fun mutableSharedOwners(file: VirtualFile): MutableSet<VirtualFile> {
    if (sharedOwnerChanges.containsKey(file)) {
      val existing = sharedOwnerChanges[file]
      if (existing != null) return existing
      return linkedSetOf<VirtualFile>().also { sharedOwnerChanges[file] = it }
    }
    val existing = previous?.sharedDeclarationOwners?.get(file).orEmpty()
    return LinkedHashSet(existing).also { sharedOwnerChanges[file] = it }
  }
}

/** Only values that change classpath lookup or the actual factory use site participate here. */
private fun FileShard.librarySignature(): SourceLibraryShardSignature {
  return SourceLibraryShardSignature(
    graphs.map { graph ->
      GraphLibrarySignature(
        graph.declarationId,
        graph.scopeKeys,
        graph.scopingAnnotations,
        graph.excludes,
        graph.bindingContainers,
        graph.includedBindingContainers,
        graph.includedDependencies,
        graph.isExtension,
        graph.selfReferences,
        graph.supertypeKeys,
        graph.supertypeDeclarations,
        graph.extensionCreations,
        graph.extensionFactories.map(::extensionFactoryLibrarySignature),
        graph.defaultImplementations.map(::defaultImplementationLibrarySignature),
        graph.injectedMemberOwnerIds,
        graph.daggerAnvilInteropEnabled,
        graph.pointer.element != null,
      )
    },
    contributions.map(::contributionLibrarySignature),
    consumers.map(::consumerLibrarySignature),
    bindings.mapNotNull { it.writtenFactoryBudgetKey() },
    bindings.mapNotNull(::bindingLibrarySignature),
    factoryInputs.map { input ->
      FactoryInputLibrarySignature(
        input.id,
        input.consumers.map(::consumerLibrarySignature),
        input.bindings.mapNotNull { it.writtenFactoryBudgetKey() },
        input.bindings.mapNotNull(::bindingLibrarySignature),
      )
    },
    dynamicGraphs.map { dynamicGraph ->
      DynamicGraphLibrarySignature(
        dynamicGraph.id,
        dynamicGraph.targetGraph,
        dynamicGraph.bindingKeys,
        dynamicGraph.isFactory,
        dynamicGraph.pointer.element != null,
      )
    },
    graphInterfaces.map(::graphInterfaceLibrarySignature),
  )
}

private fun contributionLibrarySignature(
  contribution: ContributionEntry
): ContributionLibrarySignature {
  return ContributionLibrarySignature(
    contribution.scopeKeys,
    contribution.classId,
    contribution.kind,
    contribution.replaces,
    contribution.graphExtension,
    contribution.pointer.virtualFile,
    contribution.pointer.element != null,
  )
}

private fun consumerLibrarySignature(consumer: ConsumerEntry): ConsumerLibrarySignature {
  return ConsumerLibrarySignature(
    contextKeyLibrarySignature(consumer.contextKey),
    consumer.typeClassId,
    consumer.multibindingId,
    consumer.graphId,
    consumer.includedContainerKey,
    consumer.pointer.virtualFile,
    consumer.pointer.element != null,
    consumer.originClassId,
    consumer.containerId,
    consumer.contributionScopes,
    consumer.graphContribution,
    consumer.memberOwnerClassId,
    consumer.graphRequestKind,
    consumer.isSuspend,
    consumer.isOptional,
  )
}

private fun extensionFactoryLibrarySignature(
  factory: GraphExtensionFactoryAccessor
): ExtensionFactoryLibrarySignature {
  return ExtensionFactoryLibrarySignature(
    factory.factoryKey,
    factory.extensionKey,
    factory.extension,
    factory.pointer.virtualFile,
    factory.pointer.element != null,
  )
}

private fun callableLibrarySignature(
  callable: GraphCallableReference
): GraphCallableLibrarySignature {
  return GraphCallableLibrarySignature(
    callable.signature,
    callable.pointer.virtualFile,
    callable.pointer.element != null,
  )
}

private fun defaultImplementationLibrarySignature(
  implementation: GraphDefaultImplementation
): GraphDefaultImplementationLibrarySignature {
  return GraphDefaultImplementationLibrarySignature(
    callableLibrarySignature(implementation.declaration),
    implementation.overriddenDeclarations.map(::callableLibrarySignature),
    implementation.isOptional,
  )
}

private fun graphInterfaceLibrarySignature(
  surface: GraphInterfaceSurface
): GraphInterfaceLibrarySignature {
  return GraphInterfaceLibrarySignature(
    contributionLibrarySignature(surface.contribution),
    surface.supertypeKeys,
    surface.supertypeDeclarations,
    surface.bindings.map { binding ->
      val data = binding.data
      GraphInterfaceBindingLibrarySignature(
        data.key,
        data.kind,
        data.scope,
        data.implementationName,
        data.consumedKey?.let(::contextKeyLibrarySignature),
        data.multibindingId,
        data.originClassId,
        data.replaces,
        data.contributionScopes,
        data.priority,
        data.priorityFromAnvilRank,
        data.dependencies.map(::contextKeyLibrarySignature),
        data.constructorDependencies.map(::contextKeyLibrarySignature),
        data.memberDependencies.map(::contextKeyLibrarySignature),
        data.memberInjectionOwnerIds,
        data.isSuspend,
        data.isAssisted,
        data.mapKeyValue,
        data.isClassContribution,
        data.allowEmpty,
        data.isGraphPrivate,
        binding.pointer.virtualFile,
        binding.pointer.element != null,
      )
    },
    surface.consumers.map(::consumerLibrarySignature),
    surface.extensionCreations,
    surface.extensionFactories.map(::extensionFactoryLibrarySignature),
    surface.defaultImplementations.map(::defaultImplementationLibrarySignature),
    surface.injectedMemberOwnerIds,
  )
}

private fun bindingLibrarySignature(binding: KaBinding): BindingLibrarySignature? {
  val isAssistedFactory = binding is KaBinding.AssistedFactory
  val isGeneratedContribution =
    binding is KaBinding.Provided && binding.isClassContribution ||
      binding is KaBinding.Alias && binding.isClassContribution
  val graphInput = binding as? KaBinding.BoundInstance
  val isFactoryInput =
    graphInput != null && (graphInput.isGraphInput || graphInput.isBindingContainerInput)
  if (!isAssistedFactory && !isGeneratedContribution && !isFactoryInput) return null
  val hasPriorityMetadata = binding.priority != Int.MIN_VALUE || binding.priorityFromAnvilRank
  val needsLibrarySignature =
    isFactoryInput || isAssistedFactory || binding.dependencies.isNotEmpty() || hasPriorityMetadata
  if (!needsLibrarySignature) return null
  return BindingLibrarySignature(
    binding.typeKey,
    binding.originClassId,
    binding.pointer.virtualFile,
    binding.pointer.element != null,
    isAssistedFactory,
    binding.scope,
    binding.contributionScopes,
    binding.priority,
    binding.priorityFromAnvilRank,
    binding.dependencies,
    binding.ownerGraphId,
    graphInput?.additionalOwnerGraphIds.orEmpty(),
    graphInput?.isGraphInput == true,
    graphInput?.isBindingContainerInput == true,
    (binding as? KaBinding.AssistedFactory)?.let(::assistedFactoryDefinitionSignature),
  )
}

/** Defaults and raw wrappers are metadata here, although contextual-key equality omits them. */
private fun assistedFactoryDefinitionSignature(
  binding: KaBinding.AssistedFactory
): AssistedFactoryDefinitionSignature {
  return AssistedFactoryDefinitionSignature(
    binding.typeKey,
    binding.originClassId,
    binding.pointer.virtualFile,
    binding.scope,
    binding.targetTypeKey,
    (binding.targetConstructorDependencies + binding.targetMemberDependencies).map(
      ::contextKeyLibrarySignature
    ),
    binding.targetConstructorDependencies.size,
    binding.memberInjectionOwnerIds,
    binding.factoryFunctionName,
    binding.factoryFunctionIsSuspend,
  )
}

private data class SourceLibraryShardSignature(
  val graphs: List<GraphLibrarySignature>,
  val contributions: List<ContributionLibrarySignature>,
  val consumers: List<ConsumerLibrarySignature>,
  val writtenBindingKeys: List<KaTypeKey>,
  val bindings: List<BindingLibrarySignature>,
  val factoryInputs: List<FactoryInputLibrarySignature>,
  val dynamicGraphs: List<DynamicGraphLibrarySignature>,
  val graphInterfaces: List<GraphInterfaceLibrarySignature>,
)

private data class DynamicGraphLibrarySignature(
  val id: DynamicGraphId,
  val targetGraph: GraphReference,
  val bindingKeys: Set<KaTypeKey>,
  val isFactory: Boolean,
  val pointerIsValid: Boolean,
)

private data class GraphLibrarySignature(
  val declarationId: GraphDeclarationId,
  val scopes: Set<ClassId>,
  val scopingAnnotations: Set<KaAnnotationSnapshot>,
  val excludes: Set<ClassId>,
  val bindingContainers: Set<ClassId>,
  val includedContainers: Set<KaTypeKey>,
  val includedDependencies: Set<KaTypeKey>,
  val isExtension: Boolean,
  val selfReferences: Set<GraphReference>,
  val supertypeKeys: Set<KaTypeKey>,
  val supertypeDeclarations: Set<GraphReference>,
  val extensionCreations: Set<GraphReference>,
  val extensionFactories: List<ExtensionFactoryLibrarySignature>,
  val defaultImplementations: List<GraphDefaultImplementationLibrarySignature>,
  val injectedMemberOwnerIds: Set<ClassId>,
  val daggerAnvilInteropEnabled: Boolean,
  val pointerIsValid: Boolean,
)

private data class ContributionLibrarySignature(
  val scopes: Set<ClassId>,
  val classId: ClassId?,
  val kind: ContributionEntry.Kind,
  val replaces: Set<ClassId>,
  val graphExtension: GraphReference?,
  val file: VirtualFile?,
  val pointerIsValid: Boolean,
)

private data class ConsumerLibrarySignature(
  val key: ContextKeyLibrarySignature,
  val classId: ClassId?,
  val multibindingId: String?,
  val graphId: GraphDeclarationId?,
  val includedContainerKey: KaTypeKey?,
  val file: VirtualFile?,
  val pointerIsValid: Boolean,
  val originClassId: ClassId?,
  val containerId: ClassId?,
  val contributionScopes: Set<ClassId>,
  val graphContribution: GraphReference?,
  val memberOwnerClassId: ClassId?,
  val graphRequestKind: ConsumerEntry.GraphRequestKind?,
  val isSuspend: Boolean,
  val isOptional: Boolean,
)

private data class ExtensionFactoryLibrarySignature(
  val factoryKey: KaTypeKey,
  val extensionKey: KaTypeKey,
  val extension: GraphReference,
  val file: VirtualFile?,
  val pointerIsValid: Boolean,
)

private data class GraphCallableLibrarySignature(
  val signature: GraphCallableSignature,
  val file: VirtualFile?,
  val pointerIsValid: Boolean,
)

private data class GraphDefaultImplementationLibrarySignature(
  val declaration: GraphCallableLibrarySignature,
  val overriddenDeclarations: List<GraphCallableLibrarySignature>,
  val isOptional: Boolean,
)

private data class GraphInterfaceLibrarySignature(
  val contribution: ContributionLibrarySignature,
  val supertypeKeys: Set<KaTypeKey>,
  val supertypeDeclarations: Set<GraphReference>,
  val bindings: List<GraphInterfaceBindingLibrarySignature>,
  val consumers: List<ConsumerLibrarySignature>,
  val extensionCreations: Set<GraphReference>,
  val extensionFactories: List<ExtensionFactoryLibrarySignature>,
  val defaultImplementations: List<GraphDefaultImplementationLibrarySignature>,
  val injectedMemberOwnerIds: Set<ClassId>,
)

private data class GraphInterfaceBindingLibrarySignature(
  val key: KaTypeKey,
  val kind: BindingData.Kind,
  val scope: KaAnnotationSnapshot?,
  val implementationName: String?,
  val consumedKey: ContextKeyLibrarySignature?,
  val multibindingId: String?,
  val originClassId: ClassId?,
  val replaces: Set<ClassId>,
  val contributionScopes: Set<ClassId>,
  val priority: Int,
  val priorityFromAnvilRank: Boolean,
  val dependencies: List<ContextKeyLibrarySignature>,
  val constructorDependencies: List<ContextKeyLibrarySignature>,
  val memberDependencies: List<ContextKeyLibrarySignature>,
  val memberOwnerIds: Set<ClassId>,
  val isSuspend: Boolean,
  val isAssisted: Boolean,
  val mapKeyValue: String?,
  val isClassContribution: Boolean,
  val allowEmpty: Boolean,
  val isGraphPrivate: Boolean,
  val file: VirtualFile?,
  val pointerIsValid: Boolean,
)

private data class BindingLibrarySignature(
  val key: KaTypeKey,
  val originClassId: ClassId?,
  val file: VirtualFile?,
  val pointerIsValid: Boolean,
  val isAssistedFactory: Boolean,
  val scope: KaAnnotationSnapshot?,
  val contributionScopes: Set<ClassId>,
  val priority: Int,
  val priorityFromAnvilRank: Boolean,
  val dependencies: List<KaContextualTypeKey>,
  val ownerGraphId: GraphDeclarationId?,
  val additionalOwnerGraphIds: Set<GraphDeclarationId>,
  val isGraphInput: Boolean,
  val isBindingContainerInput: Boolean,
  val factoryDefinition: AssistedFactoryDefinitionSignature?,
)

private fun contextKeyLibrarySignature(key: KaContextualTypeKey): ContextKeyLibrarySignature =
  ContextKeyLibrarySignature(key, key.hasDefault, key.rawType)

internal data class ContextKeyLibrarySignature(
  val key: KaContextualTypeKey,
  val hasDefault: Boolean,
  val rawType: KaTypeSnapshot?,
)

internal data class AssistedFactoryDefinitionSignature(
  val key: KaTypeKey,
  val originClassId: ClassId?,
  val file: VirtualFile?,
  val scope: KaAnnotationSnapshot?,
  val targetKey: KaTypeKey?,
  val dependencies: List<ContextKeyLibrarySignature>,
  val constructorDependencyCount: Int,
  val memberOwnerIds: Set<ClassId>,
  val functionName: String?,
  val functionIsSuspend: Boolean,
)

private data class FactoryInputLibrarySignature(
  val id: FactoryInputEntry.Id,
  val consumers: List<ConsumerLibrarySignature>,
  val writtenBindingKeys: List<KaTypeKey>,
  val bindings: List<BindingLibrarySignature>,
)

/** Stores immutable hash buckets so updates copy only the buckets containing changed entries. */
internal class PartitionedFileMap<V : Any>
private constructor(private val buckets: Array<Map<VirtualFile, V>?>) {

  operator fun contains(file: VirtualFile): Boolean {
    return buckets[bucketIndex(file)]?.containsKey(file) == true
  }

  operator fun get(file: VirtualFile): V? = buckets[bucketIndex(file)]?.get(file)

  /** Applies replacements and null removals while sharing unchanged buckets. */
  fun withChanges(changes: Map<VirtualFile, V?>): PartitionedFileMap<V> {
    if (changes.isEmpty()) return this

    val changedBuckets = mutableMapOf<Int, LinkedHashMap<VirtualFile, V>>()
    for ((file, value) in changes) {
      val index = bucketIndex(file)
      val bucket = changedBuckets.getOrPut(index) { LinkedHashMap(buckets[index].orEmpty()) }
      if (value == null) {
        bucket.remove(file)
      } else {
        bucket[file] = value
      }
    }
    val updatedBuckets = buckets.copyOf()
    for ((index, bucket) in changedBuckets) {
      updatedBuckets[index] = if (bucket.isEmpty()) null else bucket
    }
    return PartitionedFileMap(updatedBuckets)
  }

  /** Mixes high hash bits into the bucket selection. [BUCKET_COUNT] must be a power of two. */
  private fun bucketIndex(file: VirtualFile): Int {
    val hash = file.hashCode()
    return (hash xor (hash ushr 16)) and (BUCKET_COUNT - 1)
  }

  companion object {
    const val BUCKET_COUNT = 128

    fun <V : Any> empty(): PartitionedFileMap<V> =
      PartitionedFileMap(arrayOfNulls<Map<VirtualFile, V>>(BUCKET_COUNT))
  }
}

private fun buildFinalizedSourceLibrarySummary(
  project: Project,
  source: SourceAggregate,
  sourceIndex: BindingIndex,
): FinalizedSourceLibrarySummary {
  val consumerOwnership = ConsumerOwnershipBundle.build(sourceIndex)
  val sourceFactories =
    SourceAssistedFactoryPostProcessor(
        project,
        source.bindings,
        source.consumers,
        consumerOwnership,
      )
      .resolveInitial()
  val completeSource = source.withAddedFactories(sourceFactories.addedBindings)
  val inputs = completeSource.libraryInputs(project, sourceFactories, consumerOwnership)
  ProgressManager.checkCanceled()
  return FinalizedSourceLibrarySummary(inputs, consumerOwnership, sourceFactories)
}

internal data class FinalizedSourceLibrarySummary(
  val inputs: LibraryInputs,
  val consumerOwnership: ConsumerOwnershipBundle,
  val sourceFactories: SourceFactoryResolution,
)

private data class SourceAggregate(
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
  val graphs: List<KaGraphDeclaration>,
  val contributions: List<ContributionEntry>,
  val assistedSites: List<AssistedSite>,
  val bindingContainers: List<BindingContainerEntry>,
  val dynamicGraphs: List<DynamicGraphCall>,
) {
  fun withAddedFactories(factories: List<KaBinding.AssistedFactory>): SourceAggregate {
    if (factories.isEmpty()) return this
    return copy(bindings = bindings + factories)
  }

  fun libraryInputs(
    project: Project,
    sourceFactories: SourceFactoryResolution,
    consumerOwnership: ConsumerOwnershipBundle,
  ): LibraryInputs {
    val sourceFactoryUseSites = sourceFactories.factoryUseSites
    val scopeIds = linkedSetOf<ClassId>()
    val participatingModules = linkedSetOf<KaModule>()
    val injectRequests = linkedSetOf<LibraryInjectInput>()
    val seededFactoryUseSites =
      if (sourceFactoryUseSites.isEmpty()) null
      else {
        Collections.newSetFromMap(
          IdentityHashMap<Map<KaModule, SmartPsiElementPointer<out KtElement>>, Boolean>()
        )
      }

    fun addModule(element: PsiElement?): KaModule? {
      if (element !is KtElement) return null
      return KaModuleProvider.getModule(project, element, useSiteModule = null).also {
        participatingModules += it
      }
    }

    for (graph in graphs) {
      ProgressManager.checkCanceled()
      scopeIds += graph.scopeKeys
      addModule(graph.pointer.element)
    }
    for (dynamicGraph in dynamicGraphs) {
      ProgressManager.checkCanceled()
      addModule(dynamicGraph.pointer.element)
    }
    for (contribution in contributions) {
      ProgressManager.checkCanceled()
      scopeIds += contribution.scopeKeys
      addModule(contribution.pointer.element)
    }
    for (consumer in consumers) {
      ProgressManager.checkCanceled()
      val classId = consumer.typeClassId
      val containerOwners = consumerOwnership.owningGraphPointers(consumer)
      if (containerOwners == null) {
        val module = addModule(consumerOwnership.pointer(consumer).element) ?: continue
        if (classId == null || consumer.multibindingId != null) continue
        injectRequests += LibraryInjectInput(module, consumer.key, classId)
      } else {
        for (owner in containerOwners) {
          val module = addModule(owner.element) ?: continue
          if (classId == null || consumer.multibindingId != null) continue
          injectRequests += LibraryInjectInput(module, consumer.key, classId)
        }
      }
    }
    for (binding in bindings) {
      ProgressManager.checkCanceled()
      val hasAdditionalLibrarySeeds =
        binding is KaBinding.AssistedFactory ||
          binding is KaBinding.Provided && binding.isClassContribution ||
          binding is KaBinding.Alias && binding.isClassContribution
      if (!hasAdditionalLibrarySeeds || binding.dependencies.isEmpty()) continue
      if (binding is KaBinding.AssistedFactory) {
        val requestingUseSites = sourceFactoryUseSites[binding]
        if (requestingUseSites != null && seededFactoryUseSites?.add(requestingUseSites) == false) {
          continue
        }
        val requestingModules = requestingUseSites?.keys
        if (!requestingModules.isNullOrEmpty()) {
          participatingModules += requestingModules
          for (module in requestingModules) {
            for (dependency in binding.dependencies) {
              val key = dependency.typeKey
              val classId = key.type.classId ?: continue
              injectRequests += LibraryInjectInput(module, key, classId)
            }
          }
          continue
        }
      }
      val module = addModule(binding.pointer.element) ?: continue
      for (dependency in binding.dependencies) {
        val key = dependency.typeKey
        val classId = key.type.classId ?: continue
        injectRequests += LibraryInjectInput(module, key, classId)
      }
    }
    val definitions =
      linkedMapOf<SourceAssistedFactoryIdentity, AssistedFactoryDefinitionSignature>()
    for (binding in bindings) {
      if (binding !is KaBinding.AssistedFactory) continue
      val identity = binding.sourceFactoryIdentity() ?: continue
      definitions.putIfAbsent(identity, assistedFactoryDefinitionSignature(binding))
    }
    val budget = sourceFactories.budget
    return LibraryInputs(
      scopeIds,
      participatingModules,
      injectRequests,
      definitions.values.toList(),
      FactoryBudgetCacheInput(budget.writtenDepth, budget.writtenNodes, budget.writtenFactoryKeys),
    )
  }
}

private data class LibraryCacheKey(
  val fingerprint: IndexOptionsFingerprint,
  val rootsGeneration: Long,
  val inputs: LibraryInputs,
)

internal data class LibraryInputs(
  val scopeIds: Set<ClassId>,
  val participatingModules: Set<KaModule>,
  val requests: Set<LibraryInjectInput>,
  val sourceFactoryDefinitions: List<AssistedFactoryDefinitionSignature>,
  val factoryBudget: FactoryBudgetCacheInput,
)

internal data class FactoryBudgetCacheInput(
  val writtenDepth: Int,
  val writtenNodes: Int,
  val writtenFactoryKeys: Set<KaTypeKey>,
)

internal data class LibraryInjectInput(
  val module: KaModule,
  val key: KaTypeKey,
  val classId: ClassId,
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

/** Parsed compiler-option values that can actually change an IDE declaration snapshot. */
internal class IndexOptionsFingerprint(val options: MetroOptions) {
  private val annotationGroups =
    listOf(
      options.dependencyGraphAnnotations,
      options.dependencyGraphFactoryAnnotations,
      options.graphExtensionAnnotations,
      options.graphExtensionFactoryAnnotations,
      options.injectAnnotations,
      options.assistedInjectAnnotations,
      options.assistedAnnotations,
      options.assistedFactoryAnnotations,
      options.contributionProviderExclusionAnnotations,
      options.providesAnnotations,
      options.bindsAnnotations,
      options.multibindsAnnotations,
      options.allContributesAnnotations,
      options.contributesBindingAnnotations,
      options.contributesIntoSetAnnotations,
      options.customContributesIntoSetAnnotations,
      options.contributesIntoMapAnnotations,
      options.bindingContainerAnnotations,
      options.intoSetAnnotations,
      options.elementsIntoSetAnnotations,
      options.intoMapAnnotations,
      options.mapKeyAnnotations,
      options.qualifierAnnotations,
      options.scopeAnnotations,
      options.originAnnotations,
      options.optionalBindingAnnotations,
    )

  private val wrapperGroups =
    listOf(
      options.providerTypes,
      options.lazyTypes,
      options.suspendProviderModelingTypes,
      options.suspendLazyTypes,
    )

  private val flags =
    listOf(
      options.contributesAsInject,
      options.generateContributionProviders,
      options.enableCircuitCodegen,
      options.enableDaggerRuntimeInterop,
      options.enableDaggerAnvilInterop,
      options.enableTopLevelFunctionInjection,
      options.enableSuspendProviders,
      options.enableFunctionProviders,
      options.shrinkUnusedBindings,
    )

  private val optionalBindingBehavior = options.optionalBindingBehavior

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is IndexOptionsFingerprint) return false
    return annotationGroups == other.annotationGroups &&
      wrapperGroups == other.wrapperGroups &&
      flags == other.flags &&
      optionalBindingBehavior == other.optionalBindingBehavior
  }

  override fun hashCode(): Int {
    var result = annotationGroups.hashCode()
    result = 31 * result + wrapperGroups.hashCode()
    result = 31 * result + flags.hashCode()
    result = 31 * result + optionalBindingBehavior.hashCode()
    return result
  }
}

internal fun sweepAnnotationIds(options: MetroOptions): Set<ClassId> {
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
    addAll(bindsOptionalOfAnnotations(options))
    add(CircuitClassIds.CircuitInject)
  }
}

/**
 * Includes local import aliases without resolving annotations or starting an Analysis API session.
 */
internal fun KtFile.annotationShortNamesIncludingAliases(annotationIds: Set<ClassId>): Set<String> {
  val names = mutableSetOf<String>()
  for (annotationId in annotationIds) {
    ProgressManager.checkCanceled()
    names += annotationId.shortClassName.asString()
  }
  for (directive in importDirectives) {
    ProgressManager.checkCanceled()
    val alias = directive.aliasName ?: continue
    val importedName = directive.importedFqName ?: continue
    for (annotationId in annotationIds) {
      ProgressManager.checkCanceled()
      if (annotationId.asSingleFqName() == importedName) {
        names += alias
        break
      }
    }
  }
  return names
}
