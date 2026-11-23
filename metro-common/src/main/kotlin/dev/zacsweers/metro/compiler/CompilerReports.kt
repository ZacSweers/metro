// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText

private const val INDEX = ".metro-report-files"

/** Clears the reports recorded by the previous compilation. */
internal fun Path.resetCompilerReports() {
  createDirectories()
  for (report in compilerReportFiles().orEmpty()) {
    report.deleteIfExists()
  }

  val index = resolve(INDEX)
  index.deleteIfExists()
  index.createFile()
}

/** Creates an empty report under this destination and records its path for the next compilation. */
public fun Path.createCompilerReport(name: String): Path {
  val file = requireNotNull(reportPath(name)) { "Invalid Metro report path '$name'" }
  file.createParentDirectories()
  synchronized(this) {
    resolve(INDEX).appendText("$name\u0000")
    file.deleteIfExists()
    return file.createFile()
  }
}

/** Lists the files recorded by this compilation. Older compilers leave no index. */
public fun Path.compilerReportFiles(): List<Path>? {
  val index = resolve(INDEX)
  if (!index.isRegularFile(NOFOLLOW_LINKS)) {
    return null
  }

  // NUL terminates each path so an interrupted write can't identify a different file.
  return index.readText().split('\u0000').dropLast(1).distinct().mapNotNull { name ->
    reportPath(name)?.takeIf { it.isRegularFile(NOFOLLOW_LINKS) }
  }
}

/**
 * Keeps indexed paths inside the destination even if someone has replaced a directory with a link.
 */
private fun Path.reportPath(name: String): Path? {
  val relative = fileSystem.getPath(name)
  if (relative.root != null) {
    return null
  }

  var file = this
  for (part in relative) {
    file = file.resolve(part)
    if (part.toString() == ".." || file.isSymbolicLink()) {
      return null
    }
  }

  return file
}
