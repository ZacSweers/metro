// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

public interface BaseTypeKey<Type, Qualifier, Subtype : BaseTypeKey<Type, Qualifier, Subtype>> :
  Comparable<Subtype> {
  public val type: Type
  public val qualifier: Qualifier?

  public fun copy(type: Type = this.type, qualifier: Qualifier? = this.qualifier): Subtype

  public fun render(
    short: Boolean,
    includeQualifier: Boolean = true,
    useRelativeClassNames: Boolean = false,
  ): String
}

/**
 * The ID of the multibinding this key's element belongs to: the qualifier + type render.
 *
 * For Set multibindings, this is the element type key. For Map multibindings, it composes with the
 * rendered map key type via [createMapBindingId].
 *
 * Examples:
 * - `okhttp3.Interceptor`
 * - `@NetworkInterceptor okhttp3.Interceptor`
 */
public fun BaseTypeKey<*, *, *>.computeMultibindingId(): String =
  render(short = false, includeQualifier = true)

/** The composite multibinding ID of a Map multibinding entry. */
public fun createMapBindingId(
  renderedMapKeyType: String,
  elementTypeKey: BaseTypeKey<*, *, *>,
): String {
  return "${renderedMapKeyType}_${elementTypeKey.computeMultibindingId()}"
}
