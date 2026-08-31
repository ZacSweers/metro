// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.facet.Facet
import com.intellij.facet.FacetManager
import com.intellij.facet.FacetManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
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
import dev.zacsweers.metro.idea.MetroSettings
import dev.zacsweers.metro.idea.checkCanceledEvery
import dev.zacsweers.metro.idea.metroIdeState
import dev.zacsweers.metro.idea.model.AssistedSite
import dev.zacsweers.metro.idea.model.BindingContainerEntry
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingIndexBuilder
import dev.zacsweers.metro.idea.model.BindingIndexModuleView
import dev.zacsweers.metro.idea.model.BindingIndexResolutionInputs
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.DynamicGraphCall
import dev.zacsweers.metro.idea.model.DynamicGraphId
import dev.zacsweers.metro.idea.model.FileOrdinal
import dev.zacsweers.metro.idea.model.FileOrdinalTable
import dev.zacsweers.metro.idea.model.GraphCallableReference
import dev.zacsweers.metro.idea.model.GraphCallableSignature
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.GraphDefaultImplementation
import dev.zacsweers.metro.idea.model.GraphExtensionFactoryAccessor
import dev.zacsweers.metro.idea.model.GraphReference
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import dev.zacsweers.metro.idea.model.ModuleViewId
import dev.zacsweers.metro.idea.model.SourceAssistedFactoryIdentity
import dev.zacsweers.metro.idea.model.sourcePointerIdentity
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScope
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsListener
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker
import org.jetbrains.kotlin.idea.stubindex.KotlinAnnotationsIndex
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias

/**
 * Shared resolution service powering Metro's editor decorations, graph browser, and validation.
 *
 * A cold snapshot discovers candidate Kotlin files through stub indexes. Later PSI changes rebuild
 * only the changed file and shards that explicitly depend on it. Binary declarations live in a
 * separate cache so unrelated source edits do not repeat classpath analysis.
 */
@Service(Service.Level.PROJECT)
class MetroResolutionService(
  private val project: Project,
  private val scope: CoroutineScope,
) : Disposable {
  // Project-wide indexes are deduped by options that actually affect IDE extraction. Gradle emits
  // module-specific report/trace destinations, but those paths do not change declaration semantics.
  private val snapshots: MutableMap<SnapshotKey, IndexSnapshot> =
    Collections.synchronizedMap(
      object : LinkedHashMap<SnapshotKey, IndexSnapshot>(8, 0.75f, true) {
        override fun removeEldestEntry(
          eldest: MutableMap.MutableEntry<SnapshotKey, IndexSnapshot>
        ): Boolean = size > MAX_CACHED_INDEXES
      }
    )

  private val libraryShards: MutableMap<LibraryCacheKey, LibraryShard> =
    Collections.synchronizedMap(
      object : LinkedHashMap<LibraryCacheKey, LibraryShard>(8, 0.75f, true) {
        override fun removeEldestEntry(
          eldest: MutableMap.MutableEntry<LibraryCacheKey, LibraryShard>
        ): Boolean = size > MAX_CACHED_INDEXES
      }
    )

  private val listeners = Collections.newSetFromMap(ConcurrentHashMap<() -> Unit, Boolean>())
  private val indexBuildProgressListeners =
    Collections.newSetFromMap(ConcurrentHashMap<(IndexBuildProgress?) -> Unit, Boolean>())
  private val indexBuildProgress = AtomicReference<IndexBuildProgress?>(null)
  /** Pre-change shared declarations, so broad PSI events do not invalidate unrelated edits. */
  private val sharedDeclarationFingerprints = ConcurrentHashMap<VirtualFile, String>()
  private val invalidationPending = AtomicBoolean()
  /** The first source state in a batch of roots, facet, or compiler-settings callbacks. */
  private val pendingProjectInputs = AtomicReference<PendingProjectInputs?>(null)
  private val graphBrowserActivated = AtomicBoolean()
  private val disposed = AtomicBoolean()

  /**
   * The pending-invalidation ledger. Every mutation replaces the whole immutable value, so a
   * builder can drain it at the start of a pass and publish results with one compare-and-set. Any
   * concurrent invalidation changes the reference and fails the publish, forcing a re-drain.
   * Builders always run inside read actions, so PSI itself cannot change mid-pass. The ledger is
   * the only state other threads can move underneath a build.
   */
  private val invalidations = AtomicReference(Invalidations())

  /** The last fully built source view. Published atomically after a successful drain. */
  private val sourceSnapshot = AtomicReference<SourceSnapshot?>(null)

  /** Keys whose background builds were requested from the EDT, drained by [buildWorker]. */
  private val pendingBuilds = ConcurrentHashMap<SnapshotKey, Module>()
  private val buildSignal = Channel<Unit>(Channel.CONFLATED)

  private val lastResolveFromLibraries =
    AtomicBoolean(MetroSettings.getInstance(project).state.resolveFromLibraries)
  private val fingerprintsByModuleState: MutableMap<MetroIdeModuleState, IndexOptionsFingerprint> =
    Collections.synchronizedMap(
      object : LinkedHashMap<MetroIdeModuleState, IndexOptionsFingerprint>(16, 0.75f, true) {
        override fun removeEldestEntry(
          eldest: MutableMap.MutableEntry<MetroIdeModuleState, IndexOptionsFingerprint>
        ): Boolean = size > MAX_CACHED_OPTION_FINGERPRINTS
      }
    )

  init {
    PsiManager.getInstance(project)
      .addPsiTreeChangeListener(
        object : PsiTreeChangeAdapter() {
          override fun beforeChildRemoval(event: PsiTreeChangeEvent) =
            psiChanged(event, structuralChange = isFileStructureChange(event))

          override fun beforeChildMovement(event: PsiTreeChangeEvent) =
            psiChanged(event, structuralChange = isFileStructureChange(event))

          override fun beforePropertyChange(event: PsiTreeChangeEvent) =
            psiChanged(event, structuralChange = isFileStructureChange(event))

          override fun beforeChildReplacement(event: PsiTreeChangeEvent) = psiChanged(event)

          override fun beforeChildrenChange(event: PsiTreeChangeEvent) = psiChanged(event)

          override fun childAdded(event: PsiTreeChangeEvent) =
            psiChanged(event, structuralChange = isFileStructureChange(event))

          override fun childRemoved(event: PsiTreeChangeEvent) =
            psiChanged(event, structuralChange = isFileStructureChange(event))

          override fun childReplaced(event: PsiTreeChangeEvent) = psiChanged(event)

          override fun childrenChanged(event: PsiTreeChangeEvent) = psiChanged(event)

          override fun childMoved(event: PsiTreeChangeEvent) =
            psiChanged(event, structuralChange = isFileStructureChange(event))

          override fun propertyChanged(event: PsiTreeChangeEvent) =
            psiChanged(event, structuralChange = isFileStructureChange(event))
        },
        this,
      )
    val connection = project.messageBus.connect(this)
    connection.subscribe(
      ModuleRootListener.TOPIC,
      object : ModuleRootListener {
        override fun rootsChanged(event: ModuleRootEvent) = projectInputsChanged()
      },
    )
    connection.subscribe(
      FacetManager.FACETS_TOPIC,
      object : FacetManagerListener {
        override fun facetAdded(facet: Facet<*>) = projectInputsChanged()

        override fun facetRemoved(facet: Facet<*>) = projectInputsChanged()

        override fun facetConfigurationChanged(facet: Facet<*>) = projectInputsChanged()
      },
    )
    connection.subscribe(
      KotlinCompilerSettingsListener.TOPIC,
      object : KotlinCompilerSettingsListener {
        override fun <T> settingsChanged(oldSettings: T?, newSettings: T?) = projectInputsChanged()
      },
    )
    scope.launch { buildWorker() }
  }

  /** Drains UI-requested background builds one at a time on the service scope. */
  private suspend fun buildWorker() {
    try {
      for (unused in buildSignal) {
        while (true) {
          val (key, module) = pendingBuilds.entries.firstOrNull() ?: break
          pendingBuilds.remove(key, module)
          val progress = IndexBuildProgressReporter(::publishIndexBuildProgress)
          progress.phase(IndexBuildPhase.QUEUED)
          val built =
            try {
              retryCancelledIndexBuild {
                smartReadAction(project) { buildCurrentIndex(module, key, progress) }
              }
            } catch (exception: CancellationException) {
              throw exception
            } catch (failure: Throwable) {
              // The worker must survive analysis failures or every future EDT-scheduled build
              // would silently stop. Requesters reschedule on their next query.
              logger<MetroResolutionService>()
                .warn("Metro index build failed for ${module.name}", failure)
              continue
            }
          if (built === BindingIndex.EMPTY) {
            continue
          }
          withContext(Dispatchers.EDT) {
            val current = snapshots[key]
            if (!project.isDisposed && current?.index === built) {
              notifyListeners(restartDaemon = true)
            }
          }
        }
        publishIndexBuildProgress(null)
      }
    } finally {
      publishIndexBuildProgress(null)
    }
  }

  /** Returns the current index for [element]'s module, or an empty index when Metro is inactive. */
  internal fun index(element: PsiElement): BindingIndex {
    val file = element as? KtFile ?: element.containingFile as? KtFile
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return BindingIndex.EMPTY
    if (file != null) enrollRequestedFile(file)
    return index(module)
  }

  /** Returns a current cached index without scheduling or performing analysis. */
  internal fun cachedIndex(element: PsiElement): BindingIndex {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return BindingIndex.EMPTY
    return index(module, IndexRequestMode.CACHE_ONLY)
  }

  /** Returns the distinct current indexes that can contain an exact Find Usages target. */
  internal fun usageIndexes(element: PsiElement): List<BindingIndex> {
    val module = ModuleUtilCore.findModuleForPsiElement(element)
    if (module != null) return index(element).asUsageIndexList()
    return distinctUsageIndexes { index(it) }
  }

  /**
   * Cache-only counterpart to [usageIndexes] used before deciding whether a usage build is needed.
   */
  internal fun cachedUsageIndexes(element: PsiElement): List<BindingIndex> {
    val module = ModuleUtilCore.findModuleForPsiElement(element)
    if (module != null) return cachedIndex(element).asUsageIndexList()
    return distinctUsageIndexes { index(it, IndexRequestMode.CACHE_ONLY) }
  }

  private fun distinctUsageIndexes(indexForModule: (Module) -> BindingIndex): List<BindingIndex> {
    val result = mutableListOf<BindingIndex>()
    for (module in ModuleManager.getInstance(project).modules) {
      ProgressManager.checkCanceled()
      val index = indexForModule(module)
      if (index !== BindingIndex.EMPTY && result.none { it === index }) result += index
    }
    return result
  }

  private fun BindingIndex.asUsageIndexList(): List<BindingIndex> {
    return if (this === BindingIndex.EMPTY) emptyList() else listOf(this)
  }

  /**
   * Returns a current project snapshot for [module]. Production EDT callers never perform Kotlin
   * analysis: they trigger a coalesced smart-mode build and receive an empty index until it lands.
   * Background highlighting and the platform's synchronous unit-test fixtures build immediately.
   */
  internal fun index(module: Module): BindingIndex {
    val application = ApplicationManager.getApplication()
    val requestMode =
      if (application.isDispatchThread && !application.isUnitTestMode) {
        IndexRequestMode.BACKGROUND
      } else {
        IndexRequestMode.SYNCHRONOUS
      }
    return index(module, requestMode)
  }

  /** Returns a cached graph-browser index, building in the background only after first use. */
  internal fun indexForToolWindow(module: Module): BindingIndex {
    val requestMode =
      if (graphBrowserActivated.get()) {
        IndexRequestMode.BACKGROUND
      } else {
        IndexRequestMode.CACHE_ONLY
      }
    val index = index(module, requestMode)
    if (index !== BindingIndex.EMPTY && graphBrowserActivated.compareAndSet(false, true)) {
      // currentIndexes() may have skipped earlier modules while the browser was inactive. Ask the
      // tree to make one active pass so those modules can schedule their own snapshots.
      scheduleInvalidationNotification()
    }
    return index
  }

  internal val isGraphBrowserActivated: Boolean
    get() = graphBrowserActivated.get()

  internal fun activateGraphBrowser() {
    graphBrowserActivated.set(true)
  }

  @TestOnly
  internal fun resetGraphBrowserActivation() {
    graphBrowserActivated.set(false)
  }

  private fun index(module: Module, requestMode: IndexRequestMode): BindingIndex {
    val moduleState = project.service<MetroIdeProjectService>().state(module)
    if (!moduleState.isEnabled) return BindingIndex.EMPTY

    val fingerprint = fingerprintFor(moduleState)
    val key =
      SnapshotKey(fingerprint, MetroSettings.getInstance(project).state.resolveFromLibraries)
    val inputs = currentInputs()
    val sourceInputs = sourceSnapshot.get()?.inputs
    val compilerSettingsChanged = sourceInputs?.compilerSettings != inputs.compilerSettings
    if (!compilerSettingsChanged && sourceInputs?.roots == inputs.roots) {
      snapshots[key]
        ?.takeIf { it.matches(invalidations.get().generation, inputs.roots) }
        ?.let {
          return it.index
        }
    }

    return when (requestMode) {
      IndexRequestMode.CACHE_ONLY -> BindingIndex.EMPTY
      IndexRequestMode.BACKGROUND -> {
        scheduleBuild(module, key)
        BindingIndex.EMPTY
      }
      IndexRequestMode.SYNCHRONOUS ->
        if (ApplicationManager.getApplication().isDispatchThread) {
          // BasePlatformTestCase performs existing marker/index assertions synchronously on the
          // EDT. Production callers take the background path and never reach this exception.
          allowAnalysisOnEdt { buildCurrentIndex(module, key) }
        } else {
          buildCurrentIndex(module, key)
        }
    }
  }

  /** Notifies a tool window when a fresh background index is ready; callbacks run on the EDT. */
  internal fun addIndexListener(parentDisposable: Disposable, listener: () -> Unit) {
    listeners += listener
    Disposer.register(parentDisposable) { listeners -= listener }
  }

  /** Reports serialized tool-window index builds on the EDT. */
  internal fun addIndexBuildProgressListener(
    parentDisposable: Disposable,
    listener: (IndexBuildProgress?) -> Unit,
  ) {
    indexBuildProgressListeners += listener
    Disposer.register(parentDisposable) { indexBuildProgressListeners -= listener }
    notifyIndexBuildProgressListener(listener, indexBuildProgress.get())
  }

  /**
   * Invalidates snapshots after an index-relevant setting changes without discarding source shards.
   */
  internal fun settingsChanged() {
    val resolveFromLibraries = MetroSettings.getInstance(project).state.resolveFromLibraries
    if (lastResolveFromLibraries.getAndSet(resolveFromLibraries) == resolveFromLibraries) {
      return
    }
    val bumped = invalidations.updateAndGet { it.bumpGeneration() }
    if (!resolveFromLibraries) {
      synchronized(libraryShards) { libraryShards.clear() }
    }
    evictStaleCaches(
      bumped.generation,
      ProjectRootModificationTracker.getInstance(project).modificationCount,
    )
    notifyListeners(restartDaemon = false)
  }

  /** Roots/facet changes should refresh open windows even when no editor asks for the index. */
  private fun projectInputsChanged() {
    val pending = PendingProjectInputs(sourceSnapshot.get())
    if (!pendingProjectInputs.compareAndSet(null, pending)) return
    ApplicationManager.getApplication().invokeLater {
      val scheduled = pendingProjectInputs.getAndSet(null) ?: return@invokeLater
      if (disposed.get() || project.isDisposed) return@invokeLater
      reconcileProjectInputs(scheduled.snapshot)
    }
  }

  /** A sync can change many modules together; compare their semantic options once per batch. */
  private fun reconcileProjectInputs(snapshot: SourceSnapshot?) {
    if (snapshot == null) {
      // An already-open window may be waiting for Metro to be configured for the first time.
      scheduleInvalidationNotification()
      return
    }
    val inputs = currentInputs()
    val rootsChanged = snapshot.inputs.roots != inputs.roots
    val compilerSettingsChanged = snapshot.inputs.compilerSettings != inputs.compilerSettings
    if (!rootsChanged && !compilerSettingsChanged) return

    val currentFingerprints = if (compilerSettingsChanged) moduleFingerprints() else null
    val semanticSettingsChanged =
      compilerSettingsChanged && snapshot.moduleFingerprints != currentFingerprints
    if (!rootsChanged && !semanticSettingsChanged) {
      // Reenabling Metro can match the last built options after disabling evicted every index.
      if (snapshots.isEmpty()) scheduleInvalidationNotification()
      return
    }

    val latest = sourceSnapshot.get()
    val currentSourceAlreadyPublished =
      latest != null &&
        latest.inputs == inputs &&
        (!semanticSettingsChanged || latest.moduleFingerprints == currentFingerprints)
    if (!currentSourceAlreadyPublished) {
      val bumped = invalidations.updateAndGet { it.bumpGeneration() }
      evictStaleCaches(bumped.generation, inputs.roots)
    }
    scheduleInvalidationNotification()
  }

  /** Entries stranded by generation or root changes can never be served again, so drop them. */
  private fun evictStaleCaches(currentGeneration: Long, currentRoots: Long) {
    synchronized(snapshots) {
      snapshots.values.removeIf { !it.matches(currentGeneration, currentRoots) }
    }
    synchronized(libraryShards) {
      libraryShards.keys.removeIf { it.rootsGeneration != currentRoots }
    }
  }

  private fun scheduleBuild(module: Module, key: SnapshotKey) {
    if (pendingBuilds.putIfAbsent(key, module) != null) return
    val queued = IndexBuildProgress(IndexBuildPhase.QUEUED)
    if (indexBuildProgress.compareAndSet(null, queued)) {
      notifyIndexBuildProgress(queued)
    }
    buildSignal.trySend(Unit)
  }

  /**
   * Builds (or reuses) the index for [key] with an optimistic drain/compute/publish loop:
   * 1. Drain the invalidation ledger and read the last published source snapshot.
   * 2. Compute a new immutable snapshot outside any lock. Analysis is allowed here, and the
   *    caller's read action keeps PSI stable for the whole pass.
   * 3. Publish with a single compare-and-set against the drained ledger. A concurrent invalidation
   *    fails the publish and the loop re-drains. Unchanged shards replay from their per-file cached
   *    values, so retries are cheap.
   */
  private fun buildCurrentIndex(
    module: Module,
    key: SnapshotKey,
    progress: IndexBuildProgressReporter? = null,
  ): BindingIndex {
    if (DumbService.isDumb(project)) return BindingIndex.EMPTY
    val moduleState = project.service<MetroIdeProjectService>().state(module)
    if (!moduleState.isEnabled) return BindingIndex.EMPTY
    val currentKey =
      SnapshotKey(
        fingerprintFor(moduleState),
        MetroSettings.getInstance(project).state.resolveFromLibraries,
      )
    if (currentKey != key) return BindingIndex.EMPTY

    while (true) {
      ProgressManager.checkCanceled()
      var start = invalidations.get()
      val inputs = currentInputs()
      val prev = sourceSnapshot.get()

      if (prev != null && prev.inputs == inputs) {
        snapshots[key]
          ?.takeIf { it.matches(start.generation, inputs.roots) }
          ?.let {
            return it.index
          }
      }

      val compilerSettingsChanged =
        prev != null && prev.inputs.compilerSettings != inputs.compilerSettings
      val fingerprintChanged =
        compilerSettingsChanged && prev!!.moduleFingerprints != moduleFingerprints()
      if (fingerprintChanged) {
        // A semantic option change makes everything keyed by the old generation stale. Bump once
        // and
        // adopt the bumped ledger as this pass's drain point so the loop cannot spin.
        start = invalidations.updateAndGet { it.bumpGeneration() }
      }

      val coldSweep = prev == null || prev.inputs.roots != inputs.roots || fingerprintChanged
      val next =
        if (coldSweep) {
          coldSweep(moduleState.options, inputs, start, progress)
        } else {
          incremental(prev!!, inputs, start, progress)
        }

      // Publish the snapshot before draining the ledger. A builder that observes the drained
      // ledger then also observes this snapshot, so no builder can pair a drained ledger with
      // the previous snapshot and re-publish or cache stale state. If the drain CAS below fails,
      // the early publish is harmless because the files it incorporated are still marked dirty
      // and simply replay from their per-file cached values on the retry.
      sourceSnapshot.set(next)
      val drained = start.drainAll()
      if (!invalidations.compareAndSet(start, drained)) {
        continue
      }

      snapshots[key]
        ?.takeIf { it.matches(start.generation, inputs.roots) }
        ?.let {
          return it.index
        }
      progress?.phase(IndexBuildPhase.COMBINING_DECLARATIONS)
      val rawSource = aggregateSource(next, progress)
      progress?.phase(IndexBuildPhase.RESOLVING_ASSISTED_FACTORIES)
      val summary = next.librarySummary.getOrCreate(project, rawSource)
      val source = rawSource.withAddedFactories(summary.sourceFactories.addedBindings)
      val library =
        if (key.resolveFromLibraries) {
          progress?.phase(IndexBuildPhase.READING_DEPENDENCY_METADATA)
          libraryShardFor(key.fingerprint, inputs.roots, source, summary)
        } else {
          LibraryShard.EMPTY
        }
      progress?.phase(IndexBuildPhase.BUILDING_GRAPH_INDEX)
      val indexBuilder =
        BindingIndexBuilder().apply {
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
      indexBuilder.captureResolutionInputs(project)
      val index = indexBuilder.build()
      // Only cache when nothing invalidated the pass semantically. A plain re-drain of new dirty
      // files under the same generation still describes this exact source snapshot.
      if (invalidations.get().generation == start.generation) {
        snapshots[key] = IndexSnapshot(index, start.generation, inputs.roots)
        evictStaleCaches(start.generation, inputs.roots)
      }
      return index
    }
  }

  private fun coldSweep(
    options: MetroOptions,
    inputs: IndexInputs,
    start: Invalidations,
    progress: IndexBuildProgressReporter?,
  ): SourceSnapshot {
    progress?.phase(IndexBuildPhase.DISCOVERING_SOURCE_FILES)
    val annotationIds = projectSweepAnnotationIds(options)
    val shortNames = annotationIds.mapToSet { it.shortClassName.asString() }
    val transaction = SourceSnapshotTransaction()
    val candidates = candidateFiles(annotationIds, shortNames)
    val total = candidates.size + start.requested.size
    var completed = 0
    progress?.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, completed, total)
    for (file in candidates) {
      ProgressManager.checkCanceled()
      try {
        val virtualFile = file.virtualFile ?: continue
        transaction.applyShard(virtualFile, shardFor(file))
      } finally {
        completed++
        progress?.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, completed, total)
      }
    }
    // Stub loading can surface requested files before their annotations reach the stub index.
    for (virtualFile in start.requested) {
      ProgressManager.checkCanceled()
      try {
        if (!virtualFile.isValid || transaction.containsShard(virtualFile)) {
          continue
        }
        val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: continue
        if (containsRelevantAnnotation(file, shortNames)) {
          transaction.applyShard(virtualFile, shardFor(file))
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
    start: Invalidations,
    progress: IndexBuildProgressReporter?,
  ): SourceSnapshot {
    val dirty =
      if (start.forceAll) {
        buildSet {
          addAll(prev.shardOrder)
          addAll(start.dirty)
        }
      } else {
        start.dirty
      }
    if (dirty.isEmpty() && start.requested.isEmpty()) {
      // Output-only compiler-option changes update inputs without touching any shard.
      return if (prev.inputs == inputs) prev else prev.withInputs(inputs)
    }
    val transaction = SourceSnapshotTransaction(prev)
    val total = dirty.size + start.requested.size
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
        val forced = start.forceAll || virtualFile in start.forced
        transaction.applyShard(virtualFile, shardFor(file, forced))
      } finally {
        completed++
        progress?.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, completed, total)
      }
    }
    // Requested files were enqueued before their stubs or directory events settled. Draining
    // them here keeps them from lingering in the ledger until a cold sweep.
    for (virtualFile in start.requested) {
      ProgressManager.checkCanceled()
      try {
        if (!virtualFile.isValid || transaction.containsShard(virtualFile)) {
          continue
        }
        val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: continue
        if (containsRelevantAnnotation(file, prev.shortNames)) {
          transaction.applyShard(virtualFile, shardFor(file))
        }
      } finally {
        completed++
        progress?.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, completed, total)
      }
    }
    return transaction.snapshot(inputs, prev.moduleFingerprints, prev.shortNames)
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

  /** Materialize each scope-matched candidate once; BindingIndex owns path-specific selection. */
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
    summary: SourceLibrarySummary,
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
          summary.factoryUseSites,
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

  private fun projectSweepShortNames(fallbackOptions: MetroOptions): Set<String> {
    return projectSweepAnnotationIds(fallbackOptions).mapToSet { it.shortClassName.asString() }
  }

  /** Compiler output/report settings do not change semantic fingerprints or source declarations. */
  private fun moduleFingerprints(): Map<Module, IndexOptionsFingerprint> {
    val service = project.service<MetroIdeProjectService>()
    return buildMap {
      for (module in ModuleManager.getInstance(project).modules) {
        ProgressManager.checkCanceled()
        val state = service.state(module)
        if (state.isEnabled) put(module, fingerprintFor(state))
      }
    }
  }

  private fun fingerprintFor(state: MetroIdeModuleState): IndexOptionsFingerprint {
    return fingerprintsByModuleState.computeIfAbsent(state) { IndexOptionsFingerprint(it.options) }
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

    // Search a distinctive package component rather than common names like Inject/Provides.
    // This visits import/package occurrences, not every annotation usage in the whole project.
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

  private fun containsRelevantAnnotation(file: KtFile, shortNames: Set<String>): Boolean {
    val names =
      if (file.importDirectives.any { it.aliasName != null }) {
        shortNames +
          file.annotationShortNamesIncludingAliases(
            sweepAnnotationIds(file.metroIdeState().options)
          )
      } else {
        shortNames
      }
    val hasRelevantAnnotation =
      PsiTreeUtil.collectElementsOfType(file, KtAnnotationEntry::class.java).any { entry ->
        entry.shortName?.asString() in names
      }
    if (hasRelevantAnnotation) return true

    val dynamicGraphNames = buildSet {
      for (callableId in DYNAMIC_GRAPH_CALLABLES.keys) {
        add(callableId.callableName.asString())
        for (directive in file.importDirectives) {
          if (directive.importedFqName == callableId.asSingleFqName()) {
            directive.aliasName?.let(::add)
          }
        }
      }
    }
    return PsiTreeUtil.collectElementsOfType(file, KtCallExpression::class.java).any { call ->
      call.calleeExpression?.text in dynamicGraphNames
    }
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
        // Dependency PSI is only handed to the platform's cache registration here. The shard
        // model and the service retain virtual files instead of pinning PSI.
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

  private fun currentInputs(): IndexInputs =
    IndexInputs(
      roots = ProjectRootModificationTracker.getInstance(project).modificationCount,
      compilerSettings = KotlinCompilerSettingsTracker.getInstance(project).modificationCount,
    )

  private fun isFileStructureChange(event: PsiTreeChangeEvent): Boolean =
    event.parent is PsiDirectory ||
      event.child is KtFile ||
      event.child is PsiDirectory ||
      event.element is KtFile ||
      event.element is PsiDirectory

  /** An opened file may be available before its stub index or directory-creation event settles. */
  private fun enrollRequestedFile(file: KtFile) {
    val virtualFile = file.virtualFile ?: return
    val state = sourceSnapshot.get()
    if (state == null) {
      invalidations.updateAndGet { it.withRequested(virtualFile) }
      return
    }
    if (virtualFile in state.shards || virtualFile in invalidations.get().dirty) {
      return
    }
    // Editor features call index() once per declaration. A cached negative keeps files without
    // Metro annotations from paying a full PSI walk on every call.
    if (!isRelevantFileCached(file)) {
      return
    }
    invalidations.updateAndGet { it.withDirty(setOf(virtualFile)) }
  }

  private fun isRelevantFileCached(file: KtFile): Boolean {
    return CachedValuesManager.getCachedValue(file) {
      val shortNames =
        sourceSnapshot.get()?.shortNames ?: projectSweepShortNames(file.metroIdeState().options)
      CachedValueProvider.Result.create(
        containsRelevantAnnotation(file, shortNames),
        file,
        KotlinCompilerSettingsTracker.getInstance(file.project),
      )
    }
  }

  private fun psiChanged(event: PsiTreeChangeEvent, structuralChange: Boolean = false) {
    val file = changedFile(event)
    val directory = event.child as? PsiDirectory ?: event.element as? PsiDirectory
    if (file == null && directory != null && structuralChange) {
      directoryChanged(directory)
      return
    }
    if (file == null || !file.isValid) return
    val virtualFile = file.virtualFile ?: return
    val state = sourceSnapshot.get()
    if (state == null) {
      if (structuralChange) {
        invalidations.updateAndGet { it.withRequested(virtualFile) }
      }
      return
    }
    val requestFile = structuralChange && virtualFile !in state.shards
    val ownerFiles = state.dependencyOwners[virtualFile]
    val alreadyIndexed = virtualFile in state.shards
    val newlyRelevant = !alreadyIndexed && isRelevantFileCached(file)
    // A file can mix indexed declarations with constants or aliases that unrelated shards
    // reference. Only a change to those declarations needs the whole-project fallback.
    val globalSemanticChange = sharedDeclarationChanged(event, file, structuralChange)
    val affectsIndexedDeclarations =
      alreadyIndexed ||
        !ownerFiles.isNullOrEmpty() ||
        newlyRelevant ||
        structuralChange ||
        globalSemanticChange
    if (!affectsIndexedDeclarations) {
      if (requestFile) {
        invalidations.updateAndGet { it.withRequested(virtualFile) }
      }
      return
    }

    val dirty = mutableSetOf(virtualFile)
    if (ownerFiles != null) {
      dirty += ownerFiles
    }
    invalidations.updateAndGet { ledger ->
      var updated = ledger.withDirty(dirty)
      if (globalSemanticChange) {
        updated = updated.withForceAll()
      }
      if (requestFile) {
        updated = updated.withRequested(virtualFile)
      }
      updated
    }
    scheduleInvalidationNotification()
  }

  /**
   * Unannotated aliases/constants can change keys across unrelated files without PSI pointers.
   * Edits to such files force a whole-project re-shard because dependency tracking only records
   * annotation and factory declaration files. Narrowing this needs referenced-declaration files
   * recorded during type-key snapshotting.
   */
  private fun fileHasSharedDeclarationsCached(file: KtFile): Boolean {
    return CachedValuesManager.getCachedValue(file) {
      CachedValueProvider.Result.create(hasSharedSemanticDeclarations(file), file)
    }
  }

  private fun sharedDeclarationChanged(
    event: PsiTreeChangeEvent,
    file: KtFile,
    structuralChange: Boolean,
  ): Boolean {
    val virtualFile = file.virtualFile ?: return false
    val hasSharedDeclarations = fileHasSharedDeclarationsCached(file)
    val previous = sharedDeclarationFingerprints[virtualFile]
    if (!hasSharedDeclarations) {
      if (previous != null) {
        sharedDeclarationFingerprints.remove(virtualFile)
        return true
      }
      return changedSharedElement(event)
    }

    val current = sharedDeclarationFingerprint(file)
    sharedDeclarationFingerprints[virtualFile] = current
    if (previous != null && previous != current) return true
    if (structuralChange) return true
    return changedSharedElement(event)
  }

  /** Names and declaration text catch value, alias, containing-object, and import changes. */
  private fun sharedDeclarationFingerprint(file: KtFile): String {
    return buildString {
      append(file.packageFqName.asString())
      append('\n')
      append(file.importList?.text.orEmpty())

      fun appendDeclarations(declarations: List<KtDeclaration>, owner: String) {
        for (declaration in declarations) {
          when {
            declaration is KtTypeAlias -> {
              append('\n')
              append(owner)
              append(declaration.text)
            }
            declaration is KtProperty && declaration.hasModifier(KtTokens.CONST_KEYWORD) -> {
              append('\n')
              append(owner)
              append(declaration.text)
            }
            declaration is KtClassOrObject -> {
              appendDeclarations(declaration.declarations, "$owner${declaration.name}.")
            }
          }
        }
      }

      appendDeclarations(file.declarations, owner = "")
    }
  }

  private fun changedSharedElement(event: PsiTreeChangeEvent): Boolean {
    val candidate = event.child ?: event.element ?: event.parent ?: return false
    if (candidate is KtFile || candidate is PsiDirectory) return false
    if (candidate is KtClassOrObject && hasSharedSemanticDeclarations(candidate)) return true

    var current: PsiElement? = candidate
    while (current != null && current !is KtFile) {
      if (current is KtTypeAlias) return true
      if (current is KtProperty && current.hasModifier(KtTokens.CONST_KEYWORD)) return true
      current = current.parent
    }
    return false
  }

  private fun hasSharedSemanticDeclarations(file: KtFile): Boolean {
    return file.declarations.any(::hasSharedSemanticDeclarations)
  }

  private fun hasSharedSemanticDeclarations(declaration: KtDeclaration): Boolean {
    // Consts commonly live inside objects and companion objects, so recurse through all nesting.
    return when {
      declaration is KtTypeAlias -> true
      declaration is KtProperty && declaration.hasModifier(KtTokens.CONST_KEYWORD) -> true
      declaration is KtClassOrObject ->
        declaration.declarations.any(::hasSharedSemanticDeclarations)
      else -> false
    }
  }

  /** Directory moves can replace several Kotlin files without reporting individual PSI children. */
  private fun directoryChanged(directory: PsiDirectory) {
    if (!directory.isValid || !directory.virtualFile.isValid) return
    val files = mutableListOf<KtFile>()
    val remaining = ArrayDeque<PsiDirectory>()
    remaining += directory
    while (remaining.isNotEmpty()) {
      ProgressManager.checkCanceled()
      val current = remaining.removeFirst()
      if (!current.isValid || !current.virtualFile.isValid) continue
      files += current.files.filterIsInstance<KtFile>()
      remaining += current.subdirectories
    }
    if (files.isEmpty()) return

    val state = sourceSnapshot.get() ?: return
    val requested = mutableSetOf<VirtualFile>()
    val dirty = mutableSetOf<VirtualFile>()
    var sharedDeclarationsChanged = false
    for (file in files) {
      ProgressManager.checkCanceled()
      val virtualFile = file.virtualFile ?: continue
      if (fileHasSharedDeclarationsCached(file)) {
        sharedDeclarationsChanged = true
      }
      if (virtualFile !in state.shards) {
        requested += virtualFile
      }
      val owners = state.dependencyOwners[virtualFile]
      val relevant = virtualFile in state.shards || !owners.isNullOrEmpty()
      val newlyRelevant = containsRelevantAnnotation(file, state.shortNames)
      if (!relevant && !newlyRelevant) {
        continue
      }
      dirty += virtualFile
      if (owners != null) {
        dirty += owners
      }
    }
    if (requested.isEmpty() && dirty.isEmpty() && !sharedDeclarationsChanged) {
      return
    }
    invalidations.updateAndGet { ledger ->
      var updated = ledger.withRequested(requested)
      if (dirty.isNotEmpty()) {
        updated = updated.withDirty(dirty)
      }
      if (sharedDeclarationsChanged) {
        updated = updated.withForceAll()
      }
      updated
    }
    if (dirty.isNotEmpty() || sharedDeclarationsChanged) {
      scheduleInvalidationNotification()
    }
  }

  private fun changedFile(event: PsiTreeChangeEvent): KtFile? {
    val file = event.file as? KtFile
    if (file != null) return file

    val elementFile = event.element as? KtFile
    if (elementFile != null) return elementFile

    val childFile = event.child as? KtFile
    if (childFile != null) return childFile

    val parentFile = event.parent?.containingFile as? KtFile
    if (parentFile != null) return parentFile

    return event.child?.containingFile as? KtFile
  }

  private fun notifyListeners(restartDaemon: Boolean) {
    val application = ApplicationManager.getApplication()
    if (!application.isDispatchThread) {
      application.invokeLater {
        if (!project.isDisposed) notifyListeners(restartDaemon)
      }
      return
    }
    if (restartDaemon) DaemonCodeAnalyzer.getInstance(project).restart()
    for (listener in listeners.toList()) listener()
  }

  private fun publishIndexBuildProgress(progress: IndexBuildProgress?) {
    indexBuildProgress.set(progress)
    notifyIndexBuildProgress(progress)
  }

  private fun notifyIndexBuildProgress(progress: IndexBuildProgress?) {
    for (listener in indexBuildProgressListeners.toList()) {
      notifyIndexBuildProgressListener(listener, progress)
    }
  }

  private fun notifyIndexBuildProgressListener(
    listener: (IndexBuildProgress?) -> Unit,
    progress: IndexBuildProgress?,
  ) {
    val application = ApplicationManager.getApplication()
    if (!application.isDispatchThread) {
      application.invokeLater {
        if (progress == null && indexBuildProgress.get() != null) return@invokeLater
        if (!disposed.get() && !project.isDisposed && listener in indexBuildProgressListeners) {
          listener(progress)
        }
      }
      return
    }
    if (progress == null && indexBuildProgress.get() != null) return
    if (!disposed.get() && !project.isDisposed && listener in indexBuildProgressListeners) {
      listener(progress)
    }
  }

  /** Coalesces write-action events so an open graph window requests a fresh background snapshot. */
  private fun scheduleInvalidationNotification() {
    if (listeners.isEmpty()) return
    if (!invalidationPending.compareAndSet(false, true)) return
    ApplicationManager.getApplication().invokeLater {
      invalidationPending.set(false)
      if (!disposed.get() && !project.isDisposed) notifyListeners(restartDaemon = false)
    }
  }

  override fun dispose() {
    disposed.set(true)
    pendingProjectInputs.set(null)
    buildSignal.close()
    pendingBuilds.clear()
    graphBrowserActivated.set(false)
    listeners.clear()
    indexBuildProgressListeners.clear()
    indexBuildProgress.set(null)
    sharedDeclarationFingerprints.clear()
    snapshots.clear()
    libraryShards.clear()
    fingerprintsByModuleState.clear()
  }

  private companion object {
    const val MAX_CACHED_INDEXES = 8
    const val MAX_CACHED_OPTION_FINGERPRINTS = 64
  }
}

/** Builds the immutable source index used to capture dependency ownership. */
private fun buildSourceOwnershipIndex(project: Project, source: SourceAggregate): BindingIndex {
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
  builder.captureResolutionInputs(project)
  return builder.build()
}

/** Captures module visibility and source declaration identities during the index read. */
@OptIn(KaPlatformInterface::class)
private fun BindingIndexBuilder.captureResolutionInputs(project: Project) {
  val representatives = linkedMapOf<VirtualFile, PsiElement>()
  val pointerSourceIdentities =
    IdentityHashMap<SmartPsiElementPointer<*>, BindingIndex.SourcePointerIdentity>()
  val capturedBindings = Collections.newSetFromMap(IdentityHashMap<KaBinding, Boolean>())
  var pointerCaptureWorkIndex = 0

  fun capture(pointer: SmartPsiElementPointer<*>) {
    checkCanceledEvery(pointerCaptureWorkIndex++)
    val identity = sourcePointerIdentity(pointer)
    if (identity != null) pointerSourceIdentities[pointer] = identity
    val file = pointer.virtualFile ?: return
    if (file in representatives) return
    val element = pointer.element ?: return
    representatives.putIfAbsent(file, element)
  }

  fun captureBinding(binding: KaBinding) {
    capturedBindings += binding
    capture(binding.pointer)
  }

  for (binding in bindings) captureBinding(binding)
  for (consumer in consumers) {
    capture(consumer.pointer)
    consumer.injectedMemberPointer?.let { pointer -> capture(pointer) }
  }
  for (graph in graphs) {
    capture(graph.pointer)
    for (factory in graph.extensionFactories) capture(factory.pointer)
    for (implementation in graph.defaultImplementations) {
      capture(implementation.declaration.pointer)
      for (overridden in implementation.overriddenDeclarations) capture(overridden.pointer)
    }
    for (contribution in graph.contributedInterfaces) {
      capture(contribution.contribution.pointer)
      for (binding in contribution.bindings) captureBinding(binding)
      for (consumer in contribution.consumers) capture(consumer.pointer)
      for (factory in contribution.extensionFactories) capture(factory.pointer)
      for (implementation in contribution.defaultImplementations) {
        capture(implementation.declaration.pointer)
        for (overridden in implementation.overriddenDeclarations) capture(overridden.pointer)
      }
    }
  }
  for (contribution in contributions) capture(contribution.pointer)
  for (site in assistedSites) capture(site.pointer)
  for (container in bindingContainers) capture(container.pointer)
  for (dynamicGraph in dynamicGraphs) {
    capture(dynamicGraph.pointer)
    for (input in dynamicGraph.containerInputs) captureBinding(input)
  }
  capturedBindingSourceIdentities =
    IdentityHashMap<KaBinding, BindingIndex.SourcePointerIdentity>().apply {
      for (binding in capturedBindings) {
        checkCanceledEvery(pointerCaptureWorkIndex++)
        pointerSourceIdentities[binding.pointer]?.let { identity -> put(binding, identity) }
      }
    }

  val fileOrdinalTable =
    FileOrdinalTable.freeze(
      representatives.keys.withIndex().associate { (ordinal, file) ->
        file to FileOrdinal(ordinal)
      }
    )
  val moduleByFile = linkedMapOf<VirtualFile, ModuleViewId>()
  val moduleIds = linkedMapOf<KaModule, ModuleViewId>()
  val moduleRepresentatives = linkedMapOf<KaModule, PsiElement>()
  for ((file, element) in representatives) {
    ProgressManager.checkCanceled()
    val module = KaModuleProvider.getModule(project, element, useSiteModule = null)
    val moduleId = moduleIds.getOrPut(module) { ModuleViewId(moduleIds.size) }
    moduleByFile[file] = moduleId
    moduleRepresentatives.putIfAbsent(module, element)
  }

  val moduleViews = linkedMapOf<ModuleViewId, BindingIndexModuleView>()
  for ((module, moduleId) in moduleIds) {
    ProgressManager.checkCanceled()
    val scope = KaResolutionScope.forModule(module)
    val visibleFiles = BooleanArray(fileOrdinalTable.size)
    for ((file, element) in representatives) {
      ProgressManager.checkCanceled()
      if (scope.contains(element)) {
        visibleFiles[fileOrdinalTable.getValue(file).value] = true
      }
    }
    val moduleElement = checkNotNull(moduleRepresentatives[module])
    moduleViews[moduleId] =
      BindingIndexModuleView(
        id = moduleId,
        module = module,
        visibleFileOrdinals = visibleFiles,
        fileOrdinalTable = fileOrdinalTable,
        daggerAnvilInteropEnabled = moduleElement.metroIdeState().options.enableDaggerAnvilInterop,
      )
  }
  resolutionInputs =
    BindingIndexResolutionInputs(
      fileOrdinalTable,
      moduleByFile,
      moduleViews,
      pointerSourceIdentities,
    )
}

private enum class IndexRequestMode {
  CACHE_ONLY,
  BACKGROUND,
  SYNCHRONOUS,
}

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

/** Retries platform read-action cancellations without cancelling the long-lived index worker. */
internal suspend fun <T> retryCancelledIndexBuild(build: suspend () -> T): T {
  while (true) {
    try {
      return build()
    } catch (_: ProcessCanceledException) {
      // Yield before retrying so a cancelled service scope still stops the worker promptly.
      yield()
    }
  }
}

private val FORCE_TRACKER_KEY = Key.create<SimpleModificationTracker>("metro.shard.force.tracker")

private data class SnapshotKey(
  val fingerprint: IndexOptionsFingerprint,
  val resolveFromLibraries: Boolean,
)

private data class IndexInputs(val roots: Long, val compilerSettings: Long)

/**
 * Captures the pre-change state once so deferred callbacks retain activation and refresh events.
 */
private data class PendingProjectInputs(val snapshot: SourceSnapshot?)

private data class IndexSnapshot(
  val index: BindingIndex,
  val generation: Long,
  val rootsGeneration: Long,
) {
  fun matches(currentGeneration: Long, currentRootsGeneration: Long): Boolean =
    generation == currentGeneration && rootsGeneration == currentRootsGeneration
}

/**
 * Pending invalidations as one immutable value. [stamp] moves on every transition so a builder's
 * publish compare-and-set observes any concurrent change. [generation] moves only on semantic
 * invalidations and keys the snapshot cache.
 */
private class Invalidations(
  val stamp: Long = 0,
  val generation: Long = 0,
  val dirty: Set<VirtualFile> = emptySet(),
  val forced: Set<VirtualFile> = emptySet(),
  val requested: Set<VirtualFile> = emptySet(),
  /** Re-shard every indexed file, recorded as a flag so listeners never copy the shard set. */
  val forceAll: Boolean = false,
) {
  fun bumpGeneration(): Invalidations =
    Invalidations(stamp + 1, generation + 1, dirty, forced, requested, forceAll)

  fun withDirty(files: Set<VirtualFile>): Invalidations =
    Invalidations(stamp + 1, generation + 1, dirty + files, forced, requested, forceAll)

  fun withForceAll(): Invalidations =
    Invalidations(stamp + 1, generation + 1, dirty, forced, requested, forceAll = true)

  /** Requested files feed a future pass and do not invalidate published results. */
  fun withRequested(file: VirtualFile): Invalidations =
    if (file in requested) {
      this
    } else {
      Invalidations(stamp + 1, generation, dirty, forced, requested + file, forceAll)
    }

  /** Directory events merge all requests once instead of repeatedly copying the growing set. */
  fun withRequested(files: Set<VirtualFile>): Invalidations {
    if (files.isEmpty() || requested.containsAll(files)) return this
    return Invalidations(stamp + 1, generation, dirty, forced, requested + files, forceAll)
  }

  /** The ledger after a successful publish, which consumed every pending entry. */
  fun drainAll(): Invalidations = Invalidations(stamp + 1, generation)
}

/** An immutable source view. Incremental passes copy it with only the changed shards replaced. */
private class SourceSnapshot(
  val inputs: IndexInputs,
  val moduleFingerprints: Map<Module, IndexOptionsFingerprint>,
  val shortNames: Set<String>,
  val shards: PartitionedFileMap<FileShard>,
  /** Reused across ordinary replacements so declaration and duplicate ordering stays stable. */
  val shardOrder: List<VirtualFile>,
  /** Dependency file to the shard files that must rebuild when it changes. */
  val dependencyOwners: PartitionedFileMap<Set<VirtualFile>>,
  /** Reused when changed shards leave every effective binary lookup input unchanged. */
  val librarySummary: SourceLibrarySummaryReference,
) {
  fun withInputs(newInputs: IndexInputs): SourceSnapshot =
    SourceSnapshot(
      newInputs,
      moduleFingerprints,
      shortNames,
      shards,
      shardOrder,
      dependencyOwners,
      librarySummary,
    )
}

/** Collects one immutable source transition without copying unrelated shards or owner sets. */
private class SourceSnapshotTransaction(private val previous: SourceSnapshot? = null) {
  private val shardChanges = linkedMapOf<VirtualFile, FileShard?>()
  private val ownerChanges = linkedMapOf<VirtualFile, MutableSet<VirtualFile>?>()

  fun containsShard(file: VirtualFile): Boolean = currentShard(file) != null

  fun applyShard(file: VirtualFile, shard: FileShard) {
    removeShard(file)
    if (shard === FileShard.EMPTY) return

    shardChanges[file] = shard
    for (dependencyFile in shard.dependencyFiles) {
      mutableOwners(dependencyFile).add(file)
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
  }

  fun snapshot(
    inputs: IndexInputs,
    moduleFingerprints: Map<Module, IndexOptionsFingerprint>,
    shortNames: Set<String>,
  ): SourceSnapshot {
    val previousShards = previous?.shards ?: PartitionedFileMap.empty()
    val previousOwners = previous?.dependencyOwners ?: PartitionedFileMap.empty()
    val ownerUpdates = linkedMapOf<VirtualFile, Set<VirtualFile>?>()
    for ((file, owners) in ownerChanges) {
      ownerUpdates[file] = owners?.toSet()
    }
    val shards = previousShards.withChanges(shardChanges)
    val owners = previousOwners.withChanges(ownerUpdates)

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
    val libraryInputsChanged =
      previous == null ||
        shardChanges.any { (file, updated) ->
          val before = previous.shards[file]?.librarySignature()
          val after = updated?.librarySignature()
          before != after
        }
    val librarySummary =
      if (!libraryInputsChanged && previousSummary != null) previousSummary
      else SourceLibrarySummaryReference()
    return SourceSnapshot(
      inputs,
      moduleFingerprints,
      shortNames,
      shards,
      order,
      owners,
      librarySummary,
    )
  }

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

private data class ContextKeyLibrarySignature(
  val key: KaContextualTypeKey,
  val hasDefault: Boolean,
  val rawType: KaTypeSnapshot?,
)

private data class AssistedFactoryDefinitionSignature(
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

/** Fixed-width immutable hash buckets; a transition copies only buckets whose entries change. */
private class PartitionedFileMap<V : Any>
private constructor(private val buckets: Array<Map<VirtualFile, V>?>) {

  operator fun contains(file: VirtualFile): Boolean {
    return buckets[bucketIndex(file)]?.containsKey(file) == true
  }

  operator fun get(file: VirtualFile): V? = buckets[bucketIndex(file)]?.get(file)

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

/** A failed or canceled calculation is never published; equivalent source snapshots share this. */
private class SourceLibrarySummaryReference {
  @Volatile private var summary: SourceLibrarySummary? = null

  fun getOrCreate(project: Project, source: SourceAggregate): SourceLibrarySummary {
    summary?.let {
      return it
    }
    synchronized(this) {
      summary?.let {
        return it
      }
      val sourceIndex = buildSourceOwnershipIndex(project, source)
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
      val result =
        SourceLibrarySummary(
          inputs,
          sourceFactories.factoryUseSites,
          consumerOwnership,
          sourceFactories,
        )
      ProgressManager.checkCanceled()
      summary = result
      return result
    }
  }
}

private data class SourceLibrarySummary(
  val inputs: LibraryInputs,
  val factoryUseSites: SourceAssistedFactoryUseSites,
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

private data class LibraryInputs(
  val scopeIds: Set<ClassId>,
  val participatingModules: Set<KaModule>,
  val requests: Set<LibraryInjectInput>,
  val sourceFactoryDefinitions: List<AssistedFactoryDefinitionSignature>,
  val factoryBudget: FactoryBudgetCacheInput,
)

private data class FactoryBudgetCacheInput(
  val writtenDepth: Int,
  val writtenNodes: Int,
  val writtenFactoryKeys: Set<KaTypeKey>,
)

private data class LibraryInjectInput(
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
private class IndexOptionsFingerprint(val options: MetroOptions) {
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
  val names = annotationIds.mapTo(mutableSetOf()) { it.shortClassName.asString() }
  for (directive in importDirectives) {
    val alias = directive.aliasName ?: continue
    val importedName = directive.importedFqName ?: continue
    if (annotationIds.any { it.asSingleFqName() == importedName }) {
      names += alias
    }
  }
  return names
}
