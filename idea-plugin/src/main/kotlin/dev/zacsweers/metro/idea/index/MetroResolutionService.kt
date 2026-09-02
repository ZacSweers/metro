// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.facet.Facet
import com.intellij.facet.FacetManager
import com.intellij.facet.FacetManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.ShutDownTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import dev.zacsweers.metro.idea.MetroDaemonRestartService
import dev.zacsweers.metro.idea.MetroIdeProjectService
import dev.zacsweers.metro.idea.MetroSettings
import dev.zacsweers.metro.idea.checkCanceledEvery
import dev.zacsweers.metro.idea.index.snapshot.IndexInputs
import dev.zacsweers.metro.idea.index.snapshot.PreparedResolutionSnapshot
import dev.zacsweers.metro.idea.index.snapshot.ResolutionSnapshotBuilder
import dev.zacsweers.metro.idea.index.snapshot.ResolutionSnapshotTarget
import dev.zacsweers.metro.idea.index.snapshot.SnapshotKey
import dev.zacsweers.metro.idea.index.snapshot.SourceSnapshot
import dev.zacsweers.metro.idea.index.snapshot.SourceSnapshotChanges
import dev.zacsweers.metro.idea.metroIdeState
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingIndexBuilder
import dev.zacsweers.metro.idea.model.BindingIndexModuleView
import dev.zacsweers.metro.idea.model.BindingIndexResolutionInputs
import dev.zacsweers.metro.idea.model.FileOrdinal
import dev.zacsweers.metro.idea.model.FileOrdinalTable
import dev.zacsweers.metro.idea.model.IndexGenerationToken
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.ModuleViewId
import dev.zacsweers.metro.idea.model.sourcePointerIdentity
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScope
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsListener
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias

/**
 * Builds and caches the binding data used by editor decorations, the graph browser, and validation.
 *
 * A cold snapshot discovers candidate Kotlin files through stub indexes. Later PSI changes rebuild
 * only the changed file and shards that explicitly depend on it. Binary declarations live in a
 * separate cache so unrelated source edits do not repeat classpath analysis. One coordinator owns
 * pending changes and publishes complete immutable generations for concurrent readers. Explicit
 * queries use the current generation. In manual refresh mode, editor features keep the last
 * presentation generation until the user refreshes it.
 *
 * The supplied scope owns background execution. IntelliJ injects a scope using Dispatchers.Default.
 */
@Service(Service.Level.PROJECT)
class MetroResolutionService
private constructor(
  private val project: Project,
  private val scope: CoroutineScope,
  private val requestPolicy: IndexRequestPolicy,
) : Disposable {
  constructor(
    project: Project,
    scope: CoroutineScope,
  ) : this(project, scope, IndexRequestPolicy.Production)

  private val snapshotBuilder =
    ResolutionSnapshotBuilder(
      project,
      onShardRead = ::seedSharedDeclarationFingerprints,
      captureResolutionInputs = { builder, files -> builder.captureResolutionInputs(files) },
    )

  /** Latest progress of the coordinator's current index build. */
  internal val indexBuildProgress: StateFlow<IndexBuildProgress?>
    field: MutableStateFlow<IndexBuildProgress?> = MutableStateFlow(null)
  /** Broadcasts refresh signals to all active tool-window listeners. */
  private val indexChanges =
    MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  /** Coalesces pending refresh requests until the EDT can notify listeners. */
  private val notificationRequests = Channel<Unit>(Channel.CONFLATED)
  /** Cancels EDT delivery and disposable-bound collectors when this service is disposed. */
  private val notificationScope =
    CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
  /** Previous alias and constant contents, used to recognize edits that affect other files. */
  private val sharedDeclarationFingerprints = mutableMapOf<VirtualFile, String>()
  /** Remembers irrelevant files so repeated queries leave the current index intact. */
  private val knownIrrelevantFiles = mutableSetOf<VirtualFile>()
  /** Readers observe complete generations together with their browser and lifecycle state. */
  private val publishedResolution = MutableStateFlow(PublishedResolution.EMPTY)
  private val isDisposed: Boolean
    get() = publishedResolution.value.isDisposed

  private val retryAutomaticIndexAfterStateWarmup: (Module) -> Unit = { module ->
    if (automaticallyRefreshGraphData && !isDisposed && !project.isDisposed && !module.isDisposed) {
      val index = index(module, IndexRequestMode.AUTOMATIC_BACKGROUND)
      if (index !== BindingIndex.EMPTY) {
        // Classification may restore cached data before warmup finishes. Its caller already saw
        // an empty index. The module-state service restarts highlighting after this callback.
        notifyListeners(restartDaemon = false)
      }
    }
  }
  private val retryExplicitIndexAfterStateWarmup: (Module) -> Unit = { module ->
    if (!isDisposed && !project.isDisposed && !module.isDisposed) {
      index(module, IndexRequestMode.BACKGROUND)
    }
  }

  /** Only the resolution coordinator reads or changes this pending source work. */
  private val pendingDirtyFiles = linkedSetOf<VirtualFile>()
  private val pendingRequestedFiles = linkedSetOf<VirtualFile>()
  /** Structural changes can alter captured modules without changing file modification stamps. */
  private val pendingForceRebuildFiles = linkedSetOf<VirtualFile>()
  private var forceAllFiles = false
  private var semanticRevision = 0L
  private var sourceSnapshot: SourceSnapshot? = null
  /** Accepts callbacks from any thread and wakes the coordinator with coalesced requests. */
  private val ingress =
    ResolutionIngress(
      coalescingKey = ResolutionCoordinatorEvent::coalescingKey,
      merge = ::mergeResolutionCoordinatorEvents,
    )
  /** Combines requests by module while retaining every caller waiting for the result. */
  private val pendingBuilds = linkedMapOf<Module, PendingIndexBuild>()
  /** Retains requested modules for later automatic rebuilds. */
  private val demandedModules = linkedSetOf<Module>()
  private var pendingPsiChanges = PendingPsiChanges()
  @Volatile private var psiClassificationObserver: (() -> Unit)? = null
  private var projectInputsPending = false
  private var settingsPending = false
  private var pendingManualRefresh: ManualRefreshRequest? = null
  /** Clocks captured at the last event drain, used to reject superseded work. */
  private var coordinatorSnapshot = ingress.snapshot()
  private val coordinatorJob: Job
  private val pendingFilePresentationRequests = linkedMapOf<FilePresentationKey, BindingIndex>()
  private val completedFilePresentationBundles = ArrayDeque<CompletedFilePresentationBundle>()
  private val pendingFilePresentationAnchorRequests =
    linkedMapOf<FilePresentationKey, PendingFilePresentationAnchorBuild>()
  private val completedFilePresentationAnchorBundles =
    ArrayDeque<CompletedFilePresentationAnchorBundle>()
  private val pendingCoordinatorBarriers = mutableListOf<CompletableDeferred<Unit>>()

  /** The coordinator caps this cache and publishes immutable copies for readers. */
  private val filePresentationBundles =
    LinkedHashMap<FilePresentationKey, FilePresentationBundle>(MAX_FILE_PRESENTATION_BUNDLES)
  /** Active attempts retain their worker slots until completion, including during cancellation. */
  private val filePresentationBuilds =
    mutableMapOf<FilePresentationKey, FilePresentationBuildAttempt>()
  private val filePresentationAnchorBuilds =
    mutableMapOf<FilePresentationKey, FilePresentationAnchorBuildAttempt>()

  /** Declaration identities captured with each index generation's source ranges. */
  private val declarationAnchorSignatures =
    ConcurrentHashMap<
      IndexGenerationToken,
      Map<BindingIndex.SourcePointerIdentity, DeclarationAnchorSignature>,
    >()
  private var nextFilePresentationAttemptId = 0L

  private var lastResolveFromLibraries =
    MetroSettings.getInstance(project).state.resolveFromLibraries
  private var lastAutomaticallyRefreshGraphData = automaticallyRefreshGraphData

  init {
    PsiManager.getInstance(project)
      .addPsiTreeChangeListener(
        object : PsiTreeChangeAdapter() {
          override fun beforeChildRemoval(event: PsiTreeChangeEvent) =
            psiChanged(
              event,
              structuralChange = isFileStructureChange(event),
              oldTreeMayDisappear = true,
            )

          override fun beforeChildMovement(event: PsiTreeChangeEvent) =
            psiChanged(
              event,
              structuralChange = isFileStructureChange(event),
              oldTreeMayDisappear = true,
            )

          override fun beforePropertyChange(event: PsiTreeChangeEvent) =
            psiChanged(event, structuralChange = isFileStructureChange(event))

          override fun beforeChildReplacement(event: PsiTreeChangeEvent) =
            psiChanged(event, oldTreeMayDisappear = true)

          override fun beforeChildrenChange(event: PsiTreeChangeEvent) =
            psiChanged(event, oldTreeMayDisappear = true)

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
    coordinatorJob = scope.launch { resolutionCoordinator() }
    notificationScope.launch(Dispatchers.EDT) { deliverIndexChanges() }
  }

  /** Processes resolution requests in priority order. */
  private suspend fun resolutionCoordinator() {
    try {
      ingress.wakeups.consumeEach {
        drainCoordinatorEvents()
        while (!isDisposed && !project.isDisposed && hasRunnableCoordinatorWork()) {
          val coordinatorMetadataMayHaveChanged =
            when {
              !pendingPsiChanges.isEmpty -> {
                processPendingPsiChanges()
                true
              }
              projectInputsPending -> {
                processPendingProjectInputs()
                true
              }
              settingsPending -> {
                processPendingSettings()
                true
              }
              pendingManualRefresh != null -> {
                processResolutionCandidate(manualRefresh = true)
                true
              }
              completeSatisfiedBuildRequests() -> false
              pendingBuilds.isNotEmpty() -> {
                processResolutionCandidate(manualRefresh = false)
                true
              }
              completedFilePresentationBundles.isNotEmpty() -> {
                publishCompletedFileBundles()
                false
              }
              completedFilePresentationAnchorBundles.isNotEmpty() -> {
                publishCompletedFilePresentationAnchors()
                false
              }
              else -> {
                startPendingFileBundles()
                false
              }
            }
          if (coordinatorMetadataMayHaveChanged) {
            publishCoordinatorMetadata()
          }
          drainCoordinatorEvents()
        }
        completeCoordinatorBarriers()
        if (pendingBuilds.isEmpty()) publishIndexBuildProgress(null)
      }
    } finally {
      cancelCoordinatorWork()
      publishIndexBuildProgress(null)
    }
  }

  private fun drainCoordinatorEvents() {
    val drained = ingress.drain()
    coordinatorSnapshot = drained.snapshot
    for (event in drained.events) {
      when (event) {
        is ResolutionCoordinatorEvent.Psi -> mergePendingPsiChanges(event.changes)
        ResolutionCoordinatorEvent.ProjectInputs -> projectInputsPending = true
        ResolutionCoordinatorEvent.Settings -> settingsPending = true
        is ResolutionCoordinatorEvent.PresentationDemand -> demandedModules += event.module
        is ResolutionCoordinatorEvent.Build -> mergePendingBuild(event)
        is ResolutionCoordinatorEvent.ManualRefresh -> {
          pendingManualRefresh = ManualRefreshRequest(event.requestId)
        }
        is ResolutionCoordinatorEvent.FilePresentationRequest -> {
          if (isPresentationIndexPublished(event.index) && !hasFilePresentationWork(event.key)) {
            pendingFilePresentationRequests.putIfAbsent(event.key, event.index)
          }
        }
        is ResolutionCoordinatorEvent.FilePresentationComplete -> {
          val activeAttempt = filePresentationBuilds[event.key]
          val accepted = activeAttempt?.id == event.attemptId && activeAttempt.index === event.index
          if (!accepted) {
            continue
          }
          filePresentationBuilds.remove(event.key)
          val bundle =
            when (val outcome = event.outcome) {
              FilePresentationBuildOutcome.Canceled,
              FilePresentationBuildOutcome.Failed -> null
              is FilePresentationBuildOutcome.Succeeded -> outcome.bundle
            }
          if (
            bundle != null &&
              isPresentationIndexPublished(event.index) &&
              event.key !in filePresentationBundles &&
              completedFilePresentationBundles.none { it.key == event.key }
          ) {
            completedFilePresentationBundles +=
              CompletedFilePresentationBundle(
                event.index,
                event.key,
                bundle,
              )
          }
        }
        is ResolutionCoordinatorEvent.FilePresentationAnchorRequest -> {
          val publishedBundle = filePresentationBundles[event.key]
          if (
            isPresentationIndexPublished(event.index) &&
              publishedBundle != null &&
              publishedBundle === event.baseBundle &&
              !publishedBundle.anchorsAreCurrent(event.modificationStamp)
          ) {
            val active = filePresentationAnchorBuilds[event.key]
            val sameActiveRequest =
              active != null &&
                active.baseBundle === event.baseBundle &&
                active.modificationStamp == event.modificationStamp
            if (!sameActiveRequest) {
              pendingFilePresentationAnchorRequests[event.key] =
                PendingFilePresentationAnchorBuild(
                  event.index,
                  event.key,
                  event.baseBundle,
                  event.modificationStamp,
                )
              active?.job?.cancel()
            }
          }
        }
        is ResolutionCoordinatorEvent.FilePresentationAnchorComplete -> {
          val activeAttempt = filePresentationAnchorBuilds[event.key]
          val accepted =
            activeAttempt?.id == event.attemptId &&
              activeAttempt.index === event.index &&
              activeAttempt.baseBundle === event.baseBundle &&
              activeAttempt.modificationStamp == event.modificationStamp
          if (!accepted) continue
          filePresentationAnchorBuilds.remove(event.key)
          val bundle =
            when (val outcome = event.outcome) {
              FilePresentationBuildOutcome.Canceled,
              FilePresentationBuildOutcome.Failed -> null
              is FilePresentationBuildOutcome.Succeeded -> outcome.bundle
            }
          val pending = pendingFilePresentationAnchorRequests[event.key]
          val superseded =
            pending != null &&
              (pending.baseBundle !== event.baseBundle ||
                pending.modificationStamp != event.modificationStamp)
          if (
            bundle != null &&
              !superseded &&
              isPresentationBundlePublished(event.index, event.key, event.baseBundle) &&
              bundle.sharesSemanticData(event.baseBundle) &&
              bundle.anchorsAreCurrent(event.modificationStamp) &&
              completedFilePresentationAnchorBundles.none { it.key == event.key }
          ) {
            pendingFilePresentationAnchorRequests.remove(event.key)
            completedFilePresentationAnchorBundles +=
              CompletedFilePresentationAnchorBundle(
                event.index,
                event.key,
                event.baseBundle,
                event.modificationStamp,
                bundle,
              )
          }
        }
        is ResolutionCoordinatorEvent.TestBarrier -> {
          pendingCoordinatorBarriers += event.completion
        }
      }
    }
  }

  /** Returns whether queued work can run before another request or worker completion arrives. */
  private fun hasRunnableCoordinatorWork(): Boolean {
    return !pendingPsiChanges.isEmpty ||
      projectInputsPending ||
      settingsPending ||
      pendingManualRefresh != null ||
      pendingBuilds.isNotEmpty() ||
      completedFilePresentationBundles.isNotEmpty() ||
      completedFilePresentationAnchorBundles.isNotEmpty() ||
      hasStartableFilePresentationWork()
  }

  private fun mergePendingPsiChanges(added: PendingPsiChanges) {
    val files = pendingPsiChanges.files.toMutableMap()
    for ((file, change) in added.files) {
      files[file] = files[file]?.merge(change) ?: change
    }
    pendingPsiChanges =
      PendingPsiChanges(
        files = files,
        directories = pendingPsiChanges.directories + added.directories,
      )
  }

  private fun mergePendingBuild(event: ResolutionCoordinatorEvent.Build) {
    demandedModules += event.module
    val existing = pendingBuilds[event.module]
    if (existing == null) {
      pendingBuilds[event.module] =
        PendingIndexBuild(
          module = event.module,
          intent = event.intent,
          waiters = event.completions.toMutableList(),
          requiresPresentationRefresh = event.requiresPresentationRefresh,
        )
    } else {
      existing.upgrade(event.intent, event.requiresPresentationRefresh)
      existing.waiters += event.completions
    }
  }

  /** Completes requests when their data is already available to every active reader. */
  private fun completeSatisfiedBuildRequests(): Boolean {
    if (
      pendingBuilds.isEmpty() || pendingManualRefresh != null || isDisposed || project.isDisposed
    ) {
      return false
    }
    val publication = publishedResolution.value
    if (publication.isDisposed) return false
    val current = publication.current
    if (!generationIsCurrent(current)) return false
    val presentation = publication.presentation
    val needsCurrentPresentation = automaticallyRefreshGraphData
    // Explicit queries can refresh current data while manual mode keeps the old presentation.
    // Reenabling automatic refresh must update both before queued requests are satisfied.
    if (needsCurrentPresentation && !generationIsCurrent(presentation)) return false
    if (isDisposed || project.isDisposed || publishedResolution.value.current !== current) {
      return false
    }

    val satisfied = mutableListOf<PendingIndexBuild>()
    val requests = pendingBuilds.entries.iterator()
    while (requests.hasNext()) {
      val request = requests.next().value
      if (current.index(request.module) === BindingIndex.EMPTY) continue
      if (needsCurrentPresentation && presentation.index(request.module) === BindingIndex.EMPTY) {
        continue
      }
      requests.remove()
      satisfied += request
    }
    completeBuildRequests(satisfied, IndexBuildOutcome.PUBLISHED)
    val refreshPresentation =
      needsCurrentPresentation && satisfied.any { it.requiresPresentationRefresh }
    if (refreshPresentation) {
      // A daemon pass can return empty while a no-op change is being classified. Reusing the
      // generation still owes that reader a refresh so it can request its presentation bundle.
      notifyListeners(restartDaemon = true)
    }
    return satisfied.isNotEmpty()
  }

  /** Retains the batch when classification or its conservative fallback is interrupted. */
  private suspend fun processPendingPsiChanges() {
    val batch = pendingPsiChanges
    pendingPsiChanges = PendingPsiChanges()
    try {
      classifyAndApplyPsiChanges(batch)
    } catch (_: ProcessCanceledException) {
      mergePendingPsiChanges(batch)
      yield()
    } catch (exception: CancellationException) {
      mergePendingPsiChanges(batch)
      throw exception
    }
  }

  /** Falls back to full invalidation when classification fails without cancellation. */
  private suspend fun classifyAndApplyPsiChanges(batch: PendingPsiChanges) {
    try {
      val classified =
        smartReadAction(project) {
          checkPsiClassificationActive()
          classifyPsiChanges(batch)
        }
      psiClassificationObserver?.invoke()
      checkPsiClassificationActive()
      applyClassifiedPsiChanges(classified)
    } catch (failure: Throwable) {
      if (failure is ProcessCanceledException) throw failure
      if (failure is CancellationException) throw failure
      checkPsiClassificationActive()
      logger<MetroResolutionService>().warn("Metro PSI invalidation failed", failure)
      val requested = failedClassificationRequests(batch)
      recordForceAllInvalidation()
      pendingRequestedFiles += requested
      val sourceModulesMayHaveChanged =
        batch.directories.isNotEmpty() || batch.files.values.any { it.structuralChange }
      if (sourceModulesMayHaveChanged) {
        pendingForceRebuildFiles += requested
        pendingForceRebuildFiles += sourceSnapshot?.shardOrder.orEmpty()
      }
      snapshotBuilder.evictLibraryShards(
        ProjectRootModificationTracker.getInstance(project).modificationCount
      )
      notifyListeners(restartDaemon = true)
    }
  }

  private suspend fun processPendingProjectInputs() {
    projectInputsPending = false
    try {
      readAction {
        reconcileProjectInputs()
      }
    } catch (_: ProcessCanceledException) {
      projectInputsPending = true
      yield()
    } catch (exception: CancellationException) {
      projectInputsPending = true
      throw exception
    } catch (failure: Throwable) {
      logger<MetroResolutionService>().warn("Metro project input reconciliation failed", failure)
      semanticRevision++
      snapshotBuilder.evictLibraryShards(
        ProjectRootModificationTracker.getInstance(project).modificationCount
      )
      notifyListeners(restartDaemon = false)
    }
  }

  private fun processPendingSettings() {
    settingsPending = false
    val state = MetroSettings.getInstance(project).state
    val resolveFromLibraries = state.resolveFromLibraries
    val resolveFromLibrariesChanged = lastResolveFromLibraries != resolveFromLibraries
    lastResolveFromLibraries = resolveFromLibraries
    val automaticallyRefresh = state.automaticallyRefreshGraphData
    val automaticallyRefreshChanged = lastAutomaticallyRefreshGraphData != automaticallyRefresh
    lastAutomaticallyRefreshGraphData = automaticallyRefresh
    if (!resolveFromLibrariesChanged && !automaticallyRefreshChanged) return
    if (automaticallyRefreshChanged) {
      updatePublishedResolution { it.copy(manualStaleNotificationSent = false) }
    }

    if (automaticallyRefreshChanged && !automaticallyRefresh) {
      discardAutomaticPendingBuilds()
      updatePublishedResolution { publication ->
        val presentationRevision = publication.presentation.semanticRevision
        val refreshRevision =
          if (presentationRevision == ResolutionGeneration.EMPTY_REVISION) semanticRevision
          else presentationRevision
        publication.copy(graphBrowserRefreshRevision = refreshRevision)
      }
    }

    if (resolveFromLibrariesChanged) {
      semanticRevision++
      if (!resolveFromLibraries) snapshotBuilder.clearLibraryShards()
      snapshotBuilder.evictLibraryShards(
        ProjectRootModificationTracker.getInstance(project).modificationCount
      )
    }

    if (automaticallyRefreshChanged && automaticallyRefresh) {
      for (module in demandedModules) {
        if (module.isDisposed || module in pendingBuilds) continue
        pendingBuilds[module] =
          PendingIndexBuild(module, IndexBuildIntent.AUTOMATIC, mutableListOf())
      }
    }
    notifyListeners(restartDaemon = automaticallyRefreshChanged && automaticallyRefresh)
  }

  private fun publishCoordinatorMetadata() {
    if (!pendingPsiChanges.isEmpty || projectInputsPending || settingsPending) return
    val publication = publishedResolution.value
    val currentTrackedFiles = sourceSnapshot?.shardOrder.orEmpty()
    val trackedFilesUnchanged =
      publication.trackedSourceFiles.size == currentTrackedFiles.size &&
        publication.trackedSourceFiles.containsAll(currentTrackedFiles)
    val irrelevantFilesUnchanged = publication.knownIrrelevantFiles == knownIrrelevantFiles
    val metadataUnchanged =
      publication.classifiedSemanticClock == coordinatorSnapshot.semanticClock &&
        publication.latestSemanticRevision == semanticRevision &&
        trackedFilesUnchanged &&
        irrelevantFilesUnchanged
    if (metadataUnchanged) return

    val trackedSourceFiles =
      if (trackedFilesUnchanged) publication.trackedSourceFiles else currentTrackedFiles.toSet()
    val irrelevantFiles =
      if (irrelevantFilesUnchanged) publication.knownIrrelevantFiles
      else knownIrrelevantFiles.toSet()
    updatePublishedResolution { previous ->
      previous.copy(
        classifiedSemanticClock = coordinatorSnapshot.semanticClock,
        latestSemanticRevision = semanticRevision,
        trackedSourceFiles = trackedSourceFiles,
        knownIrrelevantFiles = irrelevantFiles,
      )
    }
  }

  private fun hasFilePresentationWork(key: FilePresentationKey): Boolean {
    return key in filePresentationBuilds ||
      key in filePresentationAnchorBuilds ||
      key in filePresentationBundles ||
      completedFilePresentationBundles.any { it.key == key }
  }

  /** A worker needs a free slot and exclusive access to its file's presentation. */
  private fun hasStartableFilePresentationWork(): Boolean {
    val activeBuilds = filePresentationBuilds.size + filePresentationAnchorBuilds.size
    if (activeBuilds >= MAX_CONCURRENT_FILE_PRESENTATION_BUILDS) return false
    val activeKeys = filePresentationBuilds.keys + filePresentationAnchorBuilds.keys
    return pendingFilePresentationRequests.keys.any { it !in activeKeys } ||
      pendingFilePresentationAnchorRequests.keys.any { it !in activeKeys }
  }

  private fun isPresentationBundlePublished(
    index: BindingIndex,
    key: FilePresentationKey,
    bundle: FilePresentationBundle,
  ): Boolean {
    return isPresentationIndexPublished(index) &&
      publishedResolution.value.filePresentationBundles[key] === bundle
  }

  private fun completeCoordinatorBarriers() {
    if (pendingCoordinatorBarriers.isEmpty()) return
    pendingCoordinatorBarriers.forEach { it.complete(Unit) }
    pendingCoordinatorBarriers.clear()
  }

  /** Applies a pure state transformation and preserves disposal as a terminal state. */
  private fun updatePublishedResolution(
    update: (PublishedResolution) -> PublishedResolution
  ): PublishedResolution? {
    val updated = publishedResolution.updateAndGet { publication ->
      if (publication.isDisposed || project.isDisposed) publication else update(publication)
    }
    return updated.takeUnless { it.isDisposed || project.isDisposed }
  }

  private fun startPendingFileBundles() {
    var available =
      MAX_CONCURRENT_FILE_PRESENTATION_BUILDS -
        filePresentationBuilds.size -
        filePresentationAnchorBuilds.size
    if (available <= 0) return
    val requests = pendingFilePresentationRequests.entries.iterator()
    while (requests.hasNext() && available > 0) {
      val (key, index) = requests.next()
      if (!isPresentationIndexPublished(index)) {
        requests.remove()
        continue
      }
      if (hasFilePresentationWork(key)) {
        requests.remove()
        continue
      }
      requests.remove()
      val attemptId = ++nextFilePresentationAttemptId
      val job =
        scope.async(start = CoroutineStart.LAZY) {
          try {
            currentCoroutineContext().ensureActive()
            if (!isPresentationIndexPublished(index)) {
              return@async FilePresentationBuildOutcome.Canceled
            }
            val semanticBundle = index.withResolutionSession { session ->
              FilePresentationBundleBuilder(
                  index = index,
                  session = session,
                  file = key.file,
                  declarationAnchorSignatures =
                    declarationAnchorSignatures[index.generationToken].orEmpty(),
                )
                .build()
            }
            val bundle =
              smartReadAction(project) {
                val ktFile = PsiManager.getInstance(project).findFile(key.file) as? KtFile
                ktFile?.let(semanticBundle::rebuildAnchors) ?: semanticBundle
              }
            currentCoroutineContext().ensureActive()
            FilePresentationBuildOutcome.Succeeded(bundle)
          } catch (exception: CancellationException) {
            throw exception
          } catch (_: ProcessCanceledException) {
            FilePresentationBuildOutcome.Canceled
          } catch (failure: Throwable) {
            logger<MetroResolutionService>()
              .warn("Metro file presentation build failed for ${key.file.name}", failure)
            FilePresentationBuildOutcome.Failed
          }
        }
      filePresentationBuilds[key] = FilePresentationBuildAttempt(attemptId, index, job)
      reportFilePresentationCompletion(job) { outcome ->
        ResolutionCoordinatorEvent.FilePresentationComplete(index, key, attemptId, outcome)
      }
      job.start()
      available--
    }
    if (available > 0) startPendingFilePresentationAnchors(available)
  }

  /** Refreshes declaration locations using slots left after starting new presentation builds. */
  private fun startPendingFilePresentationAnchors(availableSlots: Int) {
    var available = availableSlots
    val requests = pendingFilePresentationAnchorRequests.entries.iterator()
    while (requests.hasNext() && available > 0) {
      val (key, request) = requests.next()
      if (!isPresentationBundlePublished(request.index, key, request.baseBundle)) {
        requests.remove()
        continue
      }
      if (key in filePresentationBuilds || key in filePresentationAnchorBuilds) continue
      requests.remove()
      val attemptId = ++nextFilePresentationAttemptId
      val job =
        scope.async(start = CoroutineStart.LAZY) {
          try {
            currentCoroutineContext().ensureActive()
            if (!isPresentationBundlePublished(request.index, key, request.baseBundle)) {
              return@async FilePresentationBuildOutcome.Canceled
            }
            val bundle =
              smartReadAction(project) {
                val ktFile = PsiManager.getInstance(project).findFile(key.file) as? KtFile
                if (ktFile?.modificationStamp != request.modificationStamp) {
                  null
                } else {
                  request.baseBundle.rebuildAnchors(ktFile)
                }
              }
            currentCoroutineContext().ensureActive()
            if (bundle != null && bundle.anchorsAreCurrent(request.modificationStamp)) {
              FilePresentationBuildOutcome.Succeeded(bundle)
            } else {
              FilePresentationBuildOutcome.Canceled
            }
          } catch (exception: CancellationException) {
            throw exception
          } catch (_: ProcessCanceledException) {
            FilePresentationBuildOutcome.Canceled
          } catch (failure: Throwable) {
            logger<MetroResolutionService>()
              .warn("Metro file presentation anchor build failed for ${key.file.name}", failure)
            FilePresentationBuildOutcome.Failed
          }
        }
      filePresentationAnchorBuilds[key] =
        FilePresentationAnchorBuildAttempt(
          attemptId,
          request.index,
          request.baseBundle,
          request.modificationStamp,
          job,
        )
      reportFilePresentationCompletion(job) { outcome ->
        ResolutionCoordinatorEvent.FilePresentationAnchorComplete(
          request.index,
          key,
          attemptId,
          request.baseBundle,
          request.modificationStamp,
          outcome,
        )
      }
      job.start()
      available--
    }
  }

  /** Awaiting also releases attempts canceled before their lazy worker entered its body. */
  private fun reportFilePresentationCompletion(
    worker: Deferred<FilePresentationBuildOutcome>,
    event: (FilePresentationBuildOutcome) -> ResolutionCoordinatorEvent,
  ) {
    scope.launch {
      val outcome =
        try {
          worker.await()
        } catch (_: CancellationException) {
          // Service cancellation owns cleanup. An individually canceled worker releases its slot
          // here.
          currentCoroutineContext().ensureActive()
          FilePresentationBuildOutcome.Canceled
        }
      ingress.submit { event(outcome) }
    }
  }

  private fun publishCompletedFileBundles() {
    while (completedFilePresentationBundles.isNotEmpty()) {
      val completed = completedFilePresentationBundles.removeFirst()
      if (!isPresentationIndexPublished(completed.index)) continue
      filePresentationBundles[completed.key] = completed.bundle
      while (filePresentationBundles.size > MAX_FILE_PRESENTATION_BUNDLES) {
        val eldest = filePresentationBundles.entries.iterator()
        eldest.next()
        eldest.remove()
      }
      val published = updatePublishedResolution { publication ->
        publication.copy(filePresentationBundles = filePresentationBundles.toMap())
      }
      if (published != null && !isDisposed && !project.isDisposed) {
        project.service<MetroDaemonRestartService>().requestRestart()
      }
    }
  }

  /** Updates declaration locations while keeping the previously computed binding results. */
  private fun publishCompletedFilePresentationAnchors() {
    while (completedFilePresentationAnchorBundles.isNotEmpty()) {
      val completed = completedFilePresentationAnchorBundles.removeFirst()
      if (!isPresentationIndexPublished(completed.index)) continue
      val current = filePresentationBundles[completed.key] ?: continue
      if (current !== completed.baseBundle) continue
      if (!completed.bundle.sharesSemanticData(current)) continue
      if (!completed.bundle.anchorsAreCurrent(completed.modificationStamp)) continue
      val pending = pendingFilePresentationAnchorRequests[completed.key]
      if (
        pending != null &&
          (pending.baseBundle !== completed.baseBundle ||
            pending.modificationStamp != completed.modificationStamp)
      ) {
        continue
      }
      val updatedBundles = LinkedHashMap(filePresentationBundles)
      updatedBundles[completed.key] = completed.bundle
      val published = updatePublishedResolution { publication ->
        if (publication.filePresentationBundles[completed.key] !== completed.baseBundle) {
          publication
        } else {
          publication.copy(filePresentationBundles = updatedBundles.toMap())
        }
      }
      val accepted = published?.filePresentationBundles?.get(completed.key) === completed.bundle
      if (!accepted) continue
      filePresentationBundles[completed.key] = completed.bundle
      if (!isDisposed && !project.isDisposed) {
        project.service<MetroDaemonRestartService>().requestRestart()
      }
    }
  }

  private fun cancelCoordinatorWork() {
    completeBuildRequests(pendingBuilds.values, IndexBuildOutcome.CANCELED)
    pendingBuilds.clear()
    pendingFilePresentationRequests.clear()
    completedFilePresentationBundles.clear()
    pendingFilePresentationAnchorRequests.clear()
    completedFilePresentationAnchorBundles.clear()
    filePresentationBuilds.values.forEach { it.job.cancel() }
    filePresentationBuilds.clear()
    filePresentationAnchorBuilds.values.forEach { it.job.cancel() }
    filePresentationAnchorBuilds.clear()
    filePresentationBundles.clear()
    completeCoordinatorBarriers()
  }

  private suspend fun processResolutionCandidate(manualRefresh: Boolean) {
    val existingPublication = publishedResolution.value
    val retainedTokens =
      existingPublication.current.indexGenerationTokens +
        existingPublication.presentation.indexGenerationTokens
    declarationAnchorSignatures.keys.removeIf { token -> token !in retainedTokens }
    val manualRequest =
      if (manualRefresh) pendingManualRefresh.also { pendingManualRefresh = null } else null
    val requests = pendingBuilds.toMap()
    pendingBuilds.clear()
    val intent =
      when {
        manualRequest != null -> IndexBuildIntent.MANUAL_REFRESH
        requests.values.any { it.intent == IndexBuildIntent.EXPLICIT } -> IndexBuildIntent.EXPLICIT
        else -> IndexBuildIntent.AUTOMATIC
      }
    if (intent == IndexBuildIntent.AUTOMATIC && !automaticallyRefreshGraphData) {
      completeBuildRequests(requests.values, IndexBuildOutcome.SKIPPED)
      return
    }

    val buildSnapshot = coordinatorSnapshot
    val generationToken = IndexGenerationToken.create()
    val progress = IndexBuildProgressReporter(::publishIndexBuildProgress)
    progress.phase(IndexBuildPhase.QUEUED)
    val candidate =
      try {
        val capturedInvalidations = capturePendingInvalidations()
        smartReadAction(project) {
          val targets =
            resolutionTargets(
              if (manualRequest != null) {
                null
              } else {
                demandedModules
              }
            )
          if (manualRequest != null) {
            for ((_, modules) in targets) {
              demandedModules += modules
            }
          }
          if (resolutionCandidateIsSuperseded(buildSnapshot, manualRequest)) {
            throw ResolutionCandidateSupersededException()
          }
          collectResolutionCandidate(
            targets = targets,
            progress = progress,
            generationToken = generationToken,
            buildSnapshot = buildSnapshot,
            manualRequest = manualRequest,
            capturedInvalidations = capturedInvalidations,
          )
        }
      } catch (_: ResolutionCandidateSupersededException) {
        requeueResolutionCandidate(requests, manualRequest)
        yield()
        return
      } catch (_: ProcessCanceledException) {
        requeueResolutionCandidate(requests, manualRequest)
        yield()
        return
      } catch (exception: CancellationException) {
        requeueResolutionCandidate(requests, manualRequest)
        throw exception
      } catch (failure: Throwable) {
        logger<MetroResolutionService>().warn("Metro resolution preparation failed", failure)
        completeBuildRequests(requests.values, IndexBuildOutcome.FAILED)
        return
      }

    val indexesByKey =
      try {
        currentCoroutineContext().ensureActive()
        val builtIndexes =
          candidate.prepared.buildIndexes {
            if (resolutionCandidateIsSuperseded(buildSnapshot, manualRequest, candidate.inputs)) {
              throw ResolutionCandidateSupersededException()
            }
          }
        // Cancellation of the service scope must stop this candidate before publication.
        currentCoroutineContext().ensureActive()
        builtIndexes
      } catch (_: ResolutionCandidateSupersededException) {
        requeueResolutionCandidate(requests, manualRequest)
        yield()
        return
      } catch (_: ProcessCanceledException) {
        requeueResolutionCandidate(requests, manualRequest)
        yield()
        return
      } catch (exception: CancellationException) {
        requeueResolutionCandidate(requests, manualRequest)
        throw exception
      } catch (failure: Throwable) {
        logger<MetroResolutionService>().warn("Metro resolution build failed", failure)
        completeBuildRequests(requests.values, IndexBuildOutcome.FAILED)
        return
      }

    val completedInputs = currentInputs()
    val latestIngress = ingress.snapshot()
    val sameSemanticClock = latestIngress.semanticClock == buildSnapshot.semanticClock
    val latestManual =
      manualRequest == null || latestIngress.latestManualRequestId == manualRequest.id
    val sourceIsCurrent = candidate.source == null || candidate.source.inputs == completedInputs
    val completeTargetSet = indexesByKey.keys == candidate.prepared.targetKeys
    val metadataMatches = indexesByKey.values.all { it.generationToken === generationToken }
    if (
      !sameSemanticClock ||
        !latestManual ||
        completedInputs != candidate.inputs ||
        !sourceIsCurrent ||
        !completeTargetSet ||
        !metadataMatches ||
        isDisposed ||
        project.isDisposed
    ) {
      requeueResolutionCandidate(requests, manualRequest)
      yield()
      return
    }

    val generation =
      ResolutionGeneration(
        token = generationToken,
        inputs = candidate.inputs,
        semanticRevision = candidate.semanticRevision,
        source = candidate.source,
        indexesByKey = indexesByKey,
        keysByModule = candidate.keysByModule,
      )
    val publishPresentation = manualRequest != null || automaticallyRefreshGraphData
    val published = updatePublishedResolution { previous ->
      previous.copy(
        current = generation,
        presentation = if (publishPresentation) generation else previous.presentation,
        classifiedSemanticClock = buildSnapshot.semanticClock,
        latestSemanticRevision = candidate.semanticRevision,
        trackedSourceFiles = candidate.source?.shardOrder?.toSet().orEmpty(),
        knownIrrelevantFiles = knownIrrelevantFiles.toSet(),
        graphBrowserRefreshRevision =
          if (manualRequest != null) candidate.semanticRevision
          else previous.graphBrowserRefreshRevision,
        manualStaleNotificationSent = manualRequest == null && previous.manualStaleNotificationSent,
      )
    }
    if (published == null) {
      completeBuildRequests(requests.values, IndexBuildOutcome.CANCELED)
      return
    }
    sourceSnapshot = candidate.source
    consumeCapturedInvalidations(candidate.consumedInvalidations)
    snapshotBuilder.evictLibraryShards(
      currentRoots = candidate.inputs.roots,
      activeFingerprints = candidate.source?.moduleFingerprints?.values?.toSet().orEmpty(),
    )
    retainPublishedFilePresentationBundles()
    completeBuildRequests(requests.values, IndexBuildOutcome.PUBLISHED)

    if (manualRequest != null) {
      notifyListeners(restartDaemon = true, forceDaemonRestart = true)
    } else {
      notifyIndexPublished(intent)
    }
  }

  private fun resolutionTargets(modules: Set<Module>?): List<ResolutionSnapshotTarget> {
    demandedModules.removeIf(Module::isDisposed)
    val resolveFromLibraries = MetroSettings.getInstance(project).state.resolveFromLibraries
    val projectStateService = project.service<MetroIdeProjectService>()
    val selectedModules = modules ?: ModuleManager.getInstance(project).modules.toSet()
    val modulesByKey = linkedMapOf<SnapshotKey, MutableList<Module>>()
    for (module in selectedModules) {
      ProgressManager.checkCanceled()
      if (module.isDisposed) continue
      val state = projectStateService.state(module)
      if (!state.isEnabled) continue
      val key = SnapshotKey(snapshotBuilder.fingerprintFor(state), resolveFromLibraries)
      modulesByKey.getOrPut(key) { mutableListOf() } += module
    }
    return modulesByKey.map { (key, groupedModules) ->
      ResolutionSnapshotTarget(key, groupedModules)
    }
  }

  private fun resolutionCandidateIsSuperseded(
    buildSnapshot: ResolutionIngressSnapshot,
    manualRequest: ManualRefreshRequest?,
    expectedInputs: IndexInputs? = null,
  ): Boolean {
    if (isDisposed || project.isDisposed) return true
    val latest = ingress.snapshot()
    if (latest.semanticClock != buildSnapshot.semanticClock) return true
    if (manualRequest != null && latest.latestManualRequestId != manualRequest.id) return true
    return expectedInputs != null && currentInputs() != expectedInputs
  }

  private fun requeueResolutionCandidate(
    requests: Map<Module, PendingIndexBuild>,
    manualRequest: ManualRefreshRequest?,
  ) {
    for ((module, request) in requests) {
      val existing = pendingBuilds[module]
      if (existing == null) {
        pendingBuilds[module] = request
      } else {
        existing.upgrade(request.intent, request.requiresPresentationRefresh)
        existing.waiters += request.waiters
      }
    }
    if (manualRequest != null && pendingManualRefresh == null) {
      pendingManualRefresh = manualRequest
    }
  }

  private fun completeBuildRequests(
    requests: Collection<PendingIndexBuild>,
    outcome: IndexBuildOutcome,
  ) {
    for (request in requests) {
      request.waiters.forEach { waiter -> waiter.complete(outcome) }
    }
  }

  private fun notifyIndexPublished(intent: IndexBuildIntent) {
    val restartDaemon = intent == IndexBuildIntent.EXPLICIT || automaticallyRefreshGraphData
    notifyListeners(
      restartDaemon = restartDaemon,
      forceDaemonRestart = intent == IndexBuildIntent.EXPLICIT,
    )
  }

  /** Returns current graph data after applying pending invalidations. */
  internal fun currentIndex(element: PsiElement): BindingIndex {
    val file = element as? KtFile ?: element.containingFile as? KtFile
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return BindingIndex.EMPTY
    if (file != null) enrollRequestedFile(file)
    return currentIndex(module)
  }

  /** Returns the published presentation generation without scheduling analysis in manual mode. */
  internal fun presentationIndex(element: PsiElement): BindingIndex {
    val file = element as? KtFile ?: element.containingFile as? KtFile
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return BindingIndex.EMPTY
    if (file != null) enrollRequestedFile(file)
    ingress.submit { ResolutionCoordinatorEvent.PresentationDemand(module) }
    return index(module, automaticPresentationRequestMode())
  }

  /** Returns a cached presentation bundle or schedules its background construction. */
  internal fun presentationBundle(element: KtElement): FilePresentationBundle? {
    val ktFile = element.containingFile as? KtFile ?: return null
    val file = ktFile.virtualFile
    val index = presentationIndex(element)
    if (index === BindingIndex.EMPTY) return null
    val key = FilePresentationKey(index.generationToken, file)
    publishedResolution.value.filePresentationBundles[key]?.let { bundle ->
      val modificationStamp = ktFile.modificationStamp
      if (!bundle.anchorsAreCurrent(modificationStamp)) {
        ingress.submit {
          ResolutionCoordinatorEvent.FilePresentationAnchorRequest(
            index,
            key,
            bundle,
            modificationStamp,
          )
        }
      }
      return bundle
    }
    ingress.submit { ResolutionCoordinatorEvent.FilePresentationRequest(index, key) }
    return null
  }

  private fun isPresentationIndexPublished(index: BindingIndex): Boolean {
    return !(index === BindingIndex.EMPTY || isDisposed || project.isDisposed) &&
      publishedResolution.value.presentation.contains(index)
  }

  private fun retainPublishedFilePresentationBundles() {
    val activeTokens = publishedResolution.value.presentation.indexGenerationTokens
    declarationAnchorSignatures.keys.removeIf { token -> token !in activeTokens }
    pendingFilePresentationRequests.keys.removeIf { key ->
      key.generationToken !in activeTokens
    }
    completedFilePresentationBundles.removeAll { completed ->
      completed.key.generationToken !in activeTokens
    }
    pendingFilePresentationAnchorRequests.keys.removeIf { key ->
      key.generationToken !in activeTokens
    }
    completedFilePresentationAnchorBundles.removeAll { completed ->
      completed.key.generationToken !in activeTokens
    }
    filePresentationBundles.keys.removeIf { key -> key.generationToken !in activeTokens }
    val builds = filePresentationBuilds.entries.iterator()
    while (builds.hasNext()) {
      val (key, attempt) = builds.next()
      if (key.generationToken in activeTokens) continue
      attempt.job.cancel()
    }
    val anchorBuilds = filePresentationAnchorBuilds.entries.iterator()
    while (anchorBuilds.hasNext()) {
      val (key, attempt) = anchorBuilds.next()
      if (key.generationToken in activeTokens) continue
      attempt.job.cancel()
    }
    updatePublishedResolution { publication ->
      publication.copy(filePresentationBundles = filePresentationBundles.toMap())
    }
  }

  @TestOnly internal fun index(element: PsiElement): BindingIndex = currentIndex(element)

  /** Returns a current cached index and schedules missing settings work in the background. */
  internal fun cachedIndex(element: PsiElement): BindingIndex {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return BindingIndex.EMPTY
    return index(module, IndexRequestMode.CACHE_ONLY)
  }

  /** Returns the distinct current indexes that can contain an exact Find Usages target. */
  internal fun usageIndexes(element: PsiElement): List<BindingIndex> {
    val module = ModuleUtilCore.findModuleForPsiElement(element)
    if (module != null) return currentIndex(element).asUsageIndexList()
    return distinctUsageIndexes { currentIndex(it) }
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
   * Returns current binding data for [module]. Production EDT callers schedule a background build
   * and receive an empty index until it finishes. Other callers wait for the coordinator. Callers
   * in a read action use [retryCancelledIndexBuild] to release the read lock before waiting.
   */
  internal fun currentIndex(module: Module): BindingIndex {
    return index(module, currentRequestMode())
  }

  @TestOnly internal fun index(module: Module): BindingIndex = currentIndex(module)

  /** Returns a cached graph-browser index, building in the background only after first use. */
  internal fun indexForToolWindow(module: Module): BindingIndex {
    val requestMode =
      if (!isGraphBrowserActivated) {
        if (automaticallyRefreshGraphData) IndexRequestMode.CACHE_ONLY
        else IndexRequestMode.STALE_CACHE_ONLY
      } else {
        automaticPresentationRequestMode()
      }
    ingress.submit { ResolutionCoordinatorEvent.PresentationDemand(module) }
    val index = index(module, requestMode)
    if (index !== BindingIndex.EMPTY) {
      val indexIsCurrent = isCurrent(index)
      val previous = publishedResolution.getAndUpdate { publication ->
        if (publication.isDisposed || publication.graphBrowserActivated) {
          publication
        } else {
          val refreshRevision =
            if (indexIsCurrent && publication.current.contains(index))
              publication.latestSemanticRevision
            else publication.graphBrowserRefreshRevision
          publication.copy(
            graphBrowserActivated = true,
            graphBrowserRefreshRevision = refreshRevision,
          )
        }
      }
      if (!previous.isDisposed && !previous.graphBrowserActivated) {
        // currentIndexes() may have skipped earlier modules while the browser was inactive. Ask the
        // tree to make one active pass so those modules can schedule their own snapshots.
        scheduleInvalidationNotification()
      }
    }
    return index
  }

  internal val isGraphBrowserActivated: Boolean
    get() = publishedResolution.value.graphBrowserActivated

  internal fun activateGraphBrowser() {
    updatePublishedResolution { it.copy(graphBrowserActivated = true) }
  }

  /** Builds and publishes a generation containing every pending change. */
  internal fun refreshGraphData() {
    activateGraphBrowser()
    ingress.submit(manualRefresh = true) { ticket ->
      ResolutionCoordinatorEvent.ManualRefresh(ticket.eventClock)
    }
  }

  internal val isManualGraphDataRefreshRequired: Boolean
    get() {
      val publication = publishedResolution.value
      if (automaticallyRefreshGraphData || !publication.graphBrowserActivated) return false
      val ingressSnapshot = ingress.snapshot()
      val classificationPending =
        publication.classifiedSemanticClock < ingressSnapshot.semanticClock
      return classificationPending ||
        publication.latestSemanticRevision > publication.graphBrowserRefreshRevision
    }

  @TestOnly
  internal fun resetGraphBrowserActivation() {
    updatePublishedResolution {
      it.copy(graphBrowserActivated = false, manualStaleNotificationSent = false)
    }
  }

  /**
   * Resolves current graph data through cancellable smart reads and invokes [onResult] on the EDT.
   * The retry boundary releases the read lock while waiting for a build. Explicit lookups complete
   * independently of the manual browser's refresh notifications.
   */
  internal fun findGraphAsync(
    classId: ClassId,
    file: VirtualFile?,
    onResult: (KaGraphDeclaration?) -> Unit,
  ): Job {
    return scope.launch {
      if (project.isDisposed) return@launch
      val graph = retryCancelledIndexBuild {
        smartReadAction(project) {
          val psiFile = file?.let { PsiManager.getInstance(project).findFile(it) } as? KtFile
          psiFile?.let { sourceFile ->
            val module = ModuleUtilCore.findModuleForPsiElement(sourceFile)
            if (module == null) {
              null
            } else {
              enrollRequestedFile(sourceFile)
              currentIndex(module).graphs.firstOrNull {
                ProgressManager.checkCanceled()
                it.classId == classId && it.pointer.virtualFile == file
              }
            }
          }
        }
      }
      withContext(Dispatchers.EDT) {
        if (!project.isDisposed) onResult(graph)
      }
    }
  }

  /** Waits until the coordinator has drained every event submitted before this call. */
  @TestOnly
  internal suspend fun awaitCoordinatorBarrier() {
    val completion = CompletableDeferred<Unit>()
    if (ingress.submit { ResolutionCoordinatorEvent.TestBarrier(completion) } != null) {
      completion.await()
    }
  }

  private val automaticallyRefreshGraphData: Boolean
    get() = MetroSettings.getInstance(project).state.automaticallyRefreshGraphData

  private fun currentRequestMode(): IndexRequestMode {
    return requestPolicy.currentRequestMode(ApplicationManager.getApplication().isDispatchThread)
  }

  private fun automaticPresentationRequestMode(): IndexRequestMode {
    if (!automaticallyRefreshGraphData) return IndexRequestMode.STALE_CACHE_ONLY
    return requestPolicy.automaticPresentationRequestMode()
  }

  /** Reads cached data first, then applies the request mode's scheduling and waiting behavior. */
  private fun index(module: Module, requestMode: IndexRequestMode): BindingIndex {
    val publication = publishedResolution.value
    when (requestMode) {
      IndexRequestMode.STALE_CACHE_ONLY -> return publication.presentation.index(module)
      IndexRequestMode.CACHE_ONLY -> {
        val current = publication.current
        if (generationIsCurrent(current)) {
          current
            .index(module)
            .takeUnless { it === BindingIndex.EMPTY }
            ?.let {
              return it
            }
        }
      }
      IndexRequestMode.AUTOMATIC_BACKGROUND -> {
        val presentation = publication.presentation
        if (generationIsCurrent(presentation)) {
          presentation
            .index(module)
            .takeUnless { it === BindingIndex.EMPTY }
            ?.let {
              return it
            }
        }
      }
      IndexRequestMode.BACKGROUND,
      IndexRequestMode.SYNCHRONOUS -> {
        val current = publication.current
        if (generationIsCurrent(current)) {
          current
            .index(module)
            .takeUnless { it === BindingIndex.EMPTY }
            ?.let {
              return it
            }
        }
      }
    }

    val projectStateService = project.service<MetroIdeProjectService>()
    val moduleState =
      when (requestMode) {
        IndexRequestMode.AUTOMATIC_BACKGROUND ->
          projectStateService.currentStateOrSchedule(
            module,
            retryAutomaticIndexAfterStateWarmup,
          )
        IndexRequestMode.BACKGROUND ->
          projectStateService.currentStateOrSchedule(
            module,
            retryExplicitIndexAfterStateWarmup,
          )
        IndexRequestMode.SYNCHRONOUS -> projectStateService.state(module)
        else -> projectStateService.currentStateOrSchedule(module)
      } ?: return BindingIndex.EMPTY
    if (!moduleState.isEnabled) return BindingIndex.EMPTY
    if (requestMode == IndexRequestMode.CACHE_ONLY) return BindingIndex.EMPTY

    return when (requestMode) {
      IndexRequestMode.AUTOMATIC_BACKGROUND -> {
        scheduleBuild(
          module,
          IndexBuildIntent.AUTOMATIC,
          requiresPresentationRefresh = true,
        )
        BindingIndex.EMPTY
      }
      IndexRequestMode.BACKGROUND -> {
        scheduleBuild(module, IndexBuildIntent.EXPLICIT)
        BindingIndex.EMPTY
      }
      else -> {
        val application = ApplicationManager.getApplication()
        if (application.isReadAccessAllowed) {
          // Release this read action before waiting for the coordinator's smart read.
          val completion = CompletableDeferred<IndexBuildOutcome>()
          scheduleBuild(module, IndexBuildIntent.EXPLICIT, completion)
          throw ResolutionBuildPendingException(completion)
        }
        val completion = CompletableDeferred<IndexBuildOutcome>()
        scheduleBuild(module, IndexBuildIntent.EXPLICIT, completion)
        val outcome = runBlockingCancellable { completion.await() }
        if (outcome != IndexBuildOutcome.PUBLISHED) return BindingIndex.EMPTY
        val current = publishedResolution.value.current
        if (!generationIsCurrent(current)) return BindingIndex.EMPTY
        current.index(module)
      }
    }
  }

  private fun generationIsCurrent(generation: ResolutionGeneration): Boolean {
    if (generation === ResolutionGeneration.EMPTY) return false
    val publication = publishedResolution.value
    val ingressSnapshot = ingress.snapshot()
    return generation.semanticRevision == publication.latestSemanticRevision &&
      publication.classifiedSemanticClock == ingressSnapshot.semanticClock &&
      generation.inputs == currentInputs()
  }

  /** Returns true when [index] matches the current project state. */
  internal fun isCurrent(index: BindingIndex): Boolean {
    if (index === BindingIndex.EMPTY) return false
    val current = publishedResolution.value.current
    return current.contains(index) && generationIsCurrent(current)
  }

  /** Notifies a tool window when a fresh background index is ready; callbacks run on the EDT. */
  internal fun addIndexListener(parentDisposable: Disposable, listener: () -> Unit) {
    collectForDisposable(indexChanges, parentDisposable) { listener() }
  }

  /** Reports index-build progress to tool-window listeners on the EDT. */
  internal fun addIndexBuildProgressListener(
    parentDisposable: Disposable,
    listener: (IndexBuildProgress?) -> Unit,
  ) {
    collectForDisposable(indexBuildProgress, parentDisposable, listener)
  }

  /**
   * Subscribes immediately and marshals each Swing callback to the EDT for its owner's lifetime.
   */
  private fun <T> collectForDisposable(
    events: Flow<T>,
    parentDisposable: Disposable,
    listener: (T) -> Unit,
  ) {
    val job =
      notificationScope.launch(Dispatchers.EDT, start = CoroutineStart.UNDISPATCHED) {
        events.collect { value ->
          withContext(Dispatchers.EDT) {
            if (!isDisposed && !project.isDisposed) listener(value)
          }
        }
      }
    Disposer.register(parentDisposable) { job.cancel() }
  }

  /** Queues settings reconciliation and resumes automatic presentation builds. */
  internal fun settingsChanged() {
    ingress.submit(semanticChange = true) { ResolutionCoordinatorEvent.Settings }
  }

  private fun discardAutomaticPendingBuilds() {
    val iterator = pendingBuilds.entries.iterator()
    while (iterator.hasNext()) {
      val request = iterator.next().value
      if (request.intent != IndexBuildIntent.AUTOMATIC) continue
      iterator.remove()
      request.waiters.forEach { it.complete(IndexBuildOutcome.SKIPPED) }
    }
  }

  /** Roots/facet changes should refresh open windows even when no editor asks for the index. */
  private fun projectInputsChanged() {
    ApplicationManager.getApplication().invokeLater {
      if (!isDisposed && !project.isDisposed) {
        ingress.submit(semanticChange = true) { ResolutionCoordinatorEvent.ProjectInputs }
      }
    }
  }

  /** A sync can change many modules together; compare their semantic options once per batch. */
  private fun reconcileProjectInputs() {
    knownIrrelevantFiles.clear()
    val snapshot = sourceSnapshot
    if (snapshot == null) {
      // An already-open window may be waiting for Metro to be configured for the first time.
      scheduleInvalidationNotification()
      return
    }
    val inputs = currentInputs()
    val rootsChanged = snapshot.inputs.roots != inputs.roots
    val compilerSettingsChanged = snapshot.inputs.compilerSettings != inputs.compilerSettings
    if (!rootsChanged && !compilerSettingsChanged) return

    val currentFingerprints =
      if (compilerSettingsChanged) snapshotBuilder.moduleFingerprints() else null
    val semanticSettingsChanged =
      compilerSettingsChanged && snapshot.moduleFingerprints != currentFingerprints
    if (!rootsChanged && !semanticSettingsChanged) {
      if (snapshot.inputs != inputs) updatePublishedInputs(snapshot, inputs)
      // Reenabling Metro can restore the last built options while its retained data is stale.
      val current = publishedResolution.value.current
      val needsBuild =
        current.indexesByKey.isEmpty() || current.semanticRevision != semanticRevision
      if (needsBuild) {
        scheduleInvalidationNotification()
      }
      return
    }

    semanticRevision++
    snapshotBuilder.evictLibraryShards(inputs.roots)
    scheduleInvalidationNotification()
  }

  /** Updates project-input versions when changed settings leave the binding data unchanged. */
  private fun updatePublishedInputs(snapshot: SourceSnapshot, inputs: IndexInputs) {
    val updatedSource = snapshot.withInputs(inputs)
    sourceSnapshot = updatedSource
    updatePublishedResolution { publication ->
      val current = publication.current.withUpdatedInputs(snapshot, updatedSource, inputs)
      val presentation =
        if (publication.presentation === publication.current) {
          current
        } else {
          publication.presentation.withUpdatedInputs(snapshot, updatedSource, inputs)
        }
      if (current === publication.current && presentation === publication.presentation) {
        publication
      } else {
        publication.copy(current = current, presentation = presentation)
      }
    }
  }

  /** Queues an index request and retains any presentation refresh owed after its cache miss. */
  private fun scheduleBuild(
    module: Module,
    intent: IndexBuildIntent,
    completion: CompletableDeferred<IndexBuildOutcome>? = null,
    requiresPresentationRefresh: Boolean = false,
  ) {
    if (isDisposed || project.isDisposed || module.isDisposed) {
      completion?.complete(IndexBuildOutcome.CANCELED)
      return
    }
    if (intent == IndexBuildIntent.AUTOMATIC && !automaticallyRefreshGraphData) {
      completion?.complete(IndexBuildOutcome.SKIPPED)
      return
    }
    val accepted = ingress.submit {
      val completions = mutableListOf<CompletableDeferred<IndexBuildOutcome>>()
      if (completion != null) completions += completion
      ResolutionCoordinatorEvent.Build(module, intent, completions, requiresPresentationRefresh)
    }
    if (accepted == null) {
      completion?.complete(IndexBuildOutcome.CANCELED)
    }
  }

  /** Captures a detached snapshot while retaining revision and supersession ownership. */
  private fun collectResolutionCandidate(
    targets: List<ResolutionSnapshotTarget>,
    progress: IndexBuildProgressReporter,
    generationToken: IndexGenerationToken,
    buildSnapshot: ResolutionIngressSnapshot,
    manualRequest: ManualRefreshRequest?,
    capturedInvalidations: CapturedInvalidations,
  ): CollectedResolutionCandidate {
    val inputs = currentInputs()
    val previous = sourceSnapshot
    val compilerSettingsChanged =
      previous != null && previous.inputs.compilerSettings != inputs.compilerSettings
    val fingerprintChanged =
      compilerSettingsChanged && previous.moduleFingerprints != snapshotBuilder.moduleFingerprints()
    val candidateInvalidations =
      if (fingerprintChanged) {
        capturedInvalidations.copy(semanticRevision = capturedInvalidations.semanticRevision + 1)
      } else {
        capturedInvalidations
      }
    val coldSweep = previous == null || previous.inputs.roots != inputs.roots || fingerprintChanged
    val prepared =
      snapshotBuilder.prepare(
        previous = previous,
        inputs = inputs,
        targets = targets,
        pending = candidateInvalidations.sourceChanges,
        coldSweep = coldSweep,
        progress = progress,
        generationToken = generationToken,
        checkCurrent = {
          if (resolutionCandidateIsSuperseded(buildSnapshot, manualRequest, inputs)) {
            throw ResolutionCandidateSupersededException()
          }
        },
      )
    return CollectedResolutionCandidate(prepared, candidateInvalidations)
  }

  /** Captures module visibility and source declaration identities inside the generation read. */
  @OptIn(KaPlatformInterface::class)
  private fun BindingIndexBuilder.captureResolutionInputs(
    declarationSignatureFiles: Set<VirtualFile>
  ) {
    val representatives = linkedMapOf<VirtualFile, PsiElement>()
    val pointerSourceIdentities =
      IdentityHashMap<SmartPsiElementPointer<*>, BindingIndex.SourcePointerIdentity>()
    val capturedDeclarationSignatures =
      linkedMapOf<BindingIndex.SourcePointerIdentity, DeclarationAnchorSignature>()
    val ambiguousDeclarationSignatures = mutableSetOf<BindingIndex.SourcePointerIdentity>()
    val capturedBindings = Collections.newSetFromMap(IdentityHashMap<KaBinding, Boolean>())
    var pointerCaptureWorkIndex = 0

    fun capture(
      pointer: SmartPsiElementPointer<*>,
      captureAnchorSignature: Boolean = false,
    ) {
      checkCanceledEvery(pointerCaptureWorkIndex++)
      val identity = sourcePointerIdentity(pointer)
      if (identity != null) pointerSourceIdentities[pointer] = identity
      val file = pointer.virtualFile ?: return
      val needsAnchorSignature = captureAnchorSignature && file in declarationSignatureFiles
      if (!needsAnchorSignature && file in representatives) return
      val element = pointer.element ?: return
      representatives.putIfAbsent(file, element)
      if (!needsAnchorSignature || identity == null) return
      val ktElement = element as? KtElement ?: return
      val currentIdentity =
        BindingIndex.SourcePointerIdentity(
          file,
          ktElement.textRange.startOffset,
          ktElement.textRange.endOffset,
        )
      if (currentIdentity != identity) return
      val signature = DeclarationAnchorSignature.capture(ktElement)
      val existing = capturedDeclarationSignatures.putIfAbsent(identity, signature)
      if (existing != null && existing != signature) {
        ambiguousDeclarationSignatures += identity
      }
    }

    fun captureBinding(binding: KaBinding) {
      capturedBindings += binding
      capture(binding.pointer, captureAnchorSignature = true)
    }

    for (binding in bindings) captureBinding(binding)
    for (consumer in consumers) {
      capture(consumer.pointer, captureAnchorSignature = true)
      consumer.injectedMemberPointer?.let { pointer -> capture(pointer) }
    }
    for (graph in graphs) {
      capture(graph.pointer, captureAnchorSignature = true)
      for (factory in graph.extensionFactories) capture(factory.pointer)
      for (implementation in graph.defaultImplementations) {
        capture(implementation.declaration.pointer)
        for (overridden in implementation.overriddenDeclarations) capture(overridden.pointer)
      }
      for (contribution in graph.contributedInterfaces) {
        capture(contribution.contribution.pointer)
        for (binding in contribution.bindings) captureBinding(binding)
        for (consumer in contribution.consumers) {
          capture(consumer.pointer, captureAnchorSignature = true)
        }
        for (factory in contribution.extensionFactories) capture(factory.pointer)
        for (implementation in contribution.defaultImplementations) {
          capture(implementation.declaration.pointer)
          for (overridden in implementation.overriddenDeclarations) capture(overridden.pointer)
        }
      }
    }
    for (contribution in contributions) capture(contribution.pointer)
    for (site in assistedSites) {
      capture(site.pointer, captureAnchorSignature = true)
    }
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
    if (declarationSignatureFiles.isNotEmpty()) {
      ambiguousDeclarationSignatures.forEach(capturedDeclarationSignatures::remove)
      declarationAnchorSignatures.compute(generationToken) { _, existing ->
        val merged = existing.orEmpty().toMutableMap()
        val conflicts = mutableSetOf<BindingIndex.SourcePointerIdentity>()
        for ((identity, signature) in capturedDeclarationSignatures) {
          val previous = merged.putIfAbsent(identity, signature)
          if (previous != null && previous != signature) conflicts += identity
        }
        conflicts.forEach(merged::remove)
        merged.toMap()
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
          daggerAnvilInteropEnabled =
            moduleElement.metroIdeState().options.enableDaggerAnvilInterop,
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

  /** Records shared declaration fingerprints during the shard's background read. */
  private fun seedSharedDeclarationFingerprints(file: KtFile, shard: FileShard) {
    seedSharedDeclarationFingerprint(file)
    val psiManager = PsiManager.getInstance(project)
    for (dependencyFile in shard.dependencyFiles + shard.sharedDeclarationFiles) {
      ProgressManager.checkCanceled()
      if (sharedDeclarationFingerprints.containsKey(dependencyFile)) continue
      val dependency = psiManager.findFile(dependencyFile) as? KtFile ?: continue
      seedSharedDeclarationFingerprint(dependency)
    }
  }

  private fun seedSharedDeclarationFingerprint(file: KtFile) {
    ProgressManager.checkCanceled()
    val virtualFile = file.virtualFile ?: return
    if (sharedDeclarationFingerprints.containsKey(virtualFile)) return
    if (!fileHasSharedDeclarationsCached(file)) return
    sharedDeclarationFingerprints.putIfAbsent(virtualFile, sharedDeclarationFingerprint(file))
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
    val publication = publishedResolution.value
    if (
      virtualFile in publication.trackedSourceFiles ||
        virtualFile in publication.knownIrrelevantFiles
    ) {
      return
    }
    enqueuePsiChange(
      PendingPsiChanges(files = mapOf(virtualFile to PendingFileChange(requestedByQuery = true)))
    )
  }

  private fun isRelevantFileCached(file: KtFile): Boolean {
    return CachedValuesManager.getCachedValue(file) {
      // The cached provider can run again after compiler settings change. Read the coordinator's
      // accepted snapshot each time so it never retains annotation names from an older generation.
      val shortNames =
        sourceSnapshot?.shortNames
          ?: snapshotBuilder.projectSweepShortNames(file.metroIdeState().options)
      CachedValueProvider.Result.create(
        snapshotBuilder.containsRelevantAnnotation(file, shortNames),
        file,
        KotlinCompilerSettingsTracker.getInstance(file.project),
      )
    }
  }

  private fun psiChanged(
    event: PsiTreeChangeEvent,
    structuralChange: Boolean = false,
    oldTreeMayDisappear: Boolean = false,
  ) {
    val virtualFile = changedVirtualFile(event)
    if (virtualFile == null) {
      val directory = changedDirectoryVirtualFile(event)
      if (directory != null && structuralChange) {
        enqueuePsiChange(PendingPsiChanges(directories = setOf(directory)))
      }
      return
    }

    val sharedChange = sharedDeclarationChange(event, oldTreeMayDisappear)
    val mayHaveRemovedSharedDeclaration =
      mayHaveRemovedSharedDeclaration(event, oldTreeMayDisappear)

    enqueuePsiChange(
      PendingPsiChanges(
        files =
          mapOf(
            virtualFile to
              PendingFileChange(
                structuralChange = structuralChange,
                sharedDeclarationChanges =
                  if (sharedChange == SharedDeclarationChange.NONE) emptySet()
                  else setOf(sharedChange),
                removedTrackedSharedDeclaration = mayHaveRemovedSharedDeclaration,
              )
          )
      )
    )
  }

  /**
   * Aliases and constants can change binding keys in other files. Their dependencies are only
   * partially tracked, so edits to these declarations rebuild all source shards. Tracking every
   * referenced declaration during type-key capture would allow narrower invalidation.
   */
  private fun fileHasSharedDeclarationsCached(file: KtFile): Boolean {
    return CachedValuesManager.getCachedValue(file) {
      CachedValueProvider.Result.create(hasSharedSemanticDeclarations(file), file)
    }
  }

  /** Names and declaration text catch value, alias, containing-object, and import changes. */
  private fun sharedDeclarationFingerprint(file: KtFile): String {
    return buildString {
      append(file.packageFqName.asString())
      append('\n')
      append(file.importList?.text.orEmpty())

      fun appendDeclarations(declarations: List<KtDeclaration>, owner: String) {
        for (declaration in declarations) {
          ProgressManager.checkCanceled()
          when (declaration) {
            is KtTypeAlias -> {
              append('\n')
              append(owner)
              append(declaration.text)
            }

            is KtProperty if declaration.hasModifier(KtTokens.CONST_KEYWORD) -> {
              append('\n')
              append(owner)
              append(declaration.text)
            }

            is KtClassOrObject -> {
              appendDeclarations(declaration.declarations, "$owner${declaration.name}.")
            }
          }
        }
      }

      appendDeclarations(file.declarations, owner = "")
    }
  }

  /** Records the change type before removed PSI becomes unavailable. */
  private fun sharedDeclarationChange(
    event: PsiTreeChangeEvent,
    oldTreeMayDisappear: Boolean,
  ): SharedDeclarationChange {
    val candidate =
      event.child ?: event.element ?: event.parent ?: return SharedDeclarationChange.NONE
    if (candidate is KtFile) {
      // Bulk reparses may omit individual removal events, so record a possible file-wide change.
      return if (oldTreeMayDisappear) SharedDeclarationChange.FILE_CONTENTS
      else SharedDeclarationChange.NONE
    }
    if (candidate is PsiDirectory) return SharedDeclarationChange.NONE
    if (candidate is KtClassOrObject) return SharedDeclarationChange.DECLARATION_CONTAINER

    var current: PsiElement? = candidate
    while (current != null && current !is KtFile) {
      if (current is KtTypeAlias) return SharedDeclarationChange.DECLARATION
      if (current is KtProperty && current.hasModifier(KtTokens.CONST_KEYWORD)) {
        return SharedDeclarationChange.DECLARATION
      }
      if (current is KtImportDirective || current is KtPackageDirective) {
        return SharedDeclarationChange.FILE_METADATA
      }
      current = current.parent
    }
    return SharedDeclarationChange.NONE
  }

  /** Detects removed type aliases and const properties before their PSI is discarded. */
  private fun mayHaveRemovedSharedDeclaration(
    event: PsiTreeChangeEvent,
    oldTreeMayDisappear: Boolean,
  ): Boolean {
    return oldTreeMayDisappear &&
      when (val removed = event.oldChild ?: event.child) {
        // The background classifier checks files and declaration containers.
        is KtFile,
        is KtClassOrObject -> false

        is KtTypeAlias -> true
        is KtProperty -> removed.hasModifier(KtTokens.CONST_KEYWORD)
        else -> false
      }
  }

  private fun hasSharedSemanticDeclarations(file: KtFile): Boolean {
    for (declaration in file.declarations) {
      ProgressManager.checkCanceled()
      if (hasSharedSemanticDeclarations(declaration)) return true
    }
    return false
  }

  private fun hasSharedSemanticDeclarations(declaration: KtDeclaration): Boolean {
    // Consts commonly live inside objects and companion objects, so recurse through all nesting.
    return when (declaration) {
      is KtTypeAlias -> true
      is KtProperty if declaration.hasModifier(KtTokens.CONST_KEYWORD) -> true
      is KtClassOrObject -> {
        for (nested in declaration.declarations) {
          ProgressManager.checkCanceled()
          if (hasSharedSemanticDeclarations(nested)) return true
        }
        false
      }

      else -> false
    }
  }

  private fun changedVirtualFile(event: PsiTreeChangeEvent): VirtualFile? {
    event.file?.virtualFile?.let {
      return it
    }
    (event.element as? KtFile)?.virtualFile?.let {
      return it
    }
    return (event.child as? KtFile)?.virtualFile
  }

  private fun changedDirectoryVirtualFile(event: PsiTreeChangeEvent): VirtualFile? {
    val directory =
      event.child as? PsiDirectory
        ?: event.element as? PsiDirectory
        ?: event.parent as? PsiDirectory
    return directory?.virtualFile
  }

  private fun enqueuePsiChange(change: PendingPsiChanges) {
    if (change.isEmpty || isDisposed) return
    ingress.submit(semanticChange = true) { ResolutionCoordinatorEvent.Psi(change) }
  }

  /** Collects Kotlin files from failed classification requests without reading PSI. */
  private suspend fun failedClassificationRequests(batch: PendingPsiChanges): Set<VirtualFile> {
    val requested = linkedSetOf<VirtualFile>()
    requested += batch.files.keys
    val remaining = ArrayDeque(batch.directories)
    var visited = 0
    while (remaining.isNotEmpty()) {
      if (visited++ % 64 == 0) yield()
      checkPsiClassificationActive()
      val file = remaining.removeFirst()
      if (!file.isValid) continue
      if (file.isDirectory) {
        remaining += file.children
      } else if (file.extension == "kt" || file.extension == "kts") {
        requested += file
      }
    }
    return requested
  }

  /** Keeps captured project unavailability retryable and stops VFS access during shutdown. */
  internal fun checkPsiClassificationActive(projectDisposed: Boolean = project.isDisposed) {
    val applicationStopping =
      ShutDownTracker.isShutdownStarted() || ApplicationManager.getApplication().isDisposed
    if (isDisposed || applicationStopping) {
      throw CancellationException("Metro PSI classification stopped during disposal")
    }
    // Light projects can temporarily close while their service scope stays alive. Preserve the
    // batch until another event wakes the coordinator after the project becomes available again.
    if (projectDisposed) throw ProcessCanceledException()
  }

  /** Lets lifecycle tests interrupt classification after its read and before applying changes. */
  @TestOnly
  internal fun setPsiClassificationObserver(observer: (() -> Unit)?) {
    psiClassificationObserver = observer
  }

  /** Called by the coordinator inside a smart read action. */
  private fun classifyPsiChanges(batch: PendingPsiChanges): ClassifiedPsiChanges {
    val result = ClassifiedPsiChanges.Builder()
    val state = sourceSnapshot
    for (directory in batch.directories) {
      ProgressManager.checkCanceled()
      classifyDirectoryChange(directory, state, result)
    }
    for ((virtualFile, change) in batch.files) {
      ProgressManager.checkCanceled()
      classifyFileChange(virtualFile, change, state, result)
    }
    return result.build()
  }

  /** Directory moves can replace several Kotlin files without reporting individual PSI children. */
  private fun classifyDirectoryChange(
    virtualFile: VirtualFile,
    state: SourceSnapshot?,
    result: ClassifiedPsiChanges.Builder,
  ) {
    checkPsiClassificationActive()
    if (!virtualFile.isValid) {
      // The removed directory cannot be traversed. Rebuild all published shards.
      if (state != null) {
        result.forceAll = true
        result.forceRebuildFiles += state.shardOrder
      }
      return
    }
    val directory = PsiManager.getInstance(project).findDirectory(virtualFile) ?: return
    val remaining = ArrayDeque<PsiDirectory>()
    remaining += directory
    while (remaining.isNotEmpty()) {
      ProgressManager.checkCanceled()
      checkPsiClassificationActive()
      val current = remaining.removeFirst()
      if (!current.isValid || !current.virtualFile.isValid) continue
      for (file in current.files.filterIsInstance<KtFile>()) {
        ProgressManager.checkCanceled()
        checkPsiClassificationActive()
        val fileVirtualFile = file.virtualFile ?: continue
        classifyFileChange(
          fileVirtualFile,
          PendingFileChange(structuralChange = true),
          state,
          result,
          file,
        )
      }
      remaining += current.subdirectories
    }
  }

  /** Reads PSI and fingerprints shared declarations inside the coordinator's smart read. */
  private fun classifyFileChange(
    virtualFile: VirtualFile,
    change: PendingFileChange,
    state: SourceSnapshot?,
    result: ClassifiedPsiChanges.Builder,
    knownFile: KtFile? = null,
  ) {
    checkPsiClassificationActive()
    val ownerFiles = state?.dependencyOwners?.get(virtualFile)
    val alreadyIndexed = state != null && virtualFile in state.shards
    val file =
      knownFile
        ?: virtualFile
          .takeIf { it.isValid }
          ?.let {
            PsiManager.getInstance(project).findFile(it) as? KtFile
          }
    if (file == null || !file.isValid) {
      result.fingerprints[virtualFile] = null
      result.irrelevantFilesToRemove += virtualFile
      if (change.structuralChange) result.requestedToRemove += virtualFile
      result.dirty += ownerFiles.orEmpty()
      if (alreadyIndexed || !ownerFiles.isNullOrEmpty()) result.dirty += virtualFile
      if (change.structuralChange) {
        result.forceRebuildFiles += ownerFiles.orEmpty()
        if (alreadyIndexed) result.forceRebuildFiles += virtualFile
      }
      val lostFingerprint = sharedDeclarationFingerprints.containsKey(virtualFile)
      val directlyChangesSharedDeclaration =
        change.sharedDeclarationChanges.any { it.forcesGlobalInvalidation }
      val lostSharedDeclaration =
        lostFingerprint ||
          change.removedTrackedSharedDeclaration ||
          directlyChangesSharedDeclaration
      if (lostSharedDeclaration) {
        result.forceAll = true
      }
      return
    }

    val hasSharedDeclarations = fileHasSharedDeclarationsCached(file)
    val previousFingerprint = sharedDeclarationFingerprints[virtualFile]
    val currentFingerprint = if (hasSharedDeclarations) sharedDeclarationFingerprint(file) else null
    result.fingerprints[virtualFile] = currentFingerprint
    val fingerprintChanged =
      previousFingerprint != null && previousFingerprint != currentFingerprint
    val metadataAffectsSharedDeclarations =
      SharedDeclarationChange.FILE_METADATA in change.sharedDeclarationChanges &&
        (hasSharedDeclarations || previousFingerprint != null)
    // Moving aliases or constants changes which modules can resolve them with identical text.
    val movedSharedDeclarations =
      change.structuralChange && (hasSharedDeclarations || previousFingerprint != null)
    val removedSharedDeclarationWithoutFingerprint =
      change.removedTrackedSharedDeclaration &&
        previousFingerprint == null &&
        !hasSharedDeclarations
    val directlyChangesSharedDeclaration =
      change.sharedDeclarationChanges.any { it.forcesGlobalInvalidation }
    val asynchronouslyDiscoveredGlobalChange =
      metadataAffectsSharedDeclarations ||
        movedSharedDeclarations ||
        fingerprintChanged ||
        removedSharedDeclarationWithoutFingerprint
    val globalSemanticChange =
      directlyChangesSharedDeclaration || asynchronouslyDiscoveredGlobalChange

    val relevant =
      if (state == null) {
        isRelevantFileCached(file)
      } else {
        snapshotBuilder.containsRelevantAnnotation(file, state.shortNames)
      }
    if (relevant) {
      result.irrelevantFilesToRemove += virtualFile
    } else {
      result.irrelevantFiles += virtualFile
    }

    if (state == null) {
      if (change.structuralChange || change.requestedByQuery) {
        if (relevant) {
          result.requested += virtualFile
          if (change.structuralChange) result.forceRebuildFiles += virtualFile
        } else {
          result.requestedToRemove += virtualFile
        }
      }
      if (globalSemanticChange) result.forceAll = true
      return
    }

    val newlyRelevant = !alreadyIndexed && ownerFiles.isNullOrEmpty() && relevant
    val needsRebuild =
      alreadyIndexed || !ownerFiles.isNullOrEmpty() || relevant || globalSemanticChange
    if (needsRebuild) {
      result.dirty += virtualFile
      result.dirty += ownerFiles.orEmpty()
      if (change.structuralChange) {
        result.forceRebuildFiles += virtualFile
        result.forceRebuildFiles += ownerFiles.orEmpty()
      }
    }
    if (change.structuralChange && !alreadyIndexed) {
      if (relevant) {
        result.requested += virtualFile
      } else {
        result.requestedToRemove += virtualFile
      }
    }
    if (globalSemanticChange) result.forceAll = true
    if (newlyRelevant) result.restartDaemon = true
  }

  private fun applyClassifiedPsiChanges(classified: ClassifiedPsiChanges) {
    for ((file, fingerprint) in classified.fingerprints) {
      if (fingerprint == null) {
        sharedDeclarationFingerprints.remove(file)
      } else {
        sharedDeclarationFingerprints[file] = fingerprint
      }
    }
    knownIrrelevantFiles.removeAll(classified.irrelevantFilesToRemove)
    knownIrrelevantFiles.addAll(classified.irrelevantFiles)

    val dirtyInvalidationAdded = recordDirtyInvalidations(classified.dirty)
    val forceAllInvalidationAdded = classified.forceAll && recordForceAllInvalidation()
    pendingRequestedFiles += classified.requested
    pendingRequestedFiles.removeAll(classified.requestedToRemove)
    pendingForceRebuildFiles += classified.forceRebuildFiles
    val semanticInvalidationAdded = dirtyInvalidationAdded || forceAllInvalidationAdded
    if (classified.restartDaemon || semanticInvalidationAdded) {
      // The edit-triggered daemon pass may have finished before background classification landed.
      notifyListeners(restartDaemon = true)
    } else if (classified.dirty.isNotEmpty() || classified.forceAll) {
      scheduleInvalidationNotification()
    }
  }

  private fun recordDirtyInvalidations(files: Set<VirtualFile>): Boolean {
    if (files.isEmpty()) return false
    pendingDirtyFiles += files
    semanticRevision++
    return true
  }

  private fun recordForceAllInvalidation(): Boolean {
    if (forceAllFiles) return false
    forceAllFiles = true
    semanticRevision++
    return true
  }

  /** Copies the pending source changes for one build attempt. */
  private fun capturePendingInvalidations(): CapturedInvalidations {
    return CapturedInvalidations(
      sourceChanges =
        SourceSnapshotChanges(
          dirty = pendingDirtyFiles.toSet(),
          requested = pendingRequestedFiles.toSet(),
          forceRebuildFiles = pendingForceRebuildFiles.toSet(),
          forceAll = forceAllFiles,
        ),
      semanticRevision = semanticRevision,
    )
  }

  /** Removes captured work only after its complete generation has published. */
  private fun consumeCapturedInvalidations(captured: CapturedInvalidations) {
    if (semanticRevision > captured.semanticRevision) return
    pendingDirtyFiles.removeAll(captured.sourceChanges.dirty)
    pendingRequestedFiles.removeAll(captured.sourceChanges.requested)
    pendingForceRebuildFiles.removeAll(captured.sourceChanges.forceRebuildFiles)
    if (captured.sourceChanges.forceAll) forceAllFiles = false
    semanticRevision = captured.semanticRevision
  }

  /** Daemon restart intent is recorded before conflating UI refresh requests. */
  private fun notifyListeners(
    restartDaemon: Boolean,
    forceDaemonRestart: Boolean = false,
  ) {
    if (isDisposed || project.isDisposed) return
    if (restartDaemon && (forceDaemonRestart || automaticallyRefreshGraphData)) {
      project.service<MetroDaemonRestartService>().requestRestart()
    }
    scheduleInvalidationNotification()
  }

  /** Serial EDT delivery preserves the manual browser's single stale notification per refresh. */
  private suspend fun deliverIndexChanges() {
    notificationRequests.consumeEach {
      if (isDisposed) return
      // Service disposal and scope cancellation own the consumer's lifetime.
      if (project.isDisposed) return@consumeEach
      if (indexChanges.subscriptionCount.value == 0) return@consumeEach
      if (isManualGraphDataRefreshRequired) {
        val previous = publishedResolution.getAndUpdate { publication ->
          if (publication.isDisposed) publication
          else publication.copy(manualStaleNotificationSent = true)
        }
        if (previous.isDisposed || previous.manualStaleNotificationSent) return@consumeEach
      }
      indexChanges.emit(Unit)
    }
  }

  /** Publishes the current build progress for UI collectors. */
  private fun publishIndexBuildProgress(progress: IndexBuildProgress?) {
    if (isDisposed || project.isDisposed) return
    indexBuildProgress.value = progress
  }

  /** Coalesces write-action events so an open graph window can refresh or show stale status. */
  private fun scheduleInvalidationNotification() {
    if (!isDisposed && !project.isDisposed) notificationRequests.trySend(Unit)
  }

  override fun dispose() {
    publishedResolution.value = PublishedResolution.DISPOSED
    psiClassificationObserver = null
    val abandonedEvents = ingress.close()
    for (event in abandonedEvents) {
      when (event) {
        is ResolutionCoordinatorEvent.Build -> {
          event.completions.forEach { it.complete(IndexBuildOutcome.CANCELED) }
        }
        is ResolutionCoordinatorEvent.TestBarrier -> event.completion.complete(Unit)
        else -> Unit
      }
    }
    coordinatorJob.cancel()
    notificationRequests.close()
    notificationScope.cancel()
    declarationAnchorSignatures.clear()
    indexBuildProgress.value = null
  }

  companion object {
    private const val MAX_CONCURRENT_FILE_PRESENTATION_BUILDS = 2
    private const val MAX_FILE_PRESENTATION_BUNDLES = 64

    /**
     * Gives isolated tests an explicit policy without changing the project's production service.
     */
    @TestOnly
    internal fun createForTest(
      project: Project,
      scope: CoroutineScope,
      requestPolicy: IndexRequestPolicy,
    ): MetroResolutionService = MetroResolutionService(project, scope, requestPolicy)
  }
}

private enum class IndexBuildIntent {
  AUTOMATIC,
  EXPLICIT,
  MANUAL_REFRESH,
}

private enum class IndexBuildOutcome {
  PUBLISHED,
  SKIPPED,
  FAILED,
  CANCELED,
}

private class PendingIndexBuild(
  val module: Module,
  var intent: IndexBuildIntent,
  val waiters: MutableList<CompletableDeferred<IndexBuildOutcome>>,
  /** Retains a presentation reader's cache miss when an explicit query joins the request. */
  var requiresPresentationRefresh: Boolean = false,
) {
  /** Merges execution intent while preserving refresh demand from either request. */
  fun upgrade(addedIntent: IndexBuildIntent, requestRefresh: Boolean) {
    requiresPresentationRefresh = requiresPresentationRefresh || requestRefresh
    if (intent == IndexBuildIntent.AUTOMATIC && addedIntent == IndexBuildIntent.EXPLICIT) {
      intent = IndexBuildIntent.EXPLICIT
    }
  }
}

private data class ManualRefreshRequest(val id: Long)

private data class CollectedResolutionCandidate(
  val prepared: PreparedResolutionSnapshot,
  val consumedInvalidations: CapturedInvalidations,
) {
  val source: SourceSnapshot? = prepared.source
  val inputs: IndexInputs = prepared.inputs
  val semanticRevision: Long = consumedInvalidations.semanticRevision
  val keysByModule: Map<Module, SnapshotKey> = prepared.keysByModule
}

private class ResolutionCandidateSupersededException : RuntimeException() {
  override fun fillInStackTrace(): Throwable = this
}

private data class CompletedFilePresentationBundle(
  val index: BindingIndex,
  val key: FilePresentationKey,
  val bundle: FilePresentationBundle,
)

/** Requests updated declaration locations for one published bundle and file version. */
private data class PendingFilePresentationAnchorBuild(
  val index: BindingIndex,
  val key: FilePresentationKey,
  val baseBundle: FilePresentationBundle,
  val modificationStamp: Long,
)

/** Updated declaration locations waiting for the coordinator to check and publish them. */
private data class CompletedFilePresentationAnchorBundle(
  val index: BindingIndex,
  val key: FilePresentationKey,
  val baseBundle: FilePresentationBundle,
  val modificationStamp: Long,
  val bundle: FilePresentationBundle,
)

/** Tracks one worker so only its matching terminal event can release the slot. */
private data class FilePresentationBuildAttempt(
  val id: Long,
  val index: BindingIndex,
  val job: Job,
)

/** Tracks the original bundle and file version used by one declaration-location worker. */
private data class FilePresentationAnchorBuildAttempt(
  val id: Long,
  val index: BindingIndex,
  val baseBundle: FilePresentationBundle,
  val modificationStamp: Long,
  val job: Job,
)

/** Terminal state for a presentation worker attempt. */
private sealed interface FilePresentationBuildOutcome {
  data class Succeeded(val bundle: FilePresentationBundle) : FilePresentationBuildOutcome

  data object Failed : FilePresentationBuildOutcome

  data object Canceled : FilePresentationBuildOutcome
}

private sealed interface ResolutionCoordinatorEvent {
  val coalescingKey: Any

  data class Psi(val changes: PendingPsiChanges) : ResolutionCoordinatorEvent {
    override val coalescingKey: Any = ResolutionIngressEventKey.Psi
  }

  data object ProjectInputs : ResolutionCoordinatorEvent {
    override val coalescingKey: Any = ResolutionIngressEventKey.ProjectInputs
  }

  data object Settings : ResolutionCoordinatorEvent {
    override val coalescingKey: Any = ResolutionIngressEventKey.Settings
  }

  data class PresentationDemand(val module: Module) : ResolutionCoordinatorEvent {
    override val coalescingKey: Any = ResolutionIngressEventKey.PresentationDemand(module)
  }

  class Build(
    val module: Module,
    var intent: IndexBuildIntent,
    val completions: MutableList<CompletableDeferred<IndexBuildOutcome>>,
    var requiresPresentationRefresh: Boolean,
  ) : ResolutionCoordinatorEvent {
    override val coalescingKey: Any = ResolutionIngressEventKey.Build(module)
  }

  data class ManualRefresh(val requestId: Long) : ResolutionCoordinatorEvent {
    override val coalescingKey: Any = ResolutionIngressEventKey.ManualRefresh
  }

  data class FilePresentationRequest(
    val index: BindingIndex,
    val key: FilePresentationKey,
  ) : ResolutionCoordinatorEvent {
    override val coalescingKey: Any = ResolutionIngressEventKey.FilePresentationRequest(key)
  }

  data class FilePresentationComplete(
    val index: BindingIndex,
    val key: FilePresentationKey,
    val attemptId: Long,
    val outcome: FilePresentationBuildOutcome,
  ) : ResolutionCoordinatorEvent {
    override val coalescingKey: Any =
      ResolutionIngressEventKey.FilePresentationComplete(key, attemptId)
  }

  data class FilePresentationAnchorRequest(
    val index: BindingIndex,
    val key: FilePresentationKey,
    val baseBundle: FilePresentationBundle,
    val modificationStamp: Long,
  ) : ResolutionCoordinatorEvent {
    override val coalescingKey: Any = ResolutionIngressEventKey.FilePresentationAnchorRequest(key)
  }

  data class FilePresentationAnchorComplete(
    val index: BindingIndex,
    val key: FilePresentationKey,
    val attemptId: Long,
    val baseBundle: FilePresentationBundle,
    val modificationStamp: Long,
    val outcome: FilePresentationBuildOutcome,
  ) : ResolutionCoordinatorEvent {
    override val coalescingKey: Any =
      ResolutionIngressEventKey.FilePresentationAnchorComplete(key, attemptId)
  }

  class TestBarrier(val completion: CompletableDeferred<Unit>) : ResolutionCoordinatorEvent {
    override val coalescingKey: Any = this
  }
}

private sealed interface ResolutionIngressEventKey {
  data object Psi : ResolutionIngressEventKey

  data object ProjectInputs : ResolutionIngressEventKey

  data object Settings : ResolutionIngressEventKey

  data object ManualRefresh : ResolutionIngressEventKey

  data class PresentationDemand(val module: Module) : ResolutionIngressEventKey

  data class Build(val module: Module) : ResolutionIngressEventKey

  data class FilePresentationRequest(val key: FilePresentationKey) : ResolutionIngressEventKey

  data class FilePresentationComplete(val key: FilePresentationKey, val attemptId: Long) :
    ResolutionIngressEventKey

  data class FilePresentationAnchorRequest(val key: FilePresentationKey) : ResolutionIngressEventKey

  data class FilePresentationAnchorComplete(
    val key: FilePresentationKey,
    val attemptId: Long,
  ) : ResolutionIngressEventKey
}

private fun mergeResolutionCoordinatorEvents(
  existing: ResolutionCoordinatorEvent,
  added: ResolutionCoordinatorEvent,
): ResolutionCoordinatorEvent {
  check(existing.coalescingKey == added.coalescingKey)
  return when (added) {
    is ResolutionCoordinatorEvent.Psi -> {
      val previous = existing as ResolutionCoordinatorEvent.Psi
      ResolutionCoordinatorEvent.Psi(previous.changes.mergeInPlace(added.changes))
    }
    is ResolutionCoordinatorEvent.Build -> {
      val previous = existing as ResolutionCoordinatorEvent.Build
      if (added.intent == IndexBuildIntent.EXPLICIT) previous.intent = IndexBuildIntent.EXPLICIT
      previous.requiresPresentationRefresh =
        previous.requiresPresentationRefresh || added.requiresPresentationRefresh
      previous.completions += added.completions
      previous
    }
    else -> added
  }
}

/** Retries platform read-action cancellations while the calling operation remains active. */
internal suspend fun <T> retryCancelledIndexBuild(build: suspend () -> T): T {
  while (true) {
    try {
      return build()
    } catch (exception: ProcessCanceledException) {
      if (exception is ResolutionBuildPendingException) {
        when (exception.completion.await()) {
          IndexBuildOutcome.PUBLISHED -> Unit
          IndexBuildOutcome.CANCELED ->
            throw CancellationException("Metro resolution service was disposed")
          IndexBuildOutcome.SKIPPED,
          IndexBuildOutcome.FAILED -> throw exception
        }
      }
      // Yield before retrying so cancellation of the calling coroutine stops promptly.
      yield()
    }
  }
}

private class ResolutionBuildPendingException(
  val completion: CompletableDeferred<IndexBuildOutcome>
) : ProcessCanceledException() {
  override fun fillInStackTrace(): Throwable = this
}

/** Immutable indexes and the index selected for each module in one generation. */
private class ResolutionGeneration(
  val token: IndexGenerationToken,
  val inputs: IndexInputs,
  val semanticRevision: Long,
  val source: SourceSnapshot?,
  indexesByKey: Map<SnapshotKey, BindingIndex>,
  keysByModule: Map<Module, SnapshotKey>,
) {
  val indexesByKey: Map<SnapshotKey, BindingIndex> = indexesByKey.toMap()
  val keysByModule: Map<Module, SnapshotKey> = keysByModule.toMap()
  val indexGenerationTokens: Set<IndexGenerationToken> =
    this.indexesByKey.values.mapTo(linkedSetOf()) { it.generationToken }

  fun index(module: Module): BindingIndex {
    val key = keysByModule[module] ?: return BindingIndex.EMPTY
    return indexesByKey[key] ?: BindingIndex.EMPTY
  }

  fun contains(index: BindingIndex): Boolean = indexesByKey.values.any { it === index }

  /** Updates project-input versions when the binding data is unchanged. */
  fun withUpdatedInputs(
    previousSource: SourceSnapshot,
    updatedSource: SourceSnapshot,
    updatedInputs: IndexInputs,
  ): ResolutionGeneration {
    if (source !== previousSource) return this
    return ResolutionGeneration(
      token = token,
      inputs = updatedInputs,
      semanticRevision = semanticRevision,
      source = updatedSource,
      indexesByKey = indexesByKey,
      keysByModule = keysByModule,
    )
  }

  companion object {
    const val EMPTY_REVISION = -1L
    val EMPTY =
      ResolutionGeneration(
        token = IndexGenerationToken.EMPTY,
        inputs = IndexInputs(roots = -1L, compilerSettings = -1L),
        semanticRevision = EMPTY_REVISION,
        source = null,
        indexesByKey = emptyMap(),
        keysByModule = emptyMap(),
      )
  }
}

/**
 * Complete reader state. Disposal is terminal and browser revisions track the frozen presentation.
 */
private data class PublishedResolution(
  val current: ResolutionGeneration,
  val presentation: ResolutionGeneration,
  val filePresentationBundles: Map<FilePresentationKey, FilePresentationBundle> = emptyMap(),
  val classifiedSemanticClock: Long = 0L,
  val latestSemanticRevision: Long = 0L,
  val trackedSourceFiles: Set<VirtualFile> = emptySet(),
  val knownIrrelevantFiles: Set<VirtualFile> = emptySet(),
  val graphBrowserActivated: Boolean = false,
  val graphBrowserRefreshRevision: Long = 0L,
  val manualStaleNotificationSent: Boolean = false,
  val isDisposed: Boolean = false,
) {
  companion object {
    val EMPTY = PublishedResolution(ResolutionGeneration.EMPTY, ResolutionGeneration.EMPTY)
    val DISPOSED = EMPTY.copy(isDisposed = true)
  }
}

private enum class SharedDeclarationChange(val forcesGlobalInvalidation: Boolean) {
  NONE(false),
  FILE_METADATA(false),
  DECLARATION_CONTAINER(false),
  FILE_CONTENTS(false),
  DECLARATION(true),
}

private data class PendingFileChange(
  val structuralChange: Boolean = false,
  val sharedDeclarationChanges: Set<SharedDeclarationChange> = emptySet(),
  val removedTrackedSharedDeclaration: Boolean = false,
  val requestedByQuery: Boolean = false,
) {
  fun merge(other: PendingFileChange): PendingFileChange {
    return PendingFileChange(
      structuralChange = structuralChange || other.structuralChange,
      sharedDeclarationChanges = sharedDeclarationChanges + other.sharedDeclarationChanges,
      removedTrackedSharedDeclaration =
        removedTrackedSharedDeclaration || other.removedTrackedSharedDeclaration,
      requestedByQuery = requestedByQuery || other.requestedByQuery,
    )
  }
}

private class PendingPsiChanges(
  files: Map<VirtualFile, PendingFileChange> = emptyMap(),
  directories: Set<VirtualFile> = emptySet(),
) {
  val files = LinkedHashMap(files)
  val directories = LinkedHashSet(directories)

  val isEmpty: Boolean
    get() = files.isEmpty() && directories.isEmpty()

  fun mergeInPlace(added: PendingPsiChanges): PendingPsiChanges {
    for ((file, change) in added.files) {
      files[file] = files[file]?.merge(change) ?: change
    }
    directories += added.directories
    return this
  }
}

private data class ClassifiedPsiChanges(
  val dirty: Set<VirtualFile>,
  val requested: Set<VirtualFile>,
  val requestedToRemove: Set<VirtualFile>,
  val forceRebuildFiles: Set<VirtualFile>,
  val forceAll: Boolean,
  val restartDaemon: Boolean,
  val fingerprints: Map<VirtualFile, String?>,
  val irrelevantFiles: Set<VirtualFile>,
  val irrelevantFilesToRemove: Set<VirtualFile>,
) {
  class Builder {
    val dirty = linkedSetOf<VirtualFile>()
    val requested = linkedSetOf<VirtualFile>()
    val requestedToRemove = linkedSetOf<VirtualFile>()
    val forceRebuildFiles = linkedSetOf<VirtualFile>()
    var forceAll = false
    var restartDaemon = false
    val fingerprints = linkedMapOf<VirtualFile, String?>()
    val irrelevantFiles = linkedSetOf<VirtualFile>()
    val irrelevantFilesToRemove = linkedSetOf<VirtualFile>()

    fun build(): ClassifiedPsiChanges =
      ClassifiedPsiChanges(
        dirty = dirty.toSet(),
        requested = requested.toSet(),
        requestedToRemove = requestedToRemove.toSet(),
        forceRebuildFiles = forceRebuildFiles.toSet(),
        forceAll = forceAll,
        restartDaemon = restartDaemon,
        fingerprints = fingerprints.toMap(),
        irrelevantFiles = irrelevantFiles.toSet(),
        irrelevantFilesToRemove = irrelevantFilesToRemove.toSet(),
      )
  }
}

/** Immutable source work captured for one generation attempt. */
private data class CapturedInvalidations(
  val sourceChanges: SourceSnapshotChanges,
  val semanticRevision: Long,
)
