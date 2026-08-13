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
import dev.zacsweers.metro.idea.model.KaGraphNode
import dev.zacsweers.metro.idea.model.KaTypeKey
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
  private val inFlight = ConcurrentHashMap<SnapshotKey, Job>()
  private val invalidationPending = AtomicBoolean()
  private val disposed = AtomicBoolean()
  private val stateLock = Any()
  private val dirtyFiles = linkedSetOf<VirtualFile>()
  private val forciblyRebuiltFiles = linkedSetOf<VirtualFile>()
  private val requestedFiles = linkedSetOf<VirtualFile>()
  private val dependencyOwners = linkedMapOf<VirtualFile, MutableSet<VirtualFile>>()
  private var sourceState: SourceState? = null
  private var generation: Long = 0
  private var lastResolveFromLibraries =
    MetroSettings.getInstance(project).state.resolveFromLibraries
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
    synchronized(stateLock) {
      val sourceInputs = sourceState?.inputs
      val compilerSettingsChanged = sourceInputs?.compilerSettings != inputs.compilerSettings
      if (!compilerSettingsChanged && sourceInputs?.roots == inputs.roots) {
        snapshots[key]
          ?.takeIf { it.matches(generation, inputs.roots) }
          ?.let {
            return it.index
          }
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
    synchronized(stateLock) {
      if (resolveFromLibraries == lastResolveFromLibraries) return
      lastResolveFromLibraries = resolveFromLibraries
      generation++
    }
    inFlight.values.forEach(Job::cancel)
    notifyListeners(restartDaemon = false)
  }

  private fun scheduleBuild(module: Module, key: SnapshotKey) {
    val existing = inFlight[key]
    if (existing != null && existing.isActive) return

    val job =
      scope.launch(start = CoroutineStart.LAZY) {
        try {
          val built = smartReadAction(project) { buildCurrentIndex(module, key) }
          if (built === BindingIndex.EMPTY) return@launch
          withContext(Dispatchers.EDT) {
            val current = snapshots[key]
            if (!project.isDisposed && current?.index === built) {
              notifyListeners(restartDaemon = true)
            }
          }
        } catch (exception: CancellationException) {
          throw exception
        }
      }
    val registered =
      inFlight.compute(key) { _, previous ->
        if (previous != null && previous.isActive) previous else job
      }
    if (registered !== job) {
      job.cancel()
      return
    }
    job.invokeOnCompletion { inFlight.remove(key, job) }
    job.start()
  }

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

    synchronized(stateLock) {
      val inputs = currentInputs()
      val sourceInputs = sourceState?.inputs
      if (sourceInputs == inputs) {
        snapshots[key]
          ?.takeIf { it.matches(generation, inputs.roots) }
          ?.let {
            return it.index
          }
      }

      val state = reconcileSourceState(moduleState.options, inputs)
      snapshots[key]
        ?.takeIf { it.matches(generation, inputs.roots) }
        ?.let {
          return it.index
        }
      val source = aggregateSource(state.shards.values)
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
      snapshots[key] = IndexSnapshot(index, generation, inputs.roots)
      return index
    }
  }

  /**
   * Refreshes only changed declarations; roots and compiler configuration require a fresh sweep.
   */
  private fun reconcileSourceState(options: MetroOptions, inputs: IndexInputs): SourceState {
    val existing = sourceState
    val compilerSettingsChanged =
      existing != null && existing.inputs.compilerSettings != inputs.compilerSettings
    val fingerprintChanged =
      compilerSettingsChanged && existing.moduleFingerprints != moduleFingerprints()
    if (existing == null || existing.inputs.roots != inputs.roots || fingerprintChanged) {
      if (fingerprintChanged) generation++
      val shortNames = projectSweepShortNames(options)
      val rebuilt = SourceState(inputs, moduleFingerprints(), shortNames, linkedMapOf())
      // A cancelled cold sweep must not leave the previous state paired with partial owner maps.
      sourceState = null
      dependencyOwners.clear()
      dirtyFiles.clear()
      forciblyRebuiltFiles.clear()
      for (file in candidateFiles(shortNames)) {
        ProgressManager.checkCanceled()
        val virtualFile = file.virtualFile ?: continue
        updateShard(rebuilt, virtualFile, shardFor(file))
      }
      // Stub loading can create more PSI files synchronously while this cold sweep is running.
      while (requestedFiles.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val virtualFile = requestedFiles.first()
        if (virtualFile.isValid && virtualFile !in rebuilt.shards) {
          val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
          if (file != null && containsRelevantAnnotation(file, shortNames)) {
            updateShard(rebuilt, virtualFile, shardFor(file))
          }
        }
        requestedFiles.remove(virtualFile)
      }
      sourceState = rebuilt
      return rebuilt
    }

    if (existing.inputs.compilerSettings != inputs.compilerSettings) {
      existing.inputs = inputs
    }

    if (dirtyFiles.isEmpty()) return existing
    while (dirtyFiles.isNotEmpty()) {
      ProgressManager.checkCanceled()
      val virtualFile = dirtyFiles.first()
      val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
      if (file == null || !file.isValid) {
        removeShard(existing, virtualFile)
        dirtyFiles.remove(virtualFile)
        forciblyRebuiltFiles.remove(virtualFile)
        continue
      }
      val isRelevant = containsRelevantAnnotation(file, existing.shortNames)
      if (!isRelevant) {
        removeShard(existing, virtualFile)
        dirtyFiles.remove(virtualFile)
        forciblyRebuiltFiles.remove(virtualFile)
        continue
      }
      val forceRebuild = virtualFile in forciblyRebuiltFiles
      updateShard(existing, virtualFile, shardFor(file, forceRebuild))
      dirtyFiles.remove(virtualFile)
      forciblyRebuiltFiles.remove(virtualFile)
    }
    return existing
  }

  private fun updateShard(state: SourceState, virtualFile: VirtualFile, shard: FileShard) {
    removeShard(state, virtualFile)
    if (shard === FileShard.EMPTY) return
    state.shards[virtualFile] = shard
    for (dependency in shard.cacheDependencies) {
      val dependencyFile = dependency.virtualFile ?: continue
      dependencyOwners.getOrPut(dependencyFile) { linkedSetOf() }.add(virtualFile)
    }
  }

  private fun removeShard(state: SourceState, virtualFile: VirtualFile) {
    val previous = state.shards.remove(virtualFile) ?: return
    for (dependency in previous.cacheDependencies) {
      val dependencyFile = dependency.virtualFile ?: continue
      val owners = dependencyOwners[dependencyFile] ?: continue
      owners -= virtualFile
      if (owners.isEmpty()) dependencyOwners.remove(dependencyFile)
    }
  }

  private fun aggregateSource(shards: Collection<FileShard>): SourceAggregate {
    val bindings = mutableListOf<KaBinding>()
    val consumers = mutableListOf<ConsumerEntry>()
    val graphs = mutableListOf<KaGraphNode>()
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
    if (forceRebuild) {
      val state = file.metroIdeState()
      return if (state.isEnabled) IndexBuilder(file.project, state.options).buildShard(file)
      else FileShard.EMPTY
    }
    val cached =
      CachedValuesManager.getCachedValue(file) {
        // Shards use their owning module's options. Explicit dependency files cover inherited graph
        // members and factory includes even when those files contain no Metro annotations
        // themselves.
        val state = file.metroIdeState()
        val shard =
          if (state.isEnabled) IndexBuilder(file.project, state.options).buildShard(file)
          else FileShard.EMPTY
        CachedValueProvider.Result.create(
          shard,
          file,
          KotlinCompilerSettingsTracker.getInstance(file.project),
          ProjectRootModificationTracker.getInstance(file.project),
          *shard.cacheDependencies.toTypedArray(),
        )
      }
    if (cached === FileShard.EMPTY && file.textLength > 0) {
      val state = file.metroIdeState()
      if (state.isEnabled) return IndexBuilder(file.project, state.options).buildShard(file)
    }
    return cached
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
    var invalidated = false
    synchronized(stateLock) {
      val state = sourceState
      if (state == null) {
        requestedFiles += virtualFile
        return
      }
      if (virtualFile in state.shards || virtualFile in dirtyFiles) return
      if (!containsRelevantAnnotation(file, state.shortNames)) return
      dirtyFiles += virtualFile
      generation++
      invalidated = true
    }
    if (invalidated) inFlight.values.forEach(Job::cancel)
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
    synchronized(stateLock) {
      val state = sourceState
      if (state == null) {
        if (structuralChange) requestedFiles += virtualFile
        return
      }
      if (structuralChange && virtualFile !in state.shards) requestedFiles += virtualFile
      val ownerFiles = dependencyOwners[virtualFile]
      val alreadyIndexed = virtualFile in state.shards
      val newlyRelevant = !alreadyIndexed && containsRelevantAnnotation(file, state.shortNames)
      val globalSemanticChange =
        !alreadyIndexed && ownerFiles.isNullOrEmpty() && hasSharedSemanticDeclarations(file)
      val affectsIndexedDeclarations =
        alreadyIndexed ||
          !ownerFiles.isNullOrEmpty() ||
          newlyRelevant ||
          structuralChange ||
          globalSemanticChange
      if (!affectsIndexedDeclarations) return

      dirtyFiles += virtualFile
      if (ownerFiles != null) dirtyFiles += ownerFiles
      if (globalSemanticChange) {
        dirtyFiles += state.shards.keys
        forciblyRebuiltFiles += state.shards.keys
      }
      generation++
    }
    inFlight.values.forEach(Job::cancel)
    scheduleInvalidationNotification()
  }

  /** Unannotated aliases/constants can change keys across unrelated files without PSI pointers. */
  private fun hasSharedSemanticDeclarations(file: KtFile): Boolean {
    return file.declarations.any { declaration ->
      declaration is KtTypeAlias ||
        declaration is KtProperty && declaration.hasModifier(KtTokens.CONST_KEYWORD)
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

    var changed = false
    synchronized(stateLock) {
      val state = sourceState ?: return
      for (file in files) {
        val virtualFile = file.virtualFile ?: continue
        if (virtualFile !in state.shards) requestedFiles += virtualFile
        val owners = dependencyOwners[virtualFile]
        val relevant = virtualFile in state.shards || !owners.isNullOrEmpty()
        val newlyRelevant = containsRelevantAnnotation(file, state.shortNames)
        if (!relevant && !newlyRelevant) continue
        dirtyFiles += virtualFile
        if (owners != null) dirtyFiles += owners
        changed = true
      }
      if (changed) generation++
    }
    if (changed) {
      inFlight.values.forEach(Job::cancel)
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
    inFlight.values.forEach(Job::cancel)
    listeners.clear()
    snapshots.clear()
    libraryShards.clear()
    fingerprintsByModuleState.clear()
    forciblyRebuiltFiles.clear()
    requestedFiles.clear()
  }

  private companion object {
    const val MAX_CACHED_INDEXES = 8
    const val MAX_CACHED_OPTION_FINGERPRINTS = 64
  }
}

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

private data class SourceState(
  var inputs: IndexInputs,
  val moduleFingerprints: Map<Module, IndexOptionsFingerprint>,
  val shortNames: Set<String>,
  val shards: MutableMap<VirtualFile, FileShard>,
)

private data class SourceAggregate(
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
  val graphs: List<KaGraphNode>,
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
      if (consumer.multibindingId != null || consumer.key.qualifier != null) continue
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
