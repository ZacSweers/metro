// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly

/**
 * Resolves navigation targets in a background read action.
 *
 * Each editor or tool window keeps only its newest request. Optional target ordering runs in the
 * same read action after the pointers resolve. The callback runs on the EDT if the request is still
 * current and its owner is still open.
 */
@Service(Service.Level.PROJECT)
internal class MetroNavigationService(
  private val project: Project,
  private val scope: CoroutineScope,
) : Disposable {
  private data class NavigationRequest(val identity: Any, val job: Job)

  private val lock = Any()
  private var disposed = false
  private val requestsByOwner = IdentityHashMap<Any, NavigationRequest>()
  private val targetResolutionObserver = AtomicReference<(suspend () -> Unit)?>(null)

  init {
    // Editor release can happen while a cold query is suspended waiting for project indexes.
    EditorFactory.getInstance()
      .addEditorFactoryListener(
        object : EditorFactoryListener {
          override fun editorReleased(event: EditorFactoryEvent) {
            val editor = event.editor
            if (editor.project !== project) return
            val request = synchronized(lock) { requestsByOwner.remove(editor) }
            request?.job?.cancel()
          }
        },
        this,
      )
  }

  /** A newer request from [owner] cancels and supersedes its previous request. */
  fun resolveTargets(
    owner: Editor,
    targets: List<SmartPsiElementPointer<*>>,
    orderTargets: ((List<PsiElement>) -> List<PsiElement>)? = null,
    onResolved: (List<PsiElement>) -> Unit,
  ): Job? = resolveTargets(owner, { owner.isDisposed }, targets, orderTargets, onResolved)

  /** Disposing [owner] prevents its pending navigation callback from running. */
  fun resolveTargets(
    owner: Disposable,
    targets: List<SmartPsiElementPointer<*>>,
    orderTargets: ((List<PsiElement>) -> List<PsiElement>)? = null,
    onResolved: (List<PsiElement>) -> Unit,
  ): Job? = resolveTargets(owner, { Disposer.isDisposed(owner) }, targets, orderTargets, onResolved)

  /** Cold editor queries share pointer navigation's latest-request and editor-lifetime guards. */
  fun <T> runEditorRequest(
    owner: Editor,
    resolve: suspend () -> T,
    onResolved: (T) -> Unit,
  ): Job? = runRequest(owner, { owner.isDisposed }, resolve, onResolved)

  private fun resolveTargets(
    owner: Any,
    isOwnerDisposed: () -> Boolean,
    targets: List<SmartPsiElementPointer<*>>,
    orderTargets: ((List<PsiElement>) -> List<PsiElement>)?,
    onResolved: (List<PsiElement>) -> Unit,
  ): Job? {
    return runRequest(
      owner,
      isOwnerDisposed,
      resolve = {
        targetResolutionObserver.get()?.invoke()
        readAction {
          val resolved = buildList {
            for (target in targets) {
              ProgressManager.checkCanceled()
              target.element?.let(::add)
            }
          }
          if (orderTargets == null) resolved else orderTargets(resolved)
        }
      },
      onResolved = { onResolved(it.filter(PsiElement::isValid)) },
    )
  }

  /** Publishes on the EDT only while this request still owns its editor or tool-window slot. */
  private fun <T> runRequest(
    owner: Any,
    isOwnerDisposed: () -> Boolean,
    resolve: suspend () -> T,
    onResolved: (T) -> Unit,
  ): Job? {
    if (project.isDisposed || isOwnerDisposed()) return null
    val requestIdentity = Any()
    val job =
      scope.launch(start = CoroutineStart.LAZY) {
        if (project.isDisposed || !isCurrent(owner, requestIdentity) || isOwnerDisposed()) {
          return@launch
        }
        val result = resolve()
        withContext(Dispatchers.EDT) {
          if (project.isDisposed || isOwnerDisposed()) return@withContext
          if (!isCurrent(owner, requestIdentity)) return@withContext
          onResolved(result)
        }
      }

    val previous =
      synchronized(lock) {
        if (disposed) {
          job.cancel()
          return null
        }
        requestsByOwner.put(owner, NavigationRequest(requestIdentity, job))
      }
    previous?.job?.cancel()
    job.invokeOnCompletion {
      synchronized(lock) {
        if (requestsByOwner[owner]?.identity === requestIdentity) {
          requestsByOwner.remove(owner)
        }
      }
    }
    job.start()
    return job
  }

  @TestOnly
  internal fun setTargetResolutionObserver(observer: (suspend () -> Unit)?) {
    targetResolutionObserver.set(observer)
  }

  private fun isCurrent(owner: Any, requestIdentity: Any): Boolean {
    return synchronized(lock) { requestsByOwner[owner]?.identity === requestIdentity }
  }

  /** Service disposal removes the application listener and releases pending owner references. */
  override fun dispose() {
    val jobs =
      synchronized(lock) {
        disposed = true
        val pending = requestsByOwner.values.map { it.job }
        requestsByOwner.clear()
        pending
      }
    for (job in jobs) job.cancel()
  }
}
