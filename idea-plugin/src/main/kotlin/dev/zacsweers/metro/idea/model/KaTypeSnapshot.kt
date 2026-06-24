// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypePointer
import org.jetbrains.kotlin.name.ClassId

/**
 * A session-free snapshot of a [org.jetbrains.kotlin.analysis.api.types.KaType].
 *
 * [pointer] can restore the semantic type inside a [org.jetbrains.kotlin.analysis.api.KaSession],
 * while [renderedType] and [shortType] give cached keys and UI text to cross-session indexes.
 * Equality is structural by [renderedType]; Analysis API pointers are intentionally excluded
 * because two pointers can point at equivalent type renderings while still being different pointer
 * objects.
 */
internal class KaTypeSnapshot(
  val pointer: KaTypePointer<KaType>,
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
