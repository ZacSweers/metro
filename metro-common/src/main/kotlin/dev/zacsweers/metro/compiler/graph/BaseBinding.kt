// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.diagnostics.DiagnosticSpan
import dev.zacsweers.metro.compiler.diagnostics.Note

public interface BaseBinding<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, *>,
> {
  public val contextualTypeKey: ContextualTypeKey
  public val typeKey: TypeKey
    get() = contextualTypeKey.typeKey

  public val dependencies: List<ContextualTypeKey>

  /**
   * If true, indicates this binding is an alias for another binding. Mostly just for diagnostics.
   */
  public val isAlias: Boolean
    get() = false

  /**
   * If true, indicates this binding is purely informational and should not be stored in the graph
   * itself.
   */
  public val isTransient: Boolean
    get() = false

  public val diagnosticNotes: List<Note>
    get() = emptyList()

  /**
   * Some types may be implicitly deferrable such as lazy/provider types, instance-based bindings,
   * or bindings that don't participate in object construction such as object classes or members
   * injectors.
   */
  public val isImplicitlyDeferrable: Boolean
    get() = contextualTypeKey.isDeferrable

  public fun renderLocationDiagnostic(
    short: Boolean = false,
    shortLocation: Boolean = short || MetroOptions.SystemProperties.SHORTEN_LOCATIONS,
    underlineTypeKey: Boolean = true,
  ): LocationDiagnostic

  public fun renderDescriptionDiagnostic(
    short: Boolean = false,
    underlineTypeKey: Boolean = false,
  ): String
}

public data class LocationDiagnostic(
  val location: String,
  val description: String?,
  /** Resolved source span when available; enables source-frame rendering in rich console mode. */
  val span: DiagnosticSpan? = null,
)
