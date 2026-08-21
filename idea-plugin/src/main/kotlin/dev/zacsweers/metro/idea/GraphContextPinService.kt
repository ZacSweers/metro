// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.Disposer
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.matchingContext
import dev.zacsweers.metro.idea.model.matchingContextEntry
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Project-session presentation state for one preferred graph context. */
@Service(Service.Level.PROJECT)
internal class GraphContextPinService(private val project: com.intellij.openapi.project.Project) {
  private val pinnedPathRef = AtomicReference<GraphPath?>()
  private val listeners = Collections.newSetFromMap(ConcurrentHashMap<() -> Unit, Boolean>())

  val pinnedPath: GraphPath?
    get() = pinnedPathRef.get()

  fun pin(path: GraphPath) {
    if (pinnedPathRef.getAndSet(path) != path) notifyChanged()
  }

  fun clear() {
    if (pinnedPathRef.getAndSet(null) != null) notifyChanged()
  }

  fun clearIf(path: GraphPath): Boolean {
    if (!pinnedPathRef.compareAndSet(path, null)) return false
    notifyChanged()
    return true
  }

  fun matchingContext(contexts: Iterable<GraphContext>): GraphContext? {
    val path = pinnedPath ?: return null
    return contexts.matchingContext(path)
  }

  fun <T> matchingEntry(values: Map<GraphContext, T>): Map.Entry<GraphContext, T>? {
    val path = pinnedPath ?: return null
    return values.matchingContextEntry(path)
  }

  fun addListener(parentDisposable: Disposable, listener: () -> Unit) {
    listeners += listener
    Disposer.register(parentDisposable) { listeners -= listener }
  }

  private fun notifyChanged() {
    val notify = {
      if (!project.isDisposed) {
        DaemonCodeAnalyzer.getInstance(project).restart()
        listeners.forEach { it() }
      }
    }
    val application = ApplicationManager.getApplication()
    if (application.isDispatchThread) {
      notify()
    } else {
      application.invokeLater(notify)
    }
  }
}
