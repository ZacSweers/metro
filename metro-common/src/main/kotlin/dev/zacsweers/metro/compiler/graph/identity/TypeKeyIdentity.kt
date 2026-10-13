// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.identity

/**
 * What makes two binding keys the same key. Key equality, ordering, and generated IDs all use it.
 *
 * [encoded] is a string form of this identity. Two identities are equal exactly when their encoded
 * strings are equal, so ordering compares those strings.
 */
public class TypeKeyIdentity(
  public val type: TypeIdentity,
  public val qualifier: AnnotationIdentity?,
) : Comparable<TypeKeyIdentity> {
  public val encoded: String by
    lazy(LazyThreadSafetyMode.PUBLICATION) {
      buildString {
        qualifier?.let {
          it.encodeTo(this)
          append(' ')
        }
        type.encodeTo(this)
      }
    }

  private val cachedHashCode by
    lazy(LazyThreadSafetyMode.PUBLICATION) {
      31 * type.hashCode() + (qualifier?.hashCode() ?: 0)
    }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is TypeKeyIdentity) return false
    if (cachedHashCode != other.cachedHashCode) return false
    return type == other.type && qualifier == other.qualifier
  }

  override fun hashCode(): Int = cachedHashCode

  override fun compareTo(other: TypeKeyIdentity): Int {
    if (this === other) return 0
    return encoded.compareTo(other.encoded)
  }

  override fun toString(): String = encoded
}

public enum class Nullability {
  NotNull,
  Nullable,
  DefinitelyNotNull,
}

public enum class ProjectionVariance {
  Invariant,
  In,
  Out,
}

/** The identity of a type. Class and owner IDs use [org.jetbrains.kotlin.name.ClassId] strings. */
public sealed class TypeIdentity {
  internal abstract fun encodeTo(builder: StringBuilder)

  public val encoded: String by
    lazy(LazyThreadSafetyMode.PUBLICATION) { buildString { encodeTo(this) } }

  // Generated names can include this hash. Enum hash codes change between runs, so avoid them.
  final override fun hashCode(): Int = encoded.hashCode()

  override fun toString(): String = encoded

  public data class Class(
    val classId: String,
    val arguments: List<TypeArgumentIdentity> = emptyList(),
    val nullability: Nullability = Nullability.NotNull,
  ) : TypeIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      builder.appendName(classId)
      if (arguments.isNotEmpty()) {
        builder.append('<')
        arguments.forEachIndexed { index, argument ->
          if (index > 0) builder.append(',')
          argument.encodeTo(builder)
        }
        builder.append('>')
      }
      builder.appendNullability(nullability)
    }
  }

  /**
   * A type parameter, identified by its declaring class or function and its position. Overloads can
   * share an owner ID, so the name is included too.
   */
  public data class TypeParameter(
    val ownerId: String,
    val index: Int,
    val name: String,
    val nullability: Nullability = Nullability.NotNull,
  ) : TypeIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      builder.append('#')
      builder.appendName(ownerId)
      builder.append(':')
      builder.append(index)
      builder.append(':')
      builder.appendName(name)
      builder.appendNullability(nullability)
    }
  }

  public data class Flexible(val lower: TypeIdentity, val upper: TypeIdentity) : TypeIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      builder.append('(')
      lower.encodeTo(builder)
      builder.append("..")
      upper.encodeTo(builder)
      builder.append(')')
    }
  }

  /** An intersection type. [types] are sorted so declaration order doesn't matter. */
  public data class Intersection(val types: List<TypeIdentity>) : TypeIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      builder.append('{')
      types.forEachIndexed { index, type ->
        if (index > 0) builder.append('&')
        type.encodeTo(builder)
      }
      builder.append('}')
    }
  }

  /** A type the compiler or IDE couldn't resolve. */
  public data class Unresolved(val description: String) : TypeIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      builder.append("!error")
      builder.appendQuoted(description)
    }
  }
}

public sealed class TypeArgumentIdentity {
  internal abstract fun encodeTo(builder: StringBuilder)

  final override fun hashCode(): Int = buildString { encodeTo(this) }.hashCode()

  public data object Star : TypeArgumentIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      builder.append('*')
    }
  }

  public data class Projection(val variance: ProjectionVariance, val type: TypeIdentity) :
    TypeArgumentIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      when (variance) {
        ProjectionVariance.Invariant -> {}
        ProjectionVariance.In -> builder.append("in ")
        ProjectionVariance.Out -> builder.append("out ")
      }
      type.encodeTo(builder)
    }
  }
}

/** An annotation with its arguments in constructor-parameter order and defaults filled in. */
public data class AnnotationIdentity(
  val classId: String,
  val arguments: List<AnnotationValueIdentity> = emptyList(),
) {
  internal fun encodeTo(builder: StringBuilder) {
    builder.append('@')
    builder.appendName(classId)
    if (arguments.isNotEmpty()) {
      builder.append('(')
      arguments.forEachIndexed { index, argument ->
        if (index > 0) builder.append(',')
        argument.encodeTo(builder)
      }
      builder.append(')')
    }
  }

  public val encoded: String by
    lazy(LazyThreadSafetyMode.PUBLICATION) { buildString { encodeTo(this) } }

  override fun hashCode(): Int = encoded.hashCode()

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is AnnotationIdentity) return false
    return classId == other.classId && arguments == other.arguments
  }

  override fun toString(): String = encoded
}

public enum class ConstantKind {
  Null,
  Boolean,
  Char,
  Byte,
  Short,
  Int,
  Long,
  Float,
  Double,
  String,
  UByte,
  UShort,
  UInt,
  ULong,
}

public sealed class AnnotationValueIdentity {
  internal abstract fun encodeTo(builder: StringBuilder)

  final override fun hashCode(): Int = buildString { encodeTo(this) }.hashCode()

  /** No explicit value and no available default. */
  public data object Absent : AnnotationValueIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      builder.append('_')
    }
  }

  public data class Constant(val kind: ConstantKind, val value: String) :
    AnnotationValueIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      when (kind) {
        ConstantKind.String -> builder.appendQuoted(value)
        ConstantKind.Null -> builder.append("null")
        else -> {
          builder.append(kind.name)
          builder.appendQuoted(value)
        }
      }
    }
  }

  public data class EnumEntry(val classId: String, val name: String) : AnnotationValueIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      builder.append("enum(")
      builder.appendName(classId)
      builder.append(',')
      builder.appendName(name)
      builder.append(')')
    }
  }

  public data class ClassLiteral(val type: TypeIdentity) : AnnotationValueIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      builder.append("class(")
      type.encodeTo(builder)
      builder.append(')')
    }
  }

  public data class Array(val elements: List<AnnotationValueIdentity>) : AnnotationValueIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      builder.append('[')
      elements.forEachIndexed { index, element ->
        if (index > 0) builder.append(',')
        element.encodeTo(builder)
      }
      builder.append(']')
    }
  }

  public data class Nested(val annotation: AnnotationIdentity) : AnnotationValueIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      annotation.encodeTo(builder)
    }
  }

  /** A value the compiler or IDE couldn't resolve. */
  public data class Unresolved(val description: String) : AnnotationValueIdentity() {
    override fun encodeTo(builder: StringBuilder) {
      builder.append("!error")
      builder.appendQuoted(description)
    }
  }
}

/** Escapes characters the encoding uses as separators, so names can't run into each other. */
private fun StringBuilder.appendName(name: String) {
  for (char in name) {
    val isPlain = char.isLetterOrDigit() || char == '_' || char == '$' || char == '/' || char == '.'
    if (isPlain) {
      append(char)
    } else {
      appendEscaped(char)
    }
  }
}

private fun StringBuilder.appendQuoted(value: String) {
  append('"')
  for (char in value) {
    if (char == '"' || char == '\\') {
      append('\\')
    }
    append(char)
  }
  append('"')
}

private fun StringBuilder.appendEscaped(char: Char) {
  append("\\u")
  append(char.code.toString(16).padStart(4, '0'))
}

private fun StringBuilder.appendNullability(nullability: Nullability) {
  when (nullability) {
    Nullability.NotNull -> {}
    Nullability.Nullable -> append('?')
    Nullability.DefinitelyNotNull -> append("!!")
  }
}
