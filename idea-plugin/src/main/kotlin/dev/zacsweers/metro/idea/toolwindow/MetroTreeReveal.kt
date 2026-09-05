// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.ui.tree.TreeVisitor
import dev.zacsweers.metro.idea.navigation.MetroRevealTarget
import javax.swing.tree.TreePath

/** Restricts browser traversal to the exact root/extension path requested by the editor action. */
internal fun metroRevealVisitor(
  target: MetroRevealTarget,
  nodeAt: (TreePath) -> MetroTreeNode?,
): TreeVisitor {
  return TreeVisitor { path ->
    when (val node = nodeAt(path)) {
      is MetroTreeNode.Root -> TreeVisitor.Action.CONTINUE
      is MetroTreeNode.Graph ->
        when {
          node.context.path != target.path -> TreeVisitor.Action.SKIP_CHILDREN
          target.binding == null -> TreeVisitor.Action.INTERRUPT
          else -> TreeVisitor.Action.CONTINUE
        }
      is MetroTreeNode.Category,
      is MetroTreeNode.Multibinding -> TreeVisitor.Action.CONTINUE
      is MetroTreeNode.BindingRow ->
        if (node.matchesRevealTarget(target)) TreeVisitor.Action.INTERRUPT
        else TreeVisitor.Action.SKIP_CHILDREN
      else -> TreeVisitor.Action.SKIP_CHILDREN
    }
  }
}

/** Compares durable identities after selection, with no PSI resolution on the EDT. */
internal fun MetroTreeNode.matchesRevealTarget(target: MetroRevealTarget): Boolean {
  if (this is MetroTreeNode.Graph) return target.binding == null && context.path == target.path
  if (this !is MetroTreeNode.BindingRow) return false
  val requestedBinding = target.binding ?: return false
  if (binding.pointer != requestedBinding.pointer || binding.typeKey != requestedBinding.key)
    return false
  var owner = parent
  while (owner != null && owner !is MetroTreeNode.Graph) owner = owner.parent
  return owner?.context?.path == target.path
}
