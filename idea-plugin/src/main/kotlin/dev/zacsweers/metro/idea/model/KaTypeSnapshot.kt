// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.util.containers.Interner
import org.jetbrains.kotlin.name.ClassId

/**
 * A session-free snapshot of a [org.jetbrains.kotlin.analysis.api.types.KaType].
 *
 * [renderedType] and [shortType] give cached keys and UI text to cross-session indexes, and
 * [classId] the type's class for key matching. Equality is structural by [renderedType], so a
 * snapshot can outlive the [org.jetbrains.kotlin.analysis.api.KaSession] it was built in.
 */
internal class KaTypeSnapshot(
  renderedType: String,
  shortType: String = renderedType,
  val classId: ClassId?,
) {
  // Renders repeat heavily across entries, so intern them to keep the index's retained size flat.
  val renderedType: String = RENDER_INTERNER.intern(renderedType)
  val shortType: String = RENDER_INTERNER.intern(shortType)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is KaTypeSnapshot) return false
    return renderedType == other.renderedType
  }

  override fun hashCode(): Int = renderedType.hashCode()

  override fun toString(): String = renderedType

  companion object {
    private val RENDER_INTERNER = Interner.createWeakInterner<String>()
  }
}
