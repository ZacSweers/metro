// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.idea.MetroIdeModuleState
import dev.zacsweers.metro.idea.MetroIdeProjectService
import dev.zacsweers.metro.idea.SOURCE_SCAN_POOL_SIZE_RANGE
import dev.zacsweers.metro.idea.index.ConsumerOwnershipBundle
import dev.zacsweers.metro.idea.index.DYNAMIC_GRAPH_CALLABLES
import dev.zacsweers.metro.idea.index.FileShard
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.index.LibraryGraphDeclarations
import dev.zacsweers.metro.idea.index.LibraryGraphDiscovery
import dev.zacsweers.metro.idea.index.LibraryIndexPostProcessor
import dev.zacsweers.metro.idea.index.SourceClassBindingPostProcessor
import dev.zacsweers.metro.idea.index.SourceClassDependencies
import dev.zacsweers.metro.idea.metroIdeState
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingIndexBuilder
import dev.zacsweers.metro.idea.model.ClassBindingIdentity
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.IndexGenerationToken
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.tracing.IdeTraceOperation
import dev.zacsweers.metro.idea.tracing.phase
import dev.zacsweers.metro.idea.tracing.phaseSuspend
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.idea.stubindex.KotlinAnnotationsIndex
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Constructs source and dependency snapshots for one resolution coordinator.
 *
 * Calls are serialized by that coordinator. Preparation uses separate smart reads for source files
 * and later capture stages; sealing uses captured data outside read access. The builder owns
 * reusable binary shards and never accepts or publishes a generation. The callbacks keep
 * invalidation fingerprints and presentation anchors with the coordinator that owns their lifetime.
 */
internal class ResolutionSnapshotBuilder(
  private val project: Project,
  /** Observes completed shard reads inside read access; concurrent scans can invoke it together. */
  private val onShardRead: (KtFile, FileShard) -> Unit = { _, _ -> },
  /** Announces the attempt's source coverage outside read access before scanning begins. */
  private val onSourceFilesScheduled: (Set<VirtualFile>) -> Unit = {},
  private val sourceScanPoolSize: () -> Int = { 1 },
  private val captureSourceFingerprints: (KtFile, FileShard) -> Map<VirtualFile, String> = { _, _ ->
    emptyMap()
  },
  private val acceptSourceFingerprints: (Map<VirtualFile, String>) -> Unit = {},
  private val captureResolutionInputs: (BindingIndexBuilder, Set<VirtualFile>) -> Unit,
) {
  private val fileShards = SourceFileShardCache()
  private val sourceScanner =
    SourceSnapshotScanner(
      project,
      fileShards,
      onShardRead,
      ::containsRelevantAnnotation,
      captureSourceFingerprints,
      acceptSourceFingerprints,
    )
  private var cachedSourceSummary: CachedSourceLibrarySummary? = null
  private val libraryShards =
    object : LinkedHashMap<LibraryCacheKey, LibraryShard>(8, 0.75f, true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<LibraryCacheKey, LibraryShard>
      ): Boolean = size > MAX_CACHED_LIBRARY_SHARDS
    }

  /** Runs outside read access and retains completed stages while a later read is retried. */
  suspend fun prepare(
    previous: SourceSnapshot?,
    inputs: IndexInputs,
    targets: List<ResolutionSnapshotTarget>,
    pending: SourceSnapshotChanges,
    coldSweep: Boolean,
    progress: IndexBuildProgressReporter,
    generationToken: IndexGenerationToken,
    trace: IdeTraceOperation? = null,
    checkCurrent: () -> Unit,
  ): PreparedResolutionSnapshot {
    currentCoroutineContext().ensureActive()
    checkCurrent()
    val parallelism = sourceScanPoolSize().coerceIn(SOURCE_SCAN_POOL_SIZE_RANGE)
    if (targets.isEmpty()) {
      return PreparedResolutionSnapshot(
        source = null,
        inputs = inputs,
        buildersByKey = emptyMap(),
        keysByModule = emptyMap(),
      )
    }

    val collectedSource =
      trace.phaseSuspend("source.scan") { sourceTrace ->
        if (coldSweep) {
          coldSweep(
            targets.first().key.fingerprint.options,
            inputs,
            pending,
            progress,
            parallelism,
            sourceTrace,
            checkCurrent,
          )
        } else {
          incremental(
            checkNotNull(previous),
            inputs,
            pending,
            progress,
            parallelism,
            sourceTrace,
            checkCurrent,
          )
        }
      }
    checkCurrent()

    progress.phase(IndexBuildPhase.COMBINING_DECLARATIONS)
    val rawSource =
      trace.phaseSuspend("source.aggregate") { phase ->
        readSnapshotStage(project, checkCurrent, phase) {
          aggregateSource(collectedSource, progress)
        }
      }
    val summary =
      trace.phaseSuspend("source.resolveClasses") { phase ->
        val executor =
          SnapshotReadExecutor(
            project,
            parallelism,
            progress,
            IndexBuildPhase.RESOLVING_CLASS_BINDINGS,
            phase,
            checkCurrent,
          )
        sourceLibrarySummary(collectedSource, rawSource, pending, executor, phase)
      }
    val finalizedSource = collectedSource.withLibrarySummary(summary.withoutReadContexts())
    val classDependencies =
      SourceClassDependencies.Builder(SmartPointerManager.getInstance(project))
    classDependencies.include(summary.sourceClasses.dependencies)
    val source = rawSource.withAddedClassBindings(summary.sourceClasses.addedBindings)
    val buildersByKey = linkedMapOf<SnapshotKey, BindingIndexBuilder>()
    val keysByModule = linkedMapOf<Module, SnapshotKey>()
    val declarationSignatureFiles = finalizedSource.shardOrder.toSet()
    for ((key, modules) in targets) {
      currentCoroutineContext().ensureActive()
      checkCurrent()
      val library =
        if (key.resolveFromLibraries) {
          trace.phaseSuspend("library.resolve") { phase ->
            libraryShardFor(
              key.fingerprint,
              inputs.roots,
              source,
              summary,
              progress,
              parallelism,
              phase,
              checkCurrent,
            )
          }
        } else {
          LibraryShard.EMPTY
        }
      classDependencies.include(library.sourceDependencies)
      progress.phase(IndexBuildPhase.BUILDING_GRAPH_INDEX)
      val indexBuilder =
        trace.phaseSuspend("index.captureInputs") { phase ->
          readSnapshotStage(project, checkCurrent, phase) {
            val composedSource =
              source
                .withLibraryGraphs(library.graphDeclarations)
                .withGraphInterfaces(library.graphInterfaces)
            // A retried capture starts with a fresh builder so canceled pointer capture leaves no
            // state.
            val builder =
              BindingIndexBuilder(generationToken).apply {
                bindings += composedSource.bindings + library.bindings
                consumers += composedSource.consumers
                graphs += composedSource.graphs
                contributions += source.contributions + library.contributions
                assistedSites += source.assistedSites
                bindingContainers += source.bindingContainers
                incompleteClassBindings +=
                  if (key.resolveFromLibraries) library.incompleteBindings
                  else summary.sourceClasses.incompleteBindings
                dynamicGraphs += source.dynamicGraphs
              }
            captureResolutionInputs(builder, declarationSignatureFiles)
            builder
          }
        }
      buildersByKey[key] = indexBuilder
      for (module in modules) {
        keysByModule[module] = key
      }
    }
    currentCoroutineContext().ensureActive()
    checkCurrent()
    val dependencies = classDependencies.build()
    readSnapshotStage(project, checkCurrent, trace) {
      if (!dependencies.isCurrent()) {
        throw SourceSnapshotConflictException()
      }
    }
    return PreparedResolutionSnapshot(
      source = finalizedSource.withClassBindingDependencies(dependencies.withoutReadContexts()),
      inputs = inputs,
      buildersByKey = buildersByKey,
      keysByModule = keysByModule,
    )
  }

  /** Saves only completed immutable class results; each attempt still owns fresh index builders. */
  private suspend fun sourceLibrarySummary(
    collected: SourceSnapshot,
    source: SourceAggregate,
    pending: SourceSnapshotChanges,
    executor: SnapshotReadExecutor,
    trace: IdeTraceOperation?,
  ): FinalizedSourceLibrarySummary {
    val retained = executor.read {
      val snapshot = collected.librarySummary
      val cached = cachedSourceSummary
      when {
        snapshot != null && snapshot.sourceClasses.dependencies.isCurrent() -> snapshot
        cached != null && cached.matches(collected, pending.invalidationRevision) -> cached.summary
        else -> null
      }
    }
    if (retained != null) {
      trace?.attribute("cache", "reused")
      return retained
    }
    trace?.attribute("cache", "miss")
    val summary = executor.run {
      val ownershipIndex =
        trace.phaseSuspend("source.buildOwnershipIndex") {
          executor.read { buildSourceOwnershipIndex(source) }
        }
      val result =
        buildFinalizedSourceLibrarySummary(project, source, ownershipIndex, executor, trace)
      executor.read {
        if (!result.sourceClasses.dependencies.isCurrent()) {
          throw SourceSnapshotConflictException()
        }
      }
      result
    }
    cachedSourceSummary =
      CachedSourceLibrarySummary(
        collected,
        pending.invalidationRevision,
        summary.withoutReadContexts(),
      )
    return summary
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

  private suspend fun coldSweep(
    options: MetroOptions,
    inputs: IndexInputs,
    pending: SourceSnapshotChanges,
    progress: IndexBuildProgressReporter,
    parallelism: Int,
    trace: IdeTraceOperation?,
    checkCurrent: () -> Unit,
  ): SourceSnapshot {
    progress.phase(IndexBuildPhase.DISCOVERING_SOURCE_FILES)
    val discovery =
      trace.phaseSuspend("source.discover") { phase ->
        readSnapshotStage(project, checkCurrent, phase) {
          val annotationIds = projectSweepAnnotationIds(options)
          val shortNames = annotationIds.mapToSet { it.shortClassName.asString() }
          SourceFileDiscovery(
            candidateFiles(annotationIds, shortNames),
            shortNames,
            moduleFingerprints(),
          )
        }
      }
    onSourceFilesScheduled(
      buildSet {
        addAll(discovery.files)
        addAll(pending.requested)
      }
    )
    return sourceScanner.scan(
      previous = null,
      files = discovery.files,
      inputs = inputs,
      moduleFingerprints = discovery.moduleFingerprints,
      shortNames = discovery.shortNames,
      pending = pending,
      progress = progress,
      trace = trace,
      parallelism = parallelism,
      checkCurrent = checkCurrent,
    )
  }

  private suspend fun incremental(
    prev: SourceSnapshot,
    inputs: IndexInputs,
    pending: SourceSnapshotChanges,
    progress: IndexBuildProgressReporter,
    parallelism: Int,
    trace: IdeTraceOperation?,
    checkCurrent: () -> Unit,
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
    onSourceFilesScheduled(
      buildSet {
        addAll(prev.shardOrder)
        addAll(dirty)
        addAll(pending.requested)
      }
    )
    if (dirty.isEmpty() && pending.requested.isEmpty()) {
      // Output-only compiler-option changes update inputs without touching any shard.
      trace?.attribute("cache", "unchanged")
      return if (prev.inputs == inputs) prev else prev.withInputs(inputs)
    }
    return sourceScanner.scan(
      previous = prev,
      files = dirty,
      inputs = inputs,
      moduleFingerprints = prev.moduleFingerprints,
      shortNames = prev.shortNames,
      pending = pending,
      progress = progress,
      trace = trace,
      parallelism = parallelism,
      checkCurrent = checkCurrent,
    )
  }

  /** Discovery and class lookups retain completed captures across independent read retries. */
  private suspend fun libraryShardFor(
    fingerprint: IndexOptionsFingerprint,
    rootsGeneration: Long,
    source: SourceAggregate,
    summary: FinalizedSourceLibrarySummary,
    progress: IndexBuildProgressReporter,
    parallelism: Int,
    trace: IdeTraceOperation?,
    checkCurrent: () -> Unit,
  ): LibraryShard {
    val key =
      LibraryCacheKey(fingerprint, rootsGeneration, summary.inputs, summary.consumerOwnership)
    val cached = libraryShards[key]
    if (cached != null) {
      val current =
        readSnapshotStage(project, checkCurrent, trace) {
          cached.sourceDependencies.isCurrent()
        }
      if (current) {
        trace?.attribute("cache", "reused")
        return cached
      }
      trace?.attribute("sourceDependenciesChanged", true)
    }
    trace?.attribute("cache", "miss")

    val metadata =
      trace.phaseSuspend("library.discoverMetadata") { phase ->
        SnapshotReadExecutor(
            project,
            parallelism,
            progress,
            IndexBuildPhase.READING_DEPENDENCY_METADATA,
            phase,
            checkCurrent,
          )
          .run { executor ->
            val discovery = executor.read {
              LibraryGraphDiscovery(
                project,
                fingerprint.options,
                source.graphs,
                source.contributions,
                source.consumers,
                source.graphInterfaceSurfaces,
              )
            }
            discovery.discover(executor)
          }
      }
    val shard =
      trace.phaseSuspend("library.resolveClasses") { phase ->
        SnapshotReadExecutor(
            project,
            parallelism,
            progress,
            IndexBuildPhase.RESOLVING_LIBRARY_CLASSES,
            phase,
            checkCurrent,
          )
          .run { executor ->
            val hints = metadata.contributions
            val sourceWithGraphs = executor.read { source.withLibraryGraphs(metadata.declarations) }
            val interfaces = executor.read {
              combineGraphInterfaceOverlays(
                graphInterfaceOverlay(source.graphInterfaceSurfaces, metadata.declarations.graphs),
                graphInterfaceOverlay(hints.graphInterfaces, sourceWithGraphs.graphs),
              )
            }
            val sourceWithInterfaces = executor.read {
              sourceWithGraphs.withGraphInterfaces(interfaces)
            }
            val contributions = source.contributions + hints.contributions
            val composed =
              sourceWithInterfaces.copy(
                bindings = sourceWithInterfaces.bindings + hints.bindings,
                contributions = contributions,
              )
            val needsOwnership = !interfaces.isEmpty || !metadata.declarations.isEmpty
            val ownership =
              if (needsOwnership) {
                executor.read { ConsumerOwnershipBundle.build(buildSourceOwnershipIndex(composed)) }
              } else {
                summary.consumerOwnership
              }
            // New interface requests need their exact source graph owner before class lookup.
            // Existing source requests stay memoized in the previous expansion state.
            val initialClasses =
              if (needsOwnership) {
                val processor = executor.read {
                  SourceClassBindingPostProcessor(
                    project,
                    sourceWithInterfaces.bindings,
                    sourceWithInterfaces.consumers,
                    ownership,
                    summary.sourceClasses,
                  )
                }
                processor.resolveInitial(executor)
              } else {
                summary.sourceClasses
              }
            val bindings = composed.bindings.toMutableList()
            val baseBindingCount = bindings.size
            bindings += initialClasses.addedBindings.drop(summary.sourceClasses.addedBindings.size)
            val processor = executor.read {
              LibraryIndexPostProcessor(
                project,
                fingerprint.options,
                bindings,
                composed.consumers,
                composed.graphs,
                contributions,
                initialClasses.classUseSites,
                ownership,
                initialClasses,
              )
            }
            val classResolution = processor.postProcess(executor)
            trace?.attribute("bindings.added", bindings.size - baseBindingCount)
            trace?.attribute(
              "bindings.incomplete",
              classResolution.incompleteBindings.values.sumOf { it.size },
            )
            val dependencies =
              SourceClassDependencies.Builder(SmartPointerManager.getInstance(project))
            dependencies.include(metadata.sourceDependencies)
            dependencies.include(classResolution.dependencies)
            val capturedDependencies = dependencies.build()
            executor.read {
              if (!capturedDependencies.isCurrent()) {
                throw SourceSnapshotConflictException()
              }
            }
            LibraryShard(
              hints.bindings + bindings.drop(baseBindingCount),
              hints.contributions,
              interfaces,
              metadata.declarations,
              classResolution.incompleteBindings,
              capturedDependencies,
            )
          }
      }
    // Request contexts protect this capture. Semantic inputs govern future cache reuse, while
    // source declarations read during expansion remain dependencies of the cached bindings.
    libraryShards[key] =
      shard.copy(sourceDependencies = shard.sourceDependencies.withoutReadContexts())
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
  private fun candidateFiles(
    annotationIds: Set<ClassId>,
    shortNames: Set<String>,
  ): Set<VirtualFile> {
    val searchScope = GlobalSearchScope.projectScope(project)
    val files = LinkedHashSet<VirtualFile>()
    for (shortName in shortNames.sorted()) {
      ProgressManager.checkCanceled()
      for (entry in KotlinAnnotationsIndex[shortName, project, searchScope]) {
        ProgressManager.checkCanceled()
        entry.containingKtFile.virtualFile?.let(files::add)
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
          (element.containingFile as? KtFile)?.virtualFile?.let(files::add)
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
            file.virtualFile?.let(files::add)
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

  /** Drops stale library data without changing the published presentation generation. */
  fun evictLibraryShards(
    currentRoots: Long,
    activeFingerprints: Set<IndexOptionsFingerprint>? = null,
  ) {
    cachedSourceSummary = null
    libraryShards.keys.removeIf { key ->
      key.rootsGeneration != currentRoots ||
        (activeFingerprints != null && key.fingerprint !in activeFingerprints)
    }
  }

  /** Disabling dependency resolution discards its reusable binary shards. */
  fun clearLibraryShards() {
    cachedSourceSummary = null
    libraryShards.clear()
  }

  private companion object {
    const val MAX_CACHED_LIBRARY_SHARDS = 8
  }
}

/** Discovery retains file identities so source reads can resume without keeping PSI trees alive. */
private data class SourceFileDiscovery(
  val files: Set<VirtualFile>,
  val shortNames: Set<String>,
  val moduleFingerprints: Map<Module, IndexOptionsFingerprint>,
)

private data class LibraryCacheKey(
  val fingerprint: IndexOptionsFingerprint,
  val rootsGeneration: Long,
  val inputs: LibraryInputs,
  /** Reused with equal source signatures, including graph excludes and default overrides. */
  val sourceOwnership: ConsumerOwnershipBundle,
)

private data class LibraryShard(
  val bindings: List<KaBinding>,
  val contributions: List<ContributionEntry>,
  val graphInterfaces: GraphInterfaceOverlay = GraphInterfaceOverlay.EMPTY,
  val graphDeclarations: LibraryGraphDeclarations = LibraryGraphDeclarations.EMPTY,
  val incompleteBindings: Map<KaModule, Map<ClassBindingIdentity, String>> = emptyMap(),
  val sourceDependencies: SourceClassDependencies = SourceClassDependencies.EMPTY,
) {
  companion object {
    val EMPTY = LibraryShard(emptyList(), emptyList())
  }
}
