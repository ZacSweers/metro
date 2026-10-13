// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import dev.zacsweers.metro.compiler.graph.identity.AnnotationIdentity
import dev.zacsweers.metro.compiler.graph.identity.AnnotationReader
import dev.zacsweers.metro.compiler.graph.identity.AnnotationValueNode
import dev.zacsweers.metro.compiler.graph.identity.ConstantKind
import dev.zacsweers.metro.compiler.graph.identity.TypeIdentity
import dev.zacsweers.metro.compiler.graph.identity.TypeIdentityReader
import dev.zacsweers.metro.compiler.graph.identity.annotationIdentity
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/** A structured, session-free snapshot of a resolved annotation argument value. */
internal sealed interface KaAnnotationValueSnapshot {
  data class Literal(val value: Any?) : KaAnnotationValueSnapshot

  data class EnumEntry(val callableId: CallableId?) : KaAnnotationValueSnapshot

  data class KClassRef(val classId: ClassId?) : KaAnnotationValueSnapshot

  data class Array(val values: List<KaAnnotationValueSnapshot>) : KaAnnotationValueSnapshot

  data class Nested(val annotation: KaAnnotationSnapshot) : KaAnnotationValueSnapshot

  data object Unsupported : KaAnnotationValueSnapshot

  /** A constructor parameter with no explicit value and no available default. */
  data object Absent : KaAnnotationValueSnapshot
}

/**
 * An annotation participating in key/scope identity (a qualifier like `@Named("cdn")` or a scope
 * like `@SingleIn(AppScope::class)`). The Analysis API analog of the compiler's
 * `MetroFirAnnotation`/`IrAnnotation`, with the same [identity] semantics. It is built from the
 * structured resolved argument values rather than source text, so spelling differences (named vs
 * positional args, import styles) don't break identity.
 *
 * [arguments] follow constructor-parameter order when [isPositional] is true. Otherwise the
 * constructor wasn't available and they're sorted by name.
 */
internal class KaAnnotationSnapshot(
  val classId: ClassId,
  val arguments: List<Pair<Name, KaAnnotationValueSnapshot>>,
  val isPositional: Boolean = true,
) {
  val identity: AnnotationIdentity by
    lazy(LazyThreadSafetyMode.PUBLICATION) {
      KaAnnotationReader.annotationIdentity(this)
    }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is KaAnnotationSnapshot) return false
    return identity == other.identity
  }

  override fun hashCode(): Int = identity.hashCode()

  override fun toString(): String = render(short = false)

  fun copy(
    classId: ClassId = this.classId,
    arguments: List<Pair<Name, KaAnnotationValueSnapshot>> = this.arguments,
  ): KaAnnotationSnapshot = KaAnnotationSnapshot(classId, arguments, isPositional)

  fun render(short: Boolean, useRelativeClassNames: Boolean = false): String = buildString {
    append('@')
    append(
      when {
        short -> classId.shortClassName.asString()
        useRelativeClassNames -> classId.relativeClassName.asString()
        else -> classId.asFqNameString()
      },
    )
    val presentArguments = arguments.filter { it.second != KaAnnotationValueSnapshot.Absent }
    if (presentArguments.isNotEmpty()) {
      presentArguments.joinTo(this, separator = ", ", prefix = "(", postfix = ")") { (name, value)
        ->
        "${name.asString()} = ${renderValue(value, short, useRelativeClassNames)}"
      }
    }
  }

  private fun renderValue(
    value: KaAnnotationValueSnapshot,
    short: Boolean,
    useRelativeClassNames: Boolean,
  ): String {
    return when (value) {
      is KaAnnotationValueSnapshot.Literal ->
        when (val literal = value.value) {
          is String -> "\"$literal\""
          is Char -> "'$literal'"
          else -> literal.toString()
        }
      is KaAnnotationValueSnapshot.EnumEntry ->
        value.callableId?.let {
          if (short) it.callableName.asString() else it.asSingleFqName().asString()
        } ?: "<error>"
      is KaAnnotationValueSnapshot.KClassRef ->
        value.classId?.let {
          val name =
            when {
              short -> it.shortClassName.asString()
              useRelativeClassNames -> it.relativeClassName.asString()
              else -> it.asFqNameString()
            }
          "$name::class"
        } ?: "<error>"
      is KaAnnotationValueSnapshot.Array ->
        value.values.joinToString(separator = ", ", prefix = "[", postfix = "]") {
          renderValue(it, short, useRelativeClassNames)
        }
      is KaAnnotationValueSnapshot.Nested -> value.annotation.render(short, useRelativeClassNames)
      is KaAnnotationValueSnapshot.Unsupported -> "..."
      is KaAnnotationValueSnapshot.Absent -> "_"
    }
  }
}

/**
 * Reads annotation snapshots. Class literals only have a class ID here, so they're read as
 * [TypeIdentity] values.
 */
private object KaAnnotationReader :
  AnnotationReader<KaAnnotationSnapshot, KaAnnotationValueSnapshot, TypeIdentity> {
  override val typeReader = TypeIdentityReader
  override val isJvm = true

  override fun classId(annotation: KaAnnotationSnapshot): String = annotation.classId.asString()

  override fun arguments(annotation: KaAnnotationSnapshot): List<KaAnnotationValueSnapshot?> {
    if (annotation.isPositional) {
      return annotation.arguments.map { (_, value) ->
        value.takeUnless { it == KaAnnotationValueSnapshot.Absent }
      }
    }
    // Without the constructor, positions are unknown, so each value is paired with its name.
    return annotation.arguments.map { (name, value) ->
      KaAnnotationValueSnapshot.Array(
        listOf(KaAnnotationValueSnapshot.Literal(name.asString()), value),
      )
    }
  }

  override fun value(
    value: KaAnnotationValueSnapshot,
  ): AnnotationValueNode<KaAnnotationSnapshot, KaAnnotationValueSnapshot, TypeIdentity> =
    when (value) {
      is KaAnnotationValueSnapshot.Literal -> literalNode(value.value)
      is KaAnnotationValueSnapshot.EnumEntry -> {
        val callableId = value.callableId
        val enumClassId = callableId?.classId
        if (enumClassId == null) {
          AnnotationValueNode.Unresolved("<error>")
        } else {
          AnnotationValueNode.EnumEntry(enumClassId.asString(), callableId.callableName.asString())
        }
      }
      is KaAnnotationValueSnapshot.KClassRef -> {
        val classId = value.classId
        if (classId == null) {
          AnnotationValueNode.Unresolved("<error>")
        } else {
          AnnotationValueNode.ClassLiteral(TypeIdentity.Class(classId.asString()))
        }
      }
      is KaAnnotationValueSnapshot.Array -> AnnotationValueNode.Array(value.values)
      is KaAnnotationValueSnapshot.Nested -> AnnotationValueNode.Nested(value.annotation)
      is KaAnnotationValueSnapshot.Unsupported -> AnnotationValueNode.Unresolved("...")
      // Absent arguments are filtered out before values are read.
      is KaAnnotationValueSnapshot.Absent -> AnnotationValueNode.Unresolved("_")
    }

  private fun literalNode(literal: Any?): AnnotationValueNode<Nothing, Nothing, Nothing> {
    val kind =
      when (literal) {
        null -> ConstantKind.Null
        is Boolean -> ConstantKind.Boolean
        is Char -> ConstantKind.Char
        is Byte -> ConstantKind.Byte
        is Short -> ConstantKind.Short
        is Int -> ConstantKind.Int
        is Long -> ConstantKind.Long
        is Float -> ConstantKind.Float
        is Double -> ConstantKind.Double
        is String -> ConstantKind.String
        is UByte -> ConstantKind.UByte
        is UShort -> ConstantKind.UShort
        is UInt -> ConstantKind.UInt
        is ULong -> ConstantKind.ULong
        else -> return AnnotationValueNode.Unresolved(literal.toString())
      }
    return AnnotationValueNode.Constant(kind, literal.toString())
  }
}
