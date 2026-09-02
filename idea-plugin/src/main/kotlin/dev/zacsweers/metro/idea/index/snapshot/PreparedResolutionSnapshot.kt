// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.module.Module
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingIndexBuilder

/**
 * Transfers one attempt's captured data from its read action to index sealing. The mutable builders
 * stay private, and callers receive complete indexes only after every target has finished.
 */
internal class PreparedResolutionSnapshot(
  val source: SourceSnapshot?,
  val inputs: IndexInputs,
  buildersByKey: Map<SnapshotKey, BindingIndexBuilder>,
  keysByModule: Map<Module, SnapshotKey>,
) {
  private val buildersByKey = buildersByKey.toMap()
  val targetKeys: Set<SnapshotKey> = buildersByKey.keys.toSet()
  val keysByModule: Map<Module, SnapshotKey> = keysByModule.toMap()

  /** Seals this attempt outside the read action and checks supersession before each target. */
  fun buildIndexes(checkCurrent: () -> Unit): Map<SnapshotKey, BindingIndex> {
    return buildMap(buildersByKey.size) {
      for ((key, builder) in buildersByKey) {
        checkCurrent()
        put(key, builder.build())
      }
    }
  }
}
