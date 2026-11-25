// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import java.io.Flushable
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.components.Position
import org.jetbrains.kotlin.incremental.components.ScopeKind

/**
 * Buffers [record] calls into an internal log and replays them to [delegate] on [flush]. Lets IC
 * lookups be collected during IR processing and written through once afterward. Entries are
 * deduplicated, which matches the set-semantics of the underlying tracker.
 */
internal class BufferingLookupTracker(private val delegate: LookupTracker) :
  LookupTracker by delegate, Flushable {
  private val buffer = mutableSetOf<Entry>()

  override fun record(
    filePath: String,
    position: Position,
    scopeFqName: String,
    scopeKind: ScopeKind,
    name: String,
  ) {
    buffer.add(Entry(filePath, position, scopeFqName, scopeKind, name))
  }

  override fun flush() {
    for (entry in buffer) {
      delegate.record(
        entry.filePath,
        entry.position,
        entry.scopeFqName,
        entry.scopeKind,
        entry.name,
      )
    }
    buffer.clear()
  }

  private data class Entry(
    val filePath: String,
    val position: Position,
    val scopeFqName: String,
    val scopeKind: ScopeKind,
    val name: String,
  )
}
