// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.reporting

import okio.ByteString.Companion.encodeUtf8
import okio.utf8Size

/** Keeps report names readable and hashes long graph names to fit filesystem filename limits. */
public fun graphReportFileName(graphName: String, extension: String, prefix: String = ""): String {
  val fileName = "$prefix${graphName.replace('.', '-')}.$extension"
  if (fileName.utf8Size() <= 255) return fileName
  return "$prefix${graphName.encodeUtf8().sha256().hex()}.$extension"
}
