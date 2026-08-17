// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker
import org.jetbrains.kotlin.idea.stubindex.KotlinAnnotationsIndex
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
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
            smartReadAction(project) { buildCurrentIndex(module, key) }
          } catch (exception: CancellationException) {
            throw exception
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

      // Cold sweeps consume requested files. Incremental passes leave them for a future sweep.
      val drained = if (coldSweep) start.drainAll() else start.drainDirty()
      if (!invalidations.compareAndSet(start, drained)) {
        continue
      }
      sourceSnapshot.set(next)

      snapshots[key]
        ?.takeIf { it.matches(start.generation, inputs.roots) }
        ?.let {
          return it.index
        }
      val source = aggregateSource(next.shards.values)
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
    val shortNames = projectSweepShortNames(options)
    val shards = linkedMapOf<VirtualFile, FileShard>()
    val owners = linkedMapOf<VirtualFile, MutableSet<VirtualFile>>()
    for (file in candidateFiles(shortNames)) {
      ProgressManager.checkCanceled()
      val virtualFile = file.virtualFile ?: continue
      applyShard(shards, owners, virtualFile, shardFor(file))
    }
    // Stub loading can surface requested files before their annotations reach the stub index.
    for (virtualFile in start.requested) {
      ProgressManager.checkCanceled()
      if (!virtualFile.isValid || virtualFile in shards) {
        continue
      }
      val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: continue
      if (containsRelevantAnnotation(file, shortNames)) {
        applyShard(shards, owners, virtualFile, shardFor(file))
      }
    }
    return SourceSnapshot(inputs, moduleFingerprints(), shortNames, shards, owners)
  }

  private fun incremental(
    prev: SourceSnapshot,
    inputs: IndexInputs,
    start: Invalidations,
  ): SourceSnapshot {
    if (start.dirty.isEmpty()) {
      // Output-only compiler-option changes update inputs without touching any shard.
      return if (prev.inputs == inputs) prev else prev.withInputs(inputs)
    }
    val shards = LinkedHashMap(prev.shards)
    val owners = LinkedHashMap<VirtualFile, MutableSet<VirtualFile>>(prev.dependencyOwners.size)
    for ((dependencyFile, ownerSet) in prev.dependencyOwners) {
      owners[dependencyFile] = LinkedHashSet(ownerSet)
    }
    for (virtualFile in start.dirty) {
      ProgressManager.checkCanceled()
      val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
      if (file == null || !file.isValid || !containsRelevantAnnotation(file, prev.shortNames)) {
        withoutShard(shards, owners, virtualFile)
        continue
      }
      applyShard(shards, owners, virtualFile, shardFor(file, virtualFile in start.forced))
    }
    return SourceSnapshot(inputs, prev.moduleFingerprints, prev.shortNames, shards, owners)
  }

  private fun applyShard(
    shards: MutableMap<VirtualFile, FileShard>,
    owners: MutableMap<VirtualFile, MutableSet<VirtualFile>>,
    virtualFile: VirtualFile,
    shard: FileShard,
  ) {
    withoutShard(shards, owners, virtualFile)
    if (shard === FileShard.EMPTY) return
    shards[virtualFile] = shard
    for (dependencyFile in shard.dependencyFiles) {
      owners.getOrPut(dependencyFile) { linkedSetOf() }.add(virtualFile)
    }
  }

  private fun withoutShard(
    shards: MutableMap<VirtualFile, FileShard>,
    owners: MutableMap<VirtualFile, MutableSet<VirtualFile>>,
    virtualFile: VirtualFile,
  ) {
    val previous = shards.remove(virtualFile) ?: return
    for (dependencyFile in previous.dependencyFiles) {
      val ownerSet = owners[dependencyFile] ?: continue
      ownerSet -= virtualFile
      if (ownerSet.isEmpty()) {
        owners.remove(dependencyFile)
      }
    }
  }

  private fun aggregateSource(shards: Collection<FileShard>): SourceAggregate {
    val bindings = mutableListOf<KaBinding>()
    val consumers = mutableListOf<ConsumerEntry>()
    val graphs = mutableListOf<KaGraphDeclaration>()
    val contributions = mutableListOf<ContributionEntry>()
    val assistedSites = mutableListOf<AssistedSite>()
    val bindingContainers = mutableListOf<BindingContainerEntry>()
    val factoryInputs = linkedMapOf<FactoryInputEntry.Id, FactoryInputEntry>()
    for (shard in shards) {
      ProgressManager.checkCanceled()
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

  private fun projectSweepShortNames(fallbackOptions: MetroOptions): Set<String> {
    val ids = linkedSetOf<ClassId>()
    ids += sweepAnnotationIds(fallbackOptions)
    val service = project.service<MetroIdeProjectService>()
    for (module in ModuleManager.getInstance(project).modules) {
      ProgressManager.checkCanceled()
      val state = service.state(module)
      if (state.isEnabled) ids += sweepAnnotationIds(state.options)
    }
    return ids.mapToSet { it.shortClassName.asString() }
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

  /** Files containing any Metro-relevant annotation by short name, via stub indexes. */
  private fun candidateFiles(shortNames: Set<String>): Set<KtFile> {
    val searchScope = GlobalSearchScope.projectScope(project)
    val files = LinkedHashSet<KtFile>()
    for (shortName in shortNames.sorted()) {
      ProgressManager.checkCanceled()
      for (entry in KotlinAnnotationsIndex[shortName, project, searchScope]) {
        ProgressManager.checkCanceled()
        files += entry.containingKtFile
      }
    }
    return files
  }

  private fun containsRelevantAnnotation(file: KtFile, shortNames: Set<String>): Boolean {
    return PsiTreeUtil.collectElementsOfType(file, KtAnnotationEntry::class.java).any { entry ->
      entry.shortName?.asString() in shortNames
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
    if (cached === FileShard.EMPTY && file.textLength > 0) {
      val state = file.metroIdeState()
      if (state.isEnabled) return FileShardBuilder(file.project, state.options).buildShard(file)
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
    if (file == null) return
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
    val newlyRelevant = !alreadyIndexed && containsRelevantAnnotation(file, state.shortNames)
    // Applies even to indexed files and files with recorded owners. A file can mix indexed
    // declarations with constants or aliases that unrelated shards reference without any
    // recorded dependency edge.
    val globalSemanticChange = hasSharedSemanticDeclarations(file)
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

    val dirty = linkedSetOf(virtualFile)
    if (ownerFiles != null) {
      dirty += ownerFiles
    }
    val forced: Set<VirtualFile>
    if (globalSemanticChange) {
      dirty += state.shards.keys
      forced = state.shards.keys
    } else {
      forced = emptySet()
    }
    invalidations.updateAndGet { ledger ->
      var updated = ledger.withDirty(dirty, forced)
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
  private fun hasSharedSemanticDeclarations(file: KtFile): Boolean {
    // Consts commonly live inside objects and companion objects, so recurse through all nesting.
    fun KtDeclaration.isShared(): Boolean =
      when {
        this is KtTypeAlias -> true
        this is KtProperty && hasModifier(KtTokens.CONST_KEYWORD) -> true
        this is KtClassOrObject -> declarations.any { it.isShared() }
        else -> false
      }
    return file.declarations.any { it.isShared() }
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
    val requested = linkedSetOf<VirtualFile>()
    val dirty = linkedSetOf<VirtualFile>()
    for (file in files) {
      val virtualFile = file.virtualFile ?: continue
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
    if (requested.isEmpty() && dirty.isEmpty()) {
      return
    }
    invalidations.updateAndGet { ledger ->
      var updated = ledger
      for (virtualFile in requested) updated = updated.withRequested(virtualFile)
      if (dirty.isNotEmpty()) {
        updated = updated.withDirty(dirty)
      }
      updated
    }
    if (dirty.isNotEmpty()) {
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
    snapshots.clear()
    libraryShards.clear()
    fingerprintsByModuleState.clear()
  }

  private companion object {
    const val MAX_CACHED_INDEXES = 8
    const val MAX_CACHED_OPTION_FINGERPRINTS = 64
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
) {
  fun bumpGeneration(): Invalidations =
    Invalidations(stamp + 1, generation + 1, dirty, forced, requested)

  fun withDirty(
    files: Set<VirtualFile>,
    forcedFiles: Set<VirtualFile> = emptySet(),
  ): Invalidations =
    Invalidations(stamp + 1, generation + 1, dirty + files, forced + forcedFiles, requested)

  /** Requested files feed a future cold sweep and do not invalidate published results. */
  fun withRequested(file: VirtualFile): Invalidations =
    if (file in requested) {
      this
    } else {
      Invalidations(stamp + 1, generation, dirty, forced, requested + file)
    }

  /** The ledger after a successful incremental publish. */
  fun drainDirty(): Invalidations =
    Invalidations(stamp + 1, generation, emptySet(), emptySet(), requested)

  /** The ledger after a successful cold sweep, which also consumed requested files. */
  fun drainAll(): Invalidations =
    Invalidations(stamp + 1, generation, emptySet(), emptySet(), emptySet())
}

/** An immutable source view. Incremental passes copy it with only the changed shards replaced. */
private class SourceSnapshot(
  val inputs: IndexInputs,
  val moduleFingerprints: Map<Module, IndexOptionsFingerprint>,
  val shortNames: Set<String>,
  val shards: Map<VirtualFile, FileShard>,
  /** Dependency file to the shard files that must rebuild when it changes. */
  val dependencyOwners: Map<VirtualFile, Set<VirtualFile>>,
) {
  fun withInputs(newInputs: IndexInputs): SourceSnapshot =
    SourceSnapshot(newInputs, moduleFingerprints, shortNames, shards, dependencyOwners)
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
