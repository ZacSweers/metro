// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import org.jetbrains.kotlin.name.ClassId

/**
 * A session-free snapshot of a [org.jetbrains.kotlin.analysis.api.types.KaType].
 *
 * [renderedType] and [shortType] give cached keys and UI text to cross-session indexes, and
 * [classId] the type's class for key matching. Equality is structural by [renderedType], so a
 * snapshot can outlive the [org.jetbrains.kotlin.analysis.api.KaSession] it was built in.
 */
internal class KaTypeSnapshot(
  val renderedType: String,
  val shortType: String = renderedType,
  val classId: ClassId?,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is KaTypeSnapshot) return false
    return renderedType == other.renderedType
  }

  override fun hashCode(): Int = renderedType.hashCode()

  override fun toString(): String = renderedType
}
