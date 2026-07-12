// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.diagnostics.DiagnosticSpan
import dev.zacsweers.metro.compiler.diagnostics.LocatedItem
import dev.zacsweers.metro.compiler.diagnostics.Note
import dev.zacsweers.metro.compiler.diagnostics.Text

internal interface BaseBinding<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, *>,
> {
  val contextualTypeKey: ContextualTypeKey
  val typeKey: TypeKey
    get() = contextualTypeKey.typeKey

  val dependencies: List<ContextualTypeKey>

  /**
   * If true, indicates this binding is an alias for another binding. Mostly just for diagnostics.
   */
  val isAlias: Boolean
    get() = false

  /**
   * If true, indicates this binding is purely informational and should not be stored in the graph
   * itself.
   */
  val isTransient: Boolean
    get() = false

  val diagnosticNotes: List<Note>
    get() = emptyList()

  /**
   * Some types may be implicitly deferrable such as lazy/provider types, instance-based bindings,
   * or bindings that don't participate in object construction such as object classes or members
   * injectors.
   */
  val isImplicitlyDeferrable: Boolean
    get() = contextualTypeKey.isDeferrable

  fun renderLocationDiagnostic(
    short: Boolean = false,
    shortLocation: Boolean = short || MetroOptions.SystemProperties.SHORTEN_LOCATIONS,
    underlineTypeKey: Boolean = true,
  ): LocationDiagnostic

  fun renderDescriptionDiagnostic(short: Boolean = false, underlineTypeKey: Boolean = false): String
}

internal data class LocationDiagnostic(
  val location: String,
  val description: String?,
  /** Resolved source span when available; enables source-frame rendering in rich console mode. */
  val span: DiagnosticSpan? = null,
  /** Additional context rendered beside [location], primarily for source-less declarations. */
  val locationContext: Text? = null,
  /** Supporting context discovered while resolving the source declaration. */
  val notes: List<Note> = emptyList(),
) {
  fun toLocatedItem(
    code: String? = description,
    preferSourceSnippet: Boolean = false,
    includeLeadingAnnotations: Boolean = true,
  ): LocatedItem =
    LocatedItem(
      location = location,
      code = code,
      description = locationContext,
      preferSourceSnippet = preferSourceSnippet,
      includeLeadingAnnotations = includeLeadingAnnotations,
      span = span,
    )

  companion object {
    const val NO_SOURCE_LOCATION: String = "No source location available"
  }
}
