// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics.render

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.ir.IrScope
import java.io.File
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches source files for source-frame rendering.
 *
 * Missing or unreadable files resolve to null. That covers cross-module declarations, klibs, and
 * synthetic declarations; renderers can then fall back to IR-rendered signatures.
 */
@Inject
@SingleIn(IrScope::class)
internal class SourceFileCache {
  private val cache = ConcurrentHashMap<String, Optional<List<String>>>()

  fun linesFor(path: String): List<String>? =
    cache
      .computeIfAbsent(path) {
        val lines = runCatching { File(path).takeIf { it.isFile }?.readLines() }.getOrNull()
        Optional.ofNullable(lines)
      }
      .orElse(null)
}
