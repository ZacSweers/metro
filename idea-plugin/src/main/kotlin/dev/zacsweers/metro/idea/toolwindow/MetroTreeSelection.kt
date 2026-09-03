// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.tree.TreeVisitor
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import javax.swing.SwingUtilities
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.TreePath
import org.jetbrains.concurrency.Promise

/** Owns asynchronous selection without letting a completed traversal overwrite newer UI intent. */
internal class MetroTreeSelection(
  private val tree: Tree,
  parentDisposable: Disposable,
  private val traverse: (TreeVisitor) -> Promise<TreePath?> = { TreeUtil.promiseVisit(tree, it) },
) : Disposable {
  private var disposed = false
  private var requestVersion = 0L
  private var selectionVersion = 0L
  private var applyingSelection = false
  private val selectionListener = TreeSelectionListener { event ->
    val previous = event.oldLeadSelectionPath
    val removedByModel = tree.selectionPath == null && previous != null && !containsPath(previous)
    if (!applyingSelection && !removedByModel) selectionVersion++
  }

  init {
    Disposer.register(parentDisposable, this)
    tree.addTreeSelectionListener(selectionListener)
  }

  /** Captures selection before model invalidation, which can remove the selected row. */
  fun request(isCurrent: () -> Boolean = { true }): Request {
    return Request(++requestVersion, selectionVersion, isCurrent)
  }

  /** A new browser intent can prevent selection while the original action finishes resolving. */
  fun cancelPendingSelection() {
    requestVersion++
  }

  /**
   * Traversal only finds a path. Selection and scrolling happen after the EDT ownership checks. A
   * current action still receives its resolved path if the user moved the tree selection.
   */
  inner class Request
  internal constructor(
    private val version: Long,
    private val selectionAtStart: Long,
    private val isCurrent: () -> Boolean,
  ) {
    fun select(visitor: TreeVisitor, onResult: (TreePath?) -> Unit = {}) {
      if (disposed || !isCurrent()) return
      traverse(visitor).onProcessed { found ->
        SwingUtilities.invokeLater {
          if (disposed || !isCurrent()) return@invokeLater
          val path = found?.takeIf(::containsPath)
          val maySelect = version == requestVersion && selectionAtStart == selectionVersion
          if (path != null && maySelect) {
            applyingSelection = true
            try {
              tree.makeVisible(path)
              tree.selectionPath = path
              TreeUtil.scrollToVisible(tree, path, false)
            } finally {
              applyingSelection = false
            }
          }
          onResult(path)
        }
      }
    }
  }

  /** Model replacement clears selection after removing its path; deliberate clearing keeps it. */
  private fun containsPath(path: TreePath): Boolean {
    val model = tree.model ?: return false
    val components = path.path
    if (components.firstOrNull() != model.root) return false
    for (index in 1 until components.size) {
      if (model.getIndexOfChild(components[index - 1], components[index]) < 0) return false
    }
    return true
  }

  override fun dispose() {
    disposed = true
    cancelPendingSelection()
    tree.removeTreeSelectionListener(selectionListener)
  }
}
