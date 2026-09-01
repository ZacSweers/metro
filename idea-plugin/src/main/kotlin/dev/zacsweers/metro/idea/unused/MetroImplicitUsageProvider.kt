// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.unused

import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.MetroDaemonRestartService
import dev.zacsweers.metro.idea.MetroIdeAnnotationClassIds
import dev.zacsweers.metro.idea.MetroIdeModuleState
import dev.zacsweers.metro.idea.MetroSettings
import dev.zacsweers.metro.idea.metroIdeState
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.utils.classId
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsTracker
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.toUElement

/**
 * Marks Metro framework entry points as implicitly used for IntelliJ's general dead-code analysis.
 *
 * This is the broad IDE signal for declarations that Metro consumes through generated graph code,
 * even when normal source references do not exist.
 */
class MetroImplicitUsageProvider : ImplicitUsageProvider {

  override fun isImplicitUsage(element: PsiElement): Boolean {
    return element.isMetroImplicitUsage()
  }

  override fun isImplicitRead(element: PsiElement): Boolean {
    return false
  }

  override fun isImplicitWrite(element: PsiElement): Boolean {
    return false
  }
}

internal fun PsiElement.isMetroImplicitUsage(
  allowResolutionOnEdt: Boolean = ApplicationManager.getApplication().isUnitTestMode
): Boolean {
  if (!MetroSettings.getInstance(project).state.suppressUnusedWarnings) return false
  val declaration = ownerDeclaration() ?: return false
  val application = ApplicationManager.getApplication()
  if (application.isDispatchThread && !allowResolutionOnEdt) {
    return project.service<MetroImplicitUsageCache>().answerOrSchedule(declaration)
  }
  return declaration.isMetroImplicitUsage(metroIdeState())
}

private typealias MetroAnnotationMatcher = (KtAnnotated?, Set<ClassId>) -> Boolean

private val exactAnnotationMatcher: MetroAnnotationMatcher = { annotated, classIds ->
  annotated.hasAnyMetroAnnotation(classIds)
}

private fun KtDeclaration.isMetroImplicitUsage(state: MetroIdeModuleState): Boolean {
  if (!state.isEnabled) return false
  return isMetroImplicitUsage(state.options, state.annotationClassIds, exactAnnotationMatcher)
}

private const val MAX_CACHED_IMPLICIT_USAGE_FILES = 32
private const val MAX_QUEUED_IMPLICIT_USAGE_FILES = 32
private const val MAX_CONCURRENT_IMPLICIT_USAGE_WORKERS = 2

private data class ImplicitUsageInputs(
  val psi: Long,
  val roots: Long,
  val compilerSettings: Long,
)

private data class FileImplicitUsages(
  val inputs: ImplicitUsageInputs,
  val answers: Map<ImplicitUsageDeclaration, Boolean>,
)

/** One file's demand for a specific project-input snapshot. */
private class ImplicitUsageRequest(val file: VirtualFile, val inputs: ImplicitUsageInputs)

/** Retains the file's worker slot until the job completes, including cancellation before start. */
private class ImplicitUsageWorker(val request: ImplicitUsageRequest, val job: Job)

/** Source range and declaration kind, valid until the next PSI change. */
private data class ImplicitUsageDeclaration(
  val kind: Class<out KtDeclaration>,
  val startOffset: Int,
  val endOffset: Int,
)

private fun KtDeclaration.implicitUsageDeclaration(): ImplicitUsageDeclaration {
  val range = textRange
  return ImplicitUsageDeclaration(javaClass, range.startOffset, range.endOffset)
}

/**
 * Caches resolved implicit usage answers for recently highlighted files.
 *
 * An EDT cache miss queues a background read for the whole file. Resolving annotation IDs avoids
 * suppressing warnings for unrelated annotations with the same name. PSI, project root, and
 * compiler setting changes invalidate the answers. Two workers share a bounded, per-file queue. The
 * queue keeps recent demand and discards obsolete input snapshots. Capacity-evicted files need a
 * later highlighting pass, requested when the batch finishes.
 */
@Service(Service.Level.PROJECT)
internal class MetroImplicitUsageCache(
  private val project: Project,
  private val scope: CoroutineScope,
) : Disposable {
  private val lock = Any()
  private var cachedInputs: ImplicitUsageInputs? = null
  private var disposed = false
  private var restartAfterBatch = false
  private val queuedFiles = linkedMapOf<VirtualFile, ImplicitUsageRequest>()
  private val activeFiles = mutableMapOf<VirtualFile, ImplicitUsageWorker>()
  private val computationStartObserver = AtomicReference<((VirtualFile) -> Unit)?>(null)
  private val restartObserver = AtomicReference<(() -> Unit)?>(null)
  private val answersByFile =
    object : LinkedHashMap<VirtualFile, Map<ImplicitUsageDeclaration, Boolean>>(16, 0.75f, true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<VirtualFile, Map<ImplicitUsageDeclaration, Boolean>>?
      ): Boolean = size > MAX_CACHED_IMPLICIT_USAGE_FILES
    }

  /** Returns a cached answer, or starts the file's background check and returns false for now. */
  fun answerOrSchedule(declaration: KtDeclaration): Boolean {
    val file = declaration.containingKtFile
    val virtualFile = file.virtualFile ?: return false
    val declarationId = declaration.implicitUsageDeclaration()
    val inputs = currentInputs()
    val answer =
      synchronized(lock) {
        if (!canSchedule()) return false
        invalidateFor(inputs)
        answersByFile[virtualFile]?.get(declarationId).also { cachedAnswer ->
          if (cachedAnswer == null && activeFiles[virtualFile]?.request?.inputs != inputs) {
            val queued = queuedFiles.remove(virtualFile)
            queuedFiles[virtualFile] =
              if (queued?.inputs == inputs) queued else ImplicitUsageRequest(virtualFile, inputs)
            if (queuedFiles.size > MAX_QUEUED_IMPLICIT_USAGE_FILES) {
              val oldest = queuedFiles.entries.iterator()
              oldest.next()
              oldest.remove()
              restartAfterBatch = true
            }
          }
        }
      }
    if (answer == null) startPendingWorkers()
    return answer ?: false
  }

  @TestOnly
  internal fun cachedAnswer(declaration: KtDeclaration): Boolean? {
    val virtualFile = declaration.containingKtFile.virtualFile ?: return null
    val declarationId = declaration.implicitUsageDeclaration()
    val inputs = currentInputs()
    return synchronized(lock) {
      if (!canSchedule()) return@synchronized null
      invalidateFor(inputs)
      answersByFile[virtualFile]?.get(declarationId)
    }
  }

  /** Observes worker launch before the computation waits for a smart read action. */
  @TestOnly
  internal fun setComputationStartObserver(observer: ((VirtualFile) -> Unit)?) {
    computationStartObserver.set(observer)
  }

  /** Observes refresh intent before the shared daemon restart service coalesces it. */
  @TestOnly
  internal fun setRestartObserver(observer: (() -> Unit)?) {
    restartObserver.set(observer)
  }

  @TestOnly
  internal fun queuedFiles(): List<VirtualFile> = synchronized(lock) { queuedFiles.keys.toList() }

  @TestOnly internal fun activeWorkerCount(): Int = synchronized(lock) { activeFiles.size }

  /** Reserves slots under the lock before any lazy job can execute or complete. */
  private fun startPendingWorkers() {
    val workers =
      synchronized(lock) {
        if (!canSchedule()) {
          queuedFiles.clear()
          return@synchronized emptyList()
        }
        val inputs = currentInputs()
        buildList {
          val pending = queuedFiles.entries.iterator()
          while (pending.hasNext() && activeFiles.size < MAX_CONCURRENT_IMPLICIT_USAGE_WORKERS) {
            val request = pending.next().value
            if (request.inputs != inputs || !request.file.isValid) {
              pending.remove()
              continue
            }
            if (request.file in activeFiles) continue
            pending.remove()
            val job = scope.launch(start = CoroutineStart.LAZY) { compute(request) }
            val worker = ImplicitUsageWorker(request, job)
            activeFiles[request.file] = worker
            add(worker)
          }
        }
      }
    for (worker in workers) {
      worker.job.invokeOnCompletion { complete(worker) }
      worker.job.start()
    }
    restartEvictedDemandIfIdle()
  }

  /** A newer demand stays queued while this file's previous worker finishes. */
  private fun complete(worker: ImplicitUsageWorker) {
    val released = synchronized(lock) { activeFiles.remove(worker.request.file, worker) }
    if (released) startPendingWorkers()
  }

  /**
   * Attempts this snapshot once; a newer file demand starts after this worker releases its slot.
   */
  private suspend fun compute(request: ImplicitUsageRequest) {
    try {
      computationStartObserver.get()?.invoke(request.file)
      val result =
        smartReadAction(project) {
          if (project.isDisposed || !request.file.isValid) return@smartReadAction null
          val inputs = currentInputs()
          if (inputs != request.inputs) return@smartReadAction null
          val file = PsiManager.getInstance(project).findFile(request.file) as? KtFile
          if (file == null || !file.isValid) return@smartReadAction null
          val state = file.metroIdeState()
          val answers = buildMap {
            PsiTreeUtil.processElements(file) { element ->
              ProgressManager.checkCanceled()
              if (element is KtDeclaration) {
                put(element.implicitUsageDeclaration(), element.isMetroImplicitUsage(state))
              }
              true
            }
          }
          FileImplicitUsages(inputs, answers)
        }
      currentCoroutineContext().ensureActive()
      if (result != null && publish(request, result)) requestRestart()
    } catch (exception: CancellationException) {
      throw exception
    } catch (_: ProcessCanceledException) {
      // A later highlighting pass can request the file again after platform cancellation.
    } catch (failure: Exception) {
      logger<MetroImplicitUsageCache>().warn("Metro implicit usage analysis failed", failure)
    }
  }

  private fun publish(
    request: ImplicitUsageRequest,
    result: FileImplicitUsages,
  ): Boolean {
    if (project.isDisposed || !request.file.isValid) return false
    if (result.inputs != currentInputs()) return false
    return synchronized(lock) {
      if (!canSchedule()) return@synchronized false
      if (result.inputs != currentInputs()) return@synchronized false
      val worker = activeFiles[request.file] ?: return@synchronized false
      if (worker.request !== request || !worker.job.isActive) return@synchronized false
      invalidateFor(result.inputs)
      answersByFile[request.file] = result.answers
      true
    }
  }

  /** Includes batches whose workers all abandoned their reads without publishing answers. */
  private fun restartEvictedDemandIfIdle() {
    val restart =
      synchronized(lock) {
        val idle = activeFiles.isEmpty() && queuedFiles.isEmpty()
        if (!idle || !restartAfterBatch) return@synchronized false
        restartAfterBatch = false
        canSchedule()
      }
    if (restart) requestRestart()
  }

  private fun requestRestart() {
    if (!synchronized(lock) { canSchedule() }) return
    restartObserver.get()?.invoke()
    project.service<MetroDaemonRestartService>().requestRestart()
  }

  /** Called under [lock] so disposal and queue ownership use the same boundary. */
  private fun canSchedule(): Boolean = !disposed && !project.isDisposed && scope.isActive

  /** Clears obsolete answers and queued work. Active jobs release their slots on completion. */
  private fun invalidateFor(inputs: ImplicitUsageInputs) {
    if (cachedInputs == inputs) return
    cachedInputs = inputs
    answersByFile.clear()
    queuedFiles.entries.removeIf { it.value.inputs != inputs }
  }

  override fun dispose() {
    val workers =
      synchronized(lock) {
        if (disposed) return
        disposed = true
        queuedFiles.clear()
        answersByFile.clear()
        restartAfterBatch = false
        activeFiles.values.toList().also { activeFiles.clear() }
      }
    workers.forEach { it.job.cancel() }
    computationStartObserver.set(null)
    restartObserver.set(null)
  }

  private fun currentInputs(): ImplicitUsageInputs {
    return ImplicitUsageInputs(
      psi = PsiModificationTracker.getInstance(project).modificationCount,
      roots = ProjectRootModificationTracker.getInstance(project).modificationCount,
      compilerSettings = KotlinCompilerSettingsTracker.getInstance(project).modificationCount,
    )
  }
}

private fun KtDeclaration.isMetroImplicitUsage(
  options: MetroOptions,
  annotationClassIds: MetroIdeAnnotationClassIds,
  hasAnnotation: MetroAnnotationMatcher,
): Boolean {
  return when (this) {
    // Contributed objects are instance bindings even though they have no injectable constructor.
    is KtClassOrObject ->
      hasGeneratedInjectionEntryPoint(options, annotationClassIds, hasAnnotation)
    is KtConstructor<*> -> hasAnnotation(this, annotationClassIds.constructorInjectionAnnotations)
    is KtNamedFunction -> hasAnnotation(this, annotationClassIds.functionAnnotations)
    is KtProperty -> hasAnyMetroAnnotationOnPropertyOrGetter(annotationClassIds, hasAnnotation)
    is KtPropertyAccessor ->
      isGetter && hasAnnotation(this, annotationClassIds.bindingContainerCallableAnnotations)
    is KtParameter -> hasAnnotation(this, annotationClassIds.providesAnnotations)
    else -> false
  }
}

private fun PsiElement.ownerDeclaration(): KtDeclaration? {
  if (navigationElement !== this) {
    (navigationElement as? KtDeclaration)?.let {
      return it
    }
    PsiTreeUtil.getParentOfType(navigationElement, KtDeclaration::class.java, false)?.let {
      return it
    }
  }

  return when (this) {
    is KtDeclaration -> this
    is PsiNameIdentifierOwner -> parent as? KtDeclaration
    else -> PsiTreeUtil.getParentOfType(this, KtDeclaration::class.java, false)
  }
}

private fun KtClassOrObject.hasGeneratedInjectionEntryPoint(
  options: MetroOptions,
  annotationClassIds: MetroIdeAnnotationClassIds,
  hasAnnotation: MetroAnnotationMatcher,
): Boolean {
  if (hasAnnotation(this, annotationClassIds.classLevelInjectionAnnotations)) return true
  if (hasContributionProviderGeneratedUsage(options, annotationClassIds, hasAnnotation)) return true

  return hasInjectAnnotatedConstructor(
    annotationClassIds.constructorInjectionAnnotations,
    hasAnnotation,
  )
}

private fun KtClassOrObject.hasInjectAnnotatedConstructor(
  constructorAnnotations: Set<ClassId>,
  hasAnnotation: MetroAnnotationMatcher,
): Boolean {
  return hasAnnotation(primaryConstructor, constructorAnnotations) ||
    secondaryConstructors.any { hasAnnotation(it, constructorAnnotations) }
}

private fun KtClassOrObject.hasContributionProviderGeneratedUsage(
  options: MetroOptions,
  annotationClassIds: MetroIdeAnnotationClassIds,
  hasAnnotation: MetroAnnotationMatcher,
): Boolean {
  return options.generateContributionProviders &&
    hasAnnotation(this, annotationClassIds.bindingContributionAnnotations) &&
    !hasAnnotation(this, annotationClassIds.contributionProviderExclusionAnnotations)
}

private fun KtProperty.hasAnyMetroAnnotationOnPropertyOrGetter(
  annotationClassIds: MetroIdeAnnotationClassIds,
  hasAnnotation: MetroAnnotationMatcher,
): Boolean {
  return hasAnnotation(this, annotationClassIds.bindingContainerCallableAnnotations) ||
    hasAnnotation(getter, annotationClassIds.bindingContainerCallableAnnotations)
}

private fun KtAnnotated?.hasAnyMetroAnnotation(classIds: Set<ClassId>): Boolean {
  return this != null && annotationEntries.any { it.isAnyMetroAnnotation(classIds) }
}

private fun KtAnnotationEntry.isAnyMetroAnnotation(classIds: Set<ClassId>): Boolean {
  val annotationClassId =
    when (val annotationClass = typeReference?.mainReference?.resolve()) {
      is KtClassOrObject -> annotationClass.fqName?.let(ClassId::topLevel)
      is PsiClass -> annotationClass.classId
      is PsiMember -> annotationClass.containingClass?.classId
      else -> null
    }

  if (annotationClassId != null) return annotationClassId in classIds

  val uastClassId = toUElement(UAnnotation::class.java)?.resolve()?.classId
  if (uastClassId != null) return uastClassId in classIds

  // PSI/UAST reference resolution can fail for library annotations outside JVM contexts (like
  // klib-backed annotations in KMP common source sets); the Analysis API is authoritative.
  val typeReference = typeReference ?: return false
  val application = ApplicationManager.getApplication()

  val resolve = {
    analyze(typeReference) {
      val classId = (typeReference.type.fullyExpandedType as? KaClassType)?.classId
      classId != null && classId in classIds
    }
  }
  return if (application.isDispatchThread) allowAnalysisOnEdt(resolve) else resolve()
}
