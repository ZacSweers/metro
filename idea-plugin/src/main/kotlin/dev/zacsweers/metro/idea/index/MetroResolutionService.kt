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
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ShutDownTracker
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
import dev.zacsweers.metro.idea.MetroDaemonRestartService
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
import dev.zacsweers.metro.idea.model.IndexGenerationToken
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class MetroResolutionService(
  private val project: Project,
  private val scope: CoroutineScope,
) : Disposable {
  private val libraryShards =
    object : LinkedHashMap<LibraryCacheKey, LibraryShard>(8, 0.75f, true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<LibraryCacheKey, LibraryShard>
      ): Boolean = size > MAX_CACHED_INDEXES
    }

  private val mutableIndexBuildProgress = MutableStateFlow<IndexBuildProgress?>(null)
  /** Latest progress of the coordinator's current index build. */
  internal val indexBuildProgress: StateFlow<IndexBuildProgress?> =
    mutableIndexBuildProgress.asStateFlow()
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
      index(module, IndexRequestMode.AUTOMATIC_BACKGROUND)
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
      for (unused in ingress.wakeups) {
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
            if (
              pending != null &&
                pending.baseBundle === event.baseBundle &&
                pending.modificationStamp == event.modificationStamp
            ) {
              pendingFilePresentationAnchorRequests.remove(event.key)
            }
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
        )
    } else {
      existing.upgrade(event.intent)
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
      evictStaleCaches(ProjectRootModificationTracker.getInstance(project).modificationCount)
      notifyListeners(restartDaemon = true)
    }
  }

  private suspend fun processPendingProjectInputs() {
    projectInputsPending = false
    try {
      readAction {
        reconcileProjectInputs()
      }
    } catch (exception: ProcessCanceledException) {
      projectInputsPending = true
      yield()
    } catch (exception: CancellationException) {
      projectInputsPending = true
      throw exception
    } catch (failure: Throwable) {
      logger<MetroResolutionService>().warn("Metro project input reconciliation failed", failure)
      semanticRevision++
      evictStaleCaches(ProjectRootModificationTracker.getInstance(project).modificationCount)
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
      if (!resolveFromLibraries) libraryShards.clear()
      evictStaleCaches(ProjectRootModificationTracker.getInstance(project).modificationCount)
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
    if (!isPresentationIndexPublished(index)) return false
    return publishedResolution.value.filePresentationBundles[key] === bundle
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
          val targets = resolutionTargets(if (manualRequest != null) null else demandedModules)
          if (manualRequest != null) {
            for (target in targets) demandedModules += target.modules
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
      } catch (exception: ProcessCanceledException) {
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
          buildMap<SnapshotKey, BindingIndex>(candidate.buildersByKey.size) {
            for ((key, builder) in candidate.buildersByKey) {
              if (
                resolutionCandidateIsSuperseded(
                  buildSnapshot,
                  manualRequest,
                  candidate.inputs,
                )
              ) {
                throw ResolutionCandidateSupersededException()
              }
              put(key, builder.build())
            }
          }
        // Cancellation of the service scope must stop this candidate before publication.
        currentCoroutineContext().ensureActive()
        builtIndexes
      } catch (_: ResolutionCandidateSupersededException) {
        requeueResolutionCandidate(requests, manualRequest)
        yield()
        return
      } catch (exception: ProcessCanceledException) {
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
    val completeTargetSet = indexesByKey.keys == candidate.buildersByKey.keys
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
        manualStaleNotificationSent =
          if (manualRequest != null) false else previous.manualStaleNotificationSent,
      )
    }
    if (published == null) {
      completeBuildRequests(requests.values, IndexBuildOutcome.CANCELED)
      return
    }
    sourceSnapshot = candidate.source
    consumeCapturedInvalidations(candidate.consumedInvalidations)
    evictStaleCaches(
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

  private fun resolutionTargets(modules: Set<Module>?): List<ManualRefreshTarget> {
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
      val key = SnapshotKey(fingerprintFor(state), resolveFromLibraries)
      modulesByKey.getOrPut(key) { mutableListOf() } += module
    }
    return modulesByKey.map { (key, groupedModules) -> ManualRefreshTarget(key, groupedModules) }
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
        existing.upgrade(request.intent)
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
    if (index === BindingIndex.EMPTY || isDisposed || project.isDisposed) return false
    return publishedResolution.value.presentation.contains(index)
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
        automaticToolWindowRequestMode()
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

  /** Resolves a graph in a cancellable smart read and invokes [onResult] on the EDT. */
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
              index(module, IndexRequestMode.BACKGROUND).graphs.firstOrNull {
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
    val application = ApplicationManager.getApplication()
    return if (application.isDispatchThread && !application.isUnitTestMode) {
      IndexRequestMode.BACKGROUND
    } else {
      IndexRequestMode.SYNCHRONOUS
    }
  }

  private fun automaticPresentationRequestMode(): IndexRequestMode {
    if (!automaticallyRefreshGraphData) return IndexRequestMode.STALE_CACHE_ONLY
    val application = ApplicationManager.getApplication()
    return if (application.isUnitTestMode) IndexRequestMode.SYNCHRONOUS
    else IndexRequestMode.AUTOMATIC_BACKGROUND
  }

  private fun automaticToolWindowRequestMode(): IndexRequestMode {
    return if (automaticallyRefreshGraphData) {
      IndexRequestMode.AUTOMATIC_BACKGROUND
    } else {
      IndexRequestMode.STALE_CACHE_ONLY
    }
  }

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
        IndexRequestMode.STALE_CACHE_ONLY -> return publication.presentation.index(module)
        IndexRequestMode.CACHE_ONLY -> projectStateService.currentStateOrSchedule(module)
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
      } ?: return BindingIndex.EMPTY
    if (!moduleState.isEnabled) return BindingIndex.EMPTY
    if (requestMode == IndexRequestMode.CACHE_ONLY) return BindingIndex.EMPTY

    return when (requestMode) {
      IndexRequestMode.CACHE_ONLY,
      IndexRequestMode.STALE_CACHE_ONLY -> BindingIndex.EMPTY
      IndexRequestMode.AUTOMATIC_BACKGROUND -> {
        scheduleBuild(module, IndexBuildIntent.AUTOMATIC)
        BindingIndex.EMPTY
      }
      IndexRequestMode.BACKGROUND -> {
        scheduleBuild(module, IndexBuildIntent.EXPLICIT)
        BindingIndex.EMPTY
      }
      IndexRequestMode.SYNCHRONOUS -> {
        val application = ApplicationManager.getApplication()
        val staleIndex = publication.current.index(module)
        val classificationPending =
          publication.classifiedSemanticClock < ingress.snapshot().semanticClock
        if (
          application.isUnitTestMode &&
            application.isDispatchThread &&
            classificationPending &&
            staleIndex !== BindingIndex.EMPTY
        ) {
          scheduleBuild(module, IndexBuildIntent.EXPLICIT)
          return staleIndex
        }
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

    val currentFingerprints = if (compilerSettingsChanged) moduleFingerprints() else null
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
    evictStaleCaches(inputs.roots)
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

  /** Drops stale library data without changing the published presentation generation. */
  private fun evictStaleCaches(
    currentRoots: Long,
    activeFingerprints: Set<IndexOptionsFingerprint>? = null,
  ) {
    libraryShards.keys.removeIf { key ->
      key.rootsGeneration != currentRoots ||
        (activeFingerprints != null && key.fingerprint !in activeFingerprints)
    }
  }

  private fun scheduleBuild(
    module: Module,
    intent: IndexBuildIntent,
    completion: CompletableDeferred<IndexBuildOutcome>? = null,
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
      ResolutionCoordinatorEvent.Build(module, intent, completions)
    }
    if (accepted == null) {
      completion?.complete(IndexBuildOutcome.CANCELED)
    }
  }

  private fun collectResolutionCandidate(
    targets: List<ManualRefreshTarget>,
    progress: IndexBuildProgressReporter,
    generationToken: IndexGenerationToken,
    buildSnapshot: ResolutionIngressSnapshot,
    manualRequest: ManualRefreshRequest?,
    capturedInvalidations: CapturedInvalidations,
  ): CollectedResolutionCandidate {
    check(!DumbService.isDumb(project))
    ProgressManager.checkCanceled()
    val inputs = currentInputs()
    val previous = sourceSnapshot
    val compilerSettingsChanged =
      previous != null && previous.inputs.compilerSettings != inputs.compilerSettings
    val fingerprintChanged =
      compilerSettingsChanged && previous!!.moduleFingerprints != moduleFingerprints()
    val candidateInvalidations =
      if (fingerprintChanged) {
        capturedInvalidations.copy(semanticRevision = capturedInvalidations.semanticRevision + 1)
      } else {
        capturedInvalidations
      }

    if (targets.isEmpty()) {
      return CollectedResolutionCandidate(
        source = null,
        consumedInvalidations = candidateInvalidations,
        semanticRevision = candidateInvalidations.semanticRevision,
        inputs = inputs,
        buildersByKey = emptyMap(),
        keysByModule = emptyMap(),
      )
    }

    val coldSweep = previous == null || previous.inputs.roots != inputs.roots || fingerprintChanged
    val collectedSource =
      if (coldSweep) {
        coldSweep(
          targets.first().key.fingerprint.options,
          inputs,
          candidateInvalidations,
          progress,
        )
      } else {
        incremental(previous!!, inputs, candidateInvalidations, progress)
      }
    if (resolutionCandidateIsSuperseded(buildSnapshot, manualRequest, inputs)) {
      throw ResolutionCandidateSupersededException()
    }

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
    for (target in targets) {
      ProgressManager.checkCanceled()
      if (resolutionCandidateIsSuperseded(buildSnapshot, manualRequest, inputs)) {
        throw ResolutionCandidateSupersededException()
      }
      val key = target.key
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
      indexBuilder.captureResolutionInputs(declarationSignatureFiles)
      buildersByKey[key] = indexBuilder
      for (module in target.modules) {
        keysByModule[module] = key
      }
    }
    return CollectedResolutionCandidate(
      source = finalizedSource,
      consumedInvalidations = candidateInvalidations,
      semanticRevision = candidateInvalidations.semanticRevision,
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
    builder.captureResolutionInputs(emptySet())
    return builder.build()
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

  private fun coldSweep(
    options: MetroOptions,
    inputs: IndexInputs,
    pending: CapturedInvalidations,
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
        transaction.applyShard(virtualFile, shardFor(file))
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
    pending: CapturedInvalidations,
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
          shardFor(file, forceRebuild = pending.forceAll),
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

  private fun containsRelevantAnnotation(file: KtFile, shortNames: Set<String>): Boolean {
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
    seedSharedDeclarationFingerprints(file, cached)
    return cached
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
      val shortNames =
        sourceSnapshot?.shortNames ?: projectSweepShortNames(file.metroIdeState().options)
      CachedValueProvider.Result.create(
        containsRelevantAnnotation(file, shortNames),
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
    if (!oldTreeMayDisappear) return false
    return when (val removed = event.oldChild ?: event.child) {
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
    return when {
      declaration is KtTypeAlias -> true
      declaration is KtProperty && declaration.hasModifier(KtTokens.CONST_KEYWORD) -> true
      declaration is KtClassOrObject -> {
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
      if (state != null) result.forceAll = true
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
        val alreadyTracked =
          state != null &&
            (fileVirtualFile in state.shards ||
              !state.dependencyOwners[fileVirtualFile].isNullOrEmpty() ||
              !state.sharedDeclarationOwners[fileVirtualFile].isNullOrEmpty())
        if (alreadyTracked) continue
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
    val removedSharedDeclarationWithoutFingerprint =
      change.removedTrackedSharedDeclaration &&
        previousFingerprint == null &&
        !hasSharedDeclarations
    val directlyChangesSharedDeclaration =
      change.sharedDeclarationChanges.any { it.forcesGlobalInvalidation }
    val asynchronouslyDiscoveredGlobalChange =
      metadataAffectsSharedDeclarations ||
        fingerprintChanged ||
        removedSharedDeclarationWithoutFingerprint
    val globalSemanticChange =
      directlyChangesSharedDeclaration || asynchronouslyDiscoveredGlobalChange

    val relevant =
      if (state == null) {
        isRelevantFileCached(file)
      } else {
        containsRelevantAnnotation(file, state.shortNames)
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
      dirty = pendingDirtyFiles.toSet(),
      requested = pendingRequestedFiles.toSet(),
      forceAll = forceAllFiles,
      semanticRevision = semanticRevision,
    )
  }

  /** Removes captured work only after its complete generation has published. */
  private fun consumeCapturedInvalidations(captured: CapturedInvalidations) {
    if (semanticRevision > captured.semanticRevision) return
    pendingDirtyFiles.removeAll(captured.dirty)
    pendingRequestedFiles.removeAll(captured.requested)
    if (captured.forceAll) forceAllFiles = false
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
    for (unused in notificationRequests) {
      if (isDisposed) return
      // Service disposal and scope cancellation own the consumer's lifetime.
      if (project.isDisposed) continue
      if (indexChanges.subscriptionCount.value == 0) continue
      if (isManualGraphDataRefreshRequired) {
        val previous = publishedResolution.getAndUpdate { publication ->
          if (publication.isDisposed) publication
          else publication.copy(manualStaleNotificationSent = true)
        }
        if (previous.isDisposed || previous.manualStaleNotificationSent) continue
      }
      indexChanges.emit(Unit)
    }
  }

  /** Publishes the current build progress for UI collectors. */
  private fun publishIndexBuildProgress(progress: IndexBuildProgress?) {
    if (isDisposed || project.isDisposed) return
    mutableIndexBuildProgress.value = progress
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
    mutableIndexBuildProgress.value = null
  }

  private companion object {
    const val MAX_CACHED_INDEXES = 8
    const val MAX_CONCURRENT_FILE_PRESENTATION_BUILDS = 2
    const val MAX_FILE_PRESENTATION_BUNDLES = 64
  }
}

private enum class IndexRequestMode {
  CACHE_ONLY,
  STALE_CACHE_ONLY,
  AUTOMATIC_BACKGROUND,
  BACKGROUND,
  SYNCHRONOUS,
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
) {
  fun upgrade(addedIntent: IndexBuildIntent) {
    if (intent == IndexBuildIntent.AUTOMATIC && addedIntent == IndexBuildIntent.EXPLICIT) {
      intent = IndexBuildIntent.EXPLICIT
    }
  }
}

private data class ManualRefreshRequest(val id: Long)

private data class ManualRefreshTarget(
  val key: SnapshotKey,
  val modules: List<Module>,
)

private data class CollectedResolutionCandidate(
  val source: SourceSnapshot?,
  val consumedInvalidations: CapturedInvalidations,
  val semanticRevision: Long,
  val inputs: IndexInputs,
  val buildersByKey: Map<SnapshotKey, BindingIndexBuilder>,
  val keysByModule: Map<Module, SnapshotKey>,
)

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
      previous.completions += added.completions
      previous
    }
    else -> added
  }
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

private val FORCE_TRACKER_KEY = Key.create<SimpleModificationTracker>("metro.shard.force.tracker")

private data class SnapshotKey(
  val fingerprint: IndexOptionsFingerprint,
  val resolveFromLibraries: Boolean,
)

private data class IndexInputs(val roots: Long, val compilerSettings: Long)

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
  val dirty: Set<VirtualFile>,
  val requested: Set<VirtualFile>,
  val forceAll: Boolean,
  val semanticRevision: Long,
)

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
  /** Maps shared declaration files to the shards that reference them. */
  val sharedDeclarationOwners: PartitionedFileMap<Set<VirtualFile>>,
  /** Reused when changed shards leave every effective binary lookup input unchanged. */
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

  fun snapshot(
    inputs: IndexInputs,
    moduleFingerprints: Map<Module, IndexOptionsFingerprint>,
    shortNames: Set<String>,
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
    val libraryInputsChanged =
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

/** Stores immutable hash buckets so updates copy only the buckets containing changed entries. */
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

private data class FinalizedSourceLibrarySummary(
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
