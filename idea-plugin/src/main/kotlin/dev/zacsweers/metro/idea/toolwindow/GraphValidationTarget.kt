// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.openapi.vfs.VirtualFile
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import org.jetbrains.kotlin.name.ClassId

/** A graph declaration, optionally restricted to one explicitly requested compilation path. */
internal data class GraphValidationTarget(
  val classId: ClassId,
  val file: VirtualFile?,
  val path: GraphPath? = null,
) {
  /** Tree rows use durable source and path identities during EDT selection. */
  fun matches(context: GraphContext): Boolean {
    if (context.graph.classId != classId) return false
    if (file != null && context.graph.pointer.virtualFile != file) return false
    return path == null || context.path == path
  }
}

/** Keeps the pinned compilation's ancestor suffix when it contains the requested declaration. */
internal fun graphValidationPath(
  classId: ClassId,
  file: VirtualFile?,
  pinnedPath: GraphPath?,
): GraphPath? {
  if (pinnedPath == null) return null
  val index =
    pinnedPath.segments.indexOfFirst {
      it.classId == classId && (file == null || it.file == file)
    }
  if (index < 0) return null
  return GraphPath(pinnedPath.segments.drop(index), pinnedPath.dynamicGraphId)
}
