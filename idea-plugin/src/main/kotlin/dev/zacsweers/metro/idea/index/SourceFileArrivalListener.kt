// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

/**
 * Enrolls source arrivals whose parent directories may have no loaded PSI. The coordinator owns
 * relevance checks and directory traversal; existing PSI listeners handle declaration edits.
 */
internal class SourceFileArrivalListener(
  private val project: Project,
  private val onArrivals: (files: Set<VirtualFile>, directories: Set<VirtualFile>) -> Unit,
) : BulkFileListener {
  override fun after(events: List<VFileEvent>) {
    if (project.isDisposed) return
    val files = linkedSetOf<VirtualFile>()
    val directories = linkedSetOf<VirtualFile>()
    val fileIndex = ProjectFileIndex.getInstance(project)
    for (event in events) {
      val file =
        when (event) {
          is VFileCreateEvent -> event.file
          is VFileCopyEvent -> event.newParent.findChild(event.newChildName)
          is VFileMoveEvent -> event.file
          is VFilePropertyChangeEvent ->
            event.file.takeIf { event.propertyName == VirtualFile.PROP_NAME }
          else -> null
        } ?: continue
      if (!file.isValid || !fileIndex.isInContent(file)) continue
      if (file.isDirectory) {
        directories += file
      } else if (file.extension == "kt" || file.extension == "kts") {
        files += file
      }
    }
    if (files.isNotEmpty() || directories.isNotEmpty()) onArrivals(files, directories)
  }
}
