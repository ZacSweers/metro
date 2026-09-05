// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CompositeShortcutSet
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AutoScrollToSourceHandler
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.treeStructure.Tree
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.event.TreeSelectionListener
import kotlinx.coroutines.Job

/** Shares keyboard and optional preview navigation across Metro's source-backed trees. */
internal class MetroTreeNavigation(
  project: Project,
  private val tree: Tree,
  parentDisposable: Disposable,
  private val canNavigate: () -> Boolean,
  private val resolveAndNavigate: (requestFocus: Boolean) -> Job?,
) : Disposable {
  private val properties = PropertiesComponent.getInstance(project)
  private var disposed = false
  private var pendingNavigation: Job? = null
  private val selectionListener = TreeSelectionListener { cancelPendingNavigation() }
  private val autoScrollHandler =
    object : AutoScrollToSourceHandler() {
      override fun isAutoScrollMode(): Boolean =
        !disposed && properties.getBoolean(AUTOSCROLL_TO_SOURCE, false)

      override fun setAutoScrollMode(state: Boolean) {
        if (disposed) return
        properties.setValue(AUTOSCROLL_TO_SOURCE, state, false)
        if (state) navigate(requestFocus = false) else cancelPendingNavigation()
      }

      override fun scrollToSource(tree: Component) {
        if (isAutoScrollMode()) navigate(requestFocus = false)
      }
    }

  val autoscrollAction: AnAction = autoScrollHandler.createToggleAction()
  val openSourceAction: AnAction =
    object : AnAction("Go to Source", "Open the selected Metro declaration", null), DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !disposed && canNavigate()
      }

      override fun actionPerformed(e: AnActionEvent) {
        navigate(requestFocus = true)
      }
    }

  init {
    Disposer.register(parentDisposable, this)
    tree.addTreeSelectionListener(selectionListener)
    autoScrollHandler.install(tree)
    openSourceAction.registerCustomShortcutSet(
      CompositeShortcutSet(CommonShortcuts.getEditSource(), CommonShortcuts.ENTER),
      tree,
      this,
    )
    object : DoubleClickListener() {
        override fun onDoubleClick(event: MouseEvent): Boolean =
          navigate(requestFocus = true) != null
      }
      .installOn(tree)
  }

  /** Selection changes cancel a pending pointer lookup before it can navigate to the old row. */
  fun cancelPendingNavigation() {
    pendingNavigation?.cancel()
    pendingNavigation = null
  }

  /** Explicit navigation focuses the editor. Autoscroll preserves focus in the tree. */
  fun navigate(requestFocus: Boolean): Job? {
    cancelPendingNavigation()
    if (disposed || !canNavigate()) return null
    return resolveAndNavigate(requestFocus).also { pendingNavigation = it }
  }

  override fun dispose() {
    disposed = true
    cancelPendingNavigation()
    tree.removeTreeSelectionListener(selectionListener)
  }

  private companion object {
    const val AUTOSCROLL_TO_SOURCE = "dev.zacsweers.metro.idea.autoscrollToSource"
  }
}
