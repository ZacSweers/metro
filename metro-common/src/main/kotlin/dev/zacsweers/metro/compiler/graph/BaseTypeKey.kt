// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.graph.identity.TypeIdentity
import dev.zacsweers.metro.compiler.graph.identity.TypeKeyIdentity
import java.util.Objects
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * A binding key made of a type and an optional qualifier.
 *
 * Equality and ordering come from [identity]. Text from [render] is only for display. [compareTo]
 * must return 0 exactly when two keys are equal. Use [compareTypeKeys] to get that.
 */
public interface BaseTypeKey<Type, Qualifier, Subtype : BaseTypeKey<Type, Qualifier, Subtype>> :
  Comparable<Subtype> {
  public val type: Type
  public val qualifier: Qualifier?
  public val identity: TypeKeyIdentity

  public fun copy(type: Type = this.type, qualifier: Qualifier? = this.qualifier): Subtype

  public fun render(
    short: Boolean,
    includeQualifier: Boolean = true,
    useRelativeClassNames: Boolean = false,
  ): String
}

/** Orders keys by [BaseTypeKey.identity]. Equal keys compare as 0. */
public fun compareTypeKeys(left: BaseTypeKey<*, *, *>, right: BaseTypeKey<*, *, *>): Int {
  if (left == right) return 0
  return left.identity.compareTo(right.identity)
}

private val ANY_IDENTITY = TypeIdentity.Class("kotlin/Any")

/** Whether this key is a plain, unqualified `kotlin.Any`. */
public val BaseTypeKey<*, *, *>.isUnqualifiedAny: Boolean
  get() = identity.qualifier == null && identity.type == ANY_IDENTITY

/**
 * The ID of the multibinding this key's element belongs to.
 *
 * For Set multibindings, this is the element type key. For Map multibindings, it composes with the
 * map key type via [createMapBindingId].
 */
public fun BaseTypeKey<*, *, *>.computeMultibindingId(): String = identity.encoded

/** The composite multibinding ID of a Map multibinding entry. */
public fun createMapBindingId(
  mapKeyType: TypeIdentity,
  elementTypeKey: BaseTypeKey<*, *, *>,
): String {
  // Encoded names escape `|`, so the separator can't be confused with either part.
  return "${mapKeyType.encoded}|${elementTypeKey.computeMultibindingId()}"
}

/**
 * Identifies a contributor across graphs. Include all receivers in [parameterTypes] in declaration
 * order. For a property [callableName] names its getter. A missing getter has no [returnType].
 */
public fun multibindingElementId(
  owner: FqName,
  name: Name,
  isSuspend: Boolean,
  parameterTypes: List<TypeIdentity>,
  returnType: TypeIdentity?,
  callableName: Name = name,
): String {
  // Element IDs sort lexically. Keep the old hash prefix to preserve collection iteration order.
  val rawNames = parameterTypes.map { type ->
    (type as? TypeIdentity.Class)?.let { ClassId.fromString(it.classId, false).asSingleFqName() }
  }
  val order = Objects.hash(owner, name, isSuspend, rawNames)
  val fqName = owner.child(callableName)
  val signature =
    if (returnType == null) {
      "${fqName.asString()}: null"
    } else {
      callableDeclarationId(fqName, parameterTypes, returnType)
    }

  return "$order#$signature"
}

/** Encodes the full signature even for private declarations that lack an IR symbol signature. */
public fun callableDeclarationId(
  fqName: FqName,
  parameterTypes: List<TypeIdentity>,
  returnType: TypeIdentity,
): String = buildString {
  append(fqName.asString())
  parameterTypes.joinTo(this, separator = ",", prefix = "(", postfix = "): ") { it.encoded }
  append(returnType.encoded)
}
