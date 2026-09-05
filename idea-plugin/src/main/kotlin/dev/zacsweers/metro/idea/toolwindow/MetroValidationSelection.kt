// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.ui.tree.TreeVisitor
import javax.swing.tree.TreePath

/** Finds the same diagnostic or source row after its validation ancestor gains a stale label. */
internal fun validationSelectionVisitor(
  selected: MetroTreeNode,
  nodeAt: (TreePath) -> MetroTreeNode?,
): TreeVisitor? {
  var ancestor: MetroTreeNode? = selected
  var diagnostic: MetroTreeNode.Diagnostic? = null
  while (ancestor != null && ancestor !is MetroTreeNode.Validation) {
    if (ancestor is MetroTreeNode.Diagnostic) diagnostic = ancestor
    ancestor = ancestor.parent
  }
  val validation = ancestor ?: return null
  val result = validation.result
  val selectedDiagnostic = diagnostic?.diagnostic
  return TreeVisitor { path ->
    val node = nodeAt(path)
    when {
      node is MetroTreeNode.Root -> TreeVisitor.Action.CONTINUE
      node is MetroTreeNode.Graph ->
        if (node.context.path == result.context.path) TreeVisitor.Action.CONTINUE
        else TreeVisitor.Action.SKIP_CHILDREN
      node is MetroTreeNode.Validation ->
        when {
          node.result !== result -> TreeVisitor.Action.SKIP_CHILDREN
          selected is MetroTreeNode.Validation -> TreeVisitor.Action.INTERRUPT
          else -> TreeVisitor.Action.CONTINUE
        }
      node is MetroTreeNode.Diagnostic ->
        when {
          node.diagnostic !== selectedDiagnostic -> TreeVisitor.Action.SKIP_CHILDREN
          selected is MetroTreeNode.Diagnostic -> TreeVisitor.Action.INTERRUPT
          else -> TreeVisitor.Action.CONTINUE
        }
      node is MetroTreeNode.StackEntry && selected is MetroTreeNode.StackEntry ->
        if (node.index == selected.index) TreeVisitor.Action.INTERRUPT
        else TreeVisitor.Action.SKIP_CHILDREN
      node is MetroTreeNode.BindingRow && selected is MetroTreeNode.BindingRow ->
        if (node.binding === selected.binding) TreeVisitor.Action.INTERRUPT
        else TreeVisitor.Action.SKIP_CHILDREN
      node is MetroTreeNode.Summary && selected is MetroTreeNode.Summary ->
        if (node.text == selected.text) TreeVisitor.Action.INTERRUPT
        else TreeVisitor.Action.SKIP_CHILDREN
      else -> TreeVisitor.Action.SKIP_CHILDREN
    }
  }
}
