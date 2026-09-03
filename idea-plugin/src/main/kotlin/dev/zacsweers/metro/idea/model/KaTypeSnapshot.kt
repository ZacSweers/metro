// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.util.containers.Interner
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

/**
 * A session-free snapshot of a [org.jetbrains.kotlin.analysis.api.types.KaType].
 *
 * [renderedType] and [shortType] serve as cache keys and UI text. [classId] identifies the class
 * for key matching. [typeArguments] keep the type navigable after the session ends, the way
 * `IrTypeKey` navigates its `IrType`.
 *
 * Equality is structural by [renderedType], so a snapshot can outlive the
 * [org.jetbrains.kotlin.analysis.api.KaSession] it was built in.
 */
internal class KaTypeSnapshot(
  renderedType: String,
  shortType: String = renderedType,
  val classId: ClassId?,
  val typeArguments: List<KaTypeArgumentSnapshot> = emptyList(),
  val isMarkedNullable: Boolean = false,
  val isError: Boolean = false,
  /** A missing class name lets source arrivals retry only requests that can resolve differently. */
  val unresolvedClassName: Name? = null,
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

/** A positional type argument. Stars retain their slot when a type is rebuilt. */
internal sealed interface KaTypeArgumentSnapshot {
  val type: KaTypeSnapshot?

  data object Star : KaTypeArgumentSnapshot {
    override val type: KaTypeSnapshot? = null
  }

  class Typed(
    override val type: KaTypeSnapshot,
    val variance: Variance,
  ) : KaTypeArgumentSnapshot
}
