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
import dev.zacsweers.metro.idea.metroIdeState
import dev.zacsweers.metro.idea.model.AssistedSite
import dev.zacsweers.metro.idea.model.BindingContainerEntry
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
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
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsListener
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker
import org.jetbrains.kotlin.idea.stubindex.KotlinAnnotationsIndex
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtAnnotationEntry
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
  /** Pre-change shared declarations, so broad PSI events do not invalidate unrelated edits. */
  private val sharedDeclarationFingerprints = ConcurrentHashMap<VirtualFile, String>()
  private val invalidationPending = AtomicBoolean()
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

  /** Drains EDT-requested background builds one at a time on the service scope. */
  private suspend fun buildWorker() {
    for (unused in buildSignal) {
      while (true) {
        val (key, module) = pendingBuilds.entries.firstOrNull() ?: break
        pendingBuilds.remove(key, module)
        val built =
          try {
            retryCancelledIndexBuild {
              smartReadAction(project) { buildCurrentIndex(module, key) }
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
    }
  }

  /** Returns the current index for [element]'s module, or an empty index when Metro is inactive. */
  internal fun index(element: PsiElement): BindingIndex {
    val file = element as? KtFile ?: element.containingFile as? KtFile
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return BindingIndex.EMPTY
    if (file != null) enrollRequestedFile(file)
    return index(module)
  }

  /**
   * Returns a current project snapshot for [module]. Production EDT callers never perform Kotlin
   * analysis: they trigger a coalesced smart-mode build and receive an empty index until it lands.
   * Background highlighting and the platform's synchronous unit-test fixtures build immediately.
   */
  internal fun index(module: Module): BindingIndex {
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

    val application = ApplicationManager.getApplication()
    if (application.isDispatchThread && !application.isUnitTestMode) {
      scheduleBuild(module, key)
      return BindingIndex.EMPTY
    }

    return if (application.isDispatchThread) {
      // BasePlatformTestCase performs existing marker/index assertions synchronously on the EDT.
      // Production callers take the background path above and never reach this exception.
      allowAnalysisOnEdt { buildCurrentIndex(module, key) }
    } else {
      buildCurrentIndex(module, key)
    }
  }

  /** Notifies a tool window when a fresh background index is ready; callbacks run on the EDT. */
  internal fun addIndexListener(parentDisposable: Disposable, listener: () -> Unit) {
    listeners += listener
    Disposer.register(parentDisposable) { listeners -= listener }
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
    val snapshot = sourceSnapshot.get()
    if (snapshot == null) {
      // An already-open window may be waiting for Metro to be configured for the first time.
      scheduleInvalidationNotification()
      return
    }
    val inputs = currentInputs()
    val rootsChanged = snapshot.inputs.roots != inputs.roots
    val compilerSettingsChanged = snapshot.inputs.compilerSettings != inputs.compilerSettings
    if (!rootsChanged && !compilerSettingsChanged) return

    val semanticSettingsChanged =
      compilerSettingsChanged && snapshot.moduleFingerprints != moduleFingerprints()
    if (!rootsChanged && !semanticSettingsChanged) {
      // Reenabling Metro can match the last built options after disabling evicted every index.
      if (snapshots.isEmpty()) scheduleInvalidationNotification()
      return
    }

    val bumped = invalidations.updateAndGet { it.bumpGeneration() }
    evictStaleCaches(bumped.generation, inputs.roots)
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
    pendingBuilds.putIfAbsent(key, module)
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
  private fun buildCurrentIndex(module: Module, key: SnapshotKey): BindingIndex {
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
          coldSweep(moduleState.options, inputs, start)
        } else {
          incremental(prev!!, inputs, start)
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
      val source = aggregateSource(next)
      val library =
        if (key.resolveFromLibraries) {
          libraryShardFor(key.fingerprint, inputs.roots, source)
        } else {
          LibraryShard.EMPTY
        }
      val index =
        BindingIndex(
          bindings = source.bindings + library.bindings,
          consumers = source.consumers,
          graphs = source.graphs,
          contributions = source.contributions + library.contributions,
          assistedSites = source.assistedSites,
          bindingContainers = source.bindingContainers,
        )
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
  ): SourceSnapshot {
    val annotationIds = projectSweepAnnotationIds(options)
    val shortNames = annotationIds.mapToSet { it.shortClassName.asString() }
    val transaction = SourceSnapshotTransaction()
    for (file in candidateFiles(annotationIds, shortNames)) {
      ProgressManager.checkCanceled()
      val virtualFile = file.virtualFile ?: continue
      transaction.applyShard(virtualFile, shardFor(file))
    }
    // Stub loading can surface requested files before their annotations reach the stub index.
    for (virtualFile in start.requested) {
      ProgressManager.checkCanceled()
      if (!virtualFile.isValid || transaction.containsShard(virtualFile)) {
        continue
      }
      val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: continue
      if (containsRelevantAnnotation(file, shortNames)) {
        transaction.applyShard(virtualFile, shardFor(file))
      }
    }
    return transaction.snapshot(inputs, moduleFingerprints(), shortNames)
  }

  private fun incremental(
    prev: SourceSnapshot,
    inputs: IndexInputs,
    start: Invalidations,
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
    for (virtualFile in dirty) {
      ProgressManager.checkCanceled()
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
    }
    // Requested files were enqueued before their stubs or directory events settled. Draining
    // them here keeps them from lingering in the ledger until a cold sweep.
    for (virtualFile in start.requested) {
      ProgressManager.checkCanceled()
      if (!virtualFile.isValid || transaction.containsShard(virtualFile)) {
        continue
      }
      val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: continue
      if (containsRelevantAnnotation(file, prev.shortNames)) {
        transaction.applyShard(virtualFile, shardFor(file))
      }
    }
    return transaction.snapshot(inputs, prev.moduleFingerprints, prev.shortNames)
  }

  private fun aggregateSource(snapshot: SourceSnapshot): SourceAggregate {
    val bindings = mutableListOf<KaBinding>()
    val consumers = mutableListOf<ConsumerEntry>()
    val graphs = mutableListOf<KaGraphDeclaration>()
    val contributions = mutableListOf<ContributionEntry>()
    val assistedSites = mutableListOf<AssistedSite>()
    val bindingContainers = mutableListOf<BindingContainerEntry>()
    val factoryInputs = linkedMapOf<FactoryInputEntry.Id, FactoryInputEntry>()
    for (virtualFile in snapshot.shardOrder) {
      ProgressManager.checkCanceled()
      val shard = snapshot.shards[virtualFile] ?: continue
      bindings += shard.bindings
      consumers += shard.consumers
      graphs += shard.graphs
      contributions += shard.contributions
      assistedSites += shard.assistedSites
      bindingContainers += shard.bindingContainers
      for (input in shard.factoryInputs) factoryInputs.putIfAbsent(input.id, input)
    }
    for (input in factoryInputs.values) {
      bindings += input.bindings
      consumers += input.consumers
    }
    return SourceAggregate(
      bindings,
      consumers,
      graphs,
      contributions,
      assistedSites,
      bindingContainers,
    )
  }

  private fun libraryShardFor(
    fingerprint: IndexOptionsFingerprint,
    rootsGeneration: Long,
    source: SourceAggregate,
  ): LibraryShard {
    val key = LibraryCacheKey(fingerprint, rootsGeneration, source.libraryInputs(project))
    libraryShards[key]?.let {
      return it
    }

    val bindings = source.bindings.toMutableList()
    val contributions = source.contributions.toMutableList()
    LibraryIndexPostProcessor(
        project,
        fingerprint.options,
        bindings,
        source.consumers,
        source.graphs,
        contributions,
      )
      .postProcess()
    val shard =
      LibraryShard(
        bindings.drop(source.bindings.size),
        contributions.drop(source.contributions.size),
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
    return PsiTreeUtil.collectElementsOfType(file, KtAnnotationEntry::class.java).any { entry ->
      entry.shortName?.asString() in names
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
    buildSignal.close()
    pendingBuilds.clear()
    listeners.clear()
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
) {
  fun withInputs(newInputs: IndexInputs): SourceSnapshot =
    SourceSnapshot(newInputs, moduleFingerprints, shortNames, shards, shardOrder, dependencyOwners)
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
    return SourceSnapshot(inputs, moduleFingerprints, shortNames, shards, order, owners)
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

private data class SourceAggregate(
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
  val graphs: List<KaGraphDeclaration>,
  val contributions: List<ContributionEntry>,
  val assistedSites: List<AssistedSite>,
  val bindingContainers: List<BindingContainerEntry>,
) {
  fun libraryInputs(project: Project): LibraryInputs {
    val scopeIds = linkedSetOf<ClassId>()
    val participatingModules = linkedSetOf<KaModule>()
    val injectRequests = linkedSetOf<LibraryInjectInput>()
    val sourceFactoryUseSites = sourceAssistedFactoryUseSites(project, bindings, consumers)
    val seededFactoryUseSites =
      if (sourceFactoryUseSites.isEmpty()) null
      else Collections.newSetFromMap(IdentityHashMap<Map<KaModule, KtElement>, Boolean>())

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
    for (contribution in contributions) {
      ProgressManager.checkCanceled()
      scopeIds += contribution.scopeKeys
      addModule(contribution.pointer.element)
    }
    for (consumer in consumers) {
      ProgressManager.checkCanceled()
      val module = addModule(consumer.pointer.element) ?: continue
      val classId = consumer.typeClassId ?: continue
      if (consumer.multibindingId != null) {
        continue
      }
      injectRequests += LibraryInjectInput(module, consumer.key, classId)
    }
    for (binding in bindings) {
      ProgressManager.checkCanceled()
      val hasAdditionalLibrarySeeds =
        binding is KaBinding.AssistedFactory ||
          binding is KaBinding.Provided && binding.isClassContribution
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
    return LibraryInputs(scopeIds, participatingModules, injectRequests)
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
)

private data class LibraryInjectInput(
  val module: KaModule,
  val key: KaTypeKey,
  val classId: ClassId,
)

private data class LibraryShard(
  val bindings: List<KaBinding>,
  val contributions: List<ContributionEntry>,
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
