// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.util.containers.Interner
import dev.zacsweers.metro.compiler.graph.identity.TypeIdentity
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

/**
 * A session-free snapshot of a [org.jetbrains.kotlin.analysis.api.types.KaType].
 *
 * [identity] decides equality. [renderedType] and [shortType] are UI text. [classId] identifies the
 * class for key matching. [typeArguments] keep the type navigable after the session ends, the way
 * `IrTypeKey` navigates its `IrType`.
 *
 * The snapshot can outlive the [org.jetbrains.kotlin.analysis.api.KaSession] it was built in.
 */
internal class KaTypeSnapshot(
  renderedType: String,
  shortType: String = renderedType,
  val classId: ClassId?,
  val identity: TypeIdentity,
  val typeArguments: List<KaTypeArgumentSnapshot> = emptyList(),
  val isMarkedNullable: Boolean = false,
  val isError: Boolean = false,
  /** A missing class name lets source arrivals retry only requests that can resolve differently. */
  val unresolvedClassName: Name? = null,
  /** Recorded only for types a bound check can read. It's null everywhere else. */
  val hierarchy: KaTypeHierarchy? = null,
) {
  // Renders repeat heavily across entries, so intern them to keep the index's retained size flat.
  val renderedType: String = RENDER_INTERNER.intern(renderedType)
  val shortType: String = RENDER_INTERNER.intern(shortType)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is KaTypeSnapshot) return false
    return identity == other.identity
  }

  override fun hashCode(): Int = identity.hashCode()

  override fun toString(): String = renderedType

  companion object {
    private val RENDER_INTERNER = Interner.createWeakInterner<String>()
  }
}

/**
 * What a subtype check on a class type needs after its session ends. Derived bindings use it to
 * check type parameter bounds.
 *
 * [supertypes] holds every supertype of the type, with the type's arguments substituted. It's null
 * when the type was rebuilt outside a session, so its supertypes aren't known. [parameterVariances]
 * holds the declared variance of each of the class's type parameters, in order.
 *
 * Supertype snapshots have no hierarchy of their own. `Int` has the supertype `Comparable<Int>`,
 * which mentions `Int` again, so recording them at every depth would never end.
 */
internal class KaTypeHierarchy(
  val supertypes: List<KaTypeSnapshot>?,
  val parameterVariances: List<Variance>,
)

/** A positional type argument. Stars retain their slot when a type is rebuilt. */
internal sealed interface KaTypeArgumentSnapshot {
  val type: KaTypeSnapshot?

  data object Star : KaTypeArgumentSnapshot {
    override val type: KaTypeSnapshot? = null
  }

  data class Typed(
    override val type: KaTypeSnapshot,
    val variance: Variance,
  ) : KaTypeArgumentSnapshot
}
