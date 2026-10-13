// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.identity

private const val JAVA_VOID_CLASS_ID = "java/lang/Void"
private const val NOTHING_CLASS_ID = "kotlin/Nothing"
private const val ARRAY_CLASS_ID = "kotlin/Array"
private const val ANY_CLASS_ID = "kotlin/Any"

/** Reads one level of a compiler or IDE type. [typeIdentity] walks the rest. */
public interface TypeReader<T> {
  public fun read(type: T): TypeNode<T>
}

/** Reads a [TypeIdentity] that's already built, like a class literal known only by its class ID. */
public object TypeIdentityReader : TypeReader<TypeIdentity> {
  override fun read(type: TypeIdentity): TypeNode<TypeIdentity> =
    when (type) {
      is TypeIdentity.Class ->
        TypeNode.Class(
          type.classId,
          type.arguments.map { argument ->
            when (argument) {
              is TypeArgumentIdentity.Star -> TypeArgumentNode.Star
              is TypeArgumentIdentity.Projection ->
                TypeArgumentNode.Projection(argument.variance, argument.type)
            }
          },
          type.nullability,
        )
      is TypeIdentity.TypeParameter ->
        TypeNode.TypeParameter(type.ownerId, type.index, type.name, type.nullability)
      is TypeIdentity.Flexible -> TypeNode.Flexible(type.lower, type.upper)
      is TypeIdentity.Intersection -> TypeNode.Intersection(type.types)
      is TypeIdentity.Unresolved -> TypeNode.Unresolved(type.description)
    }
}

public sealed interface TypeNode<out T> {
  public class Class<T>(
    public val classId: String,
    public val arguments: List<TypeArgumentNode<T>>,
    public val nullability: Nullability,
  ) : TypeNode<T>

  public class TypeParameter(
    public val ownerId: String,
    public val index: Int,
    public val name: String,
    public val nullability: Nullability,
  ) : TypeNode<Nothing>

  public class Flexible<T>(public val lower: T, public val upper: T) : TypeNode<T>

  public class Intersection<T>(public val types: List<T>) : TypeNode<T>

  public class Unresolved(public val description: String) : TypeNode<Nothing>
}

public sealed interface TypeArgumentNode<out T> {
  public data object Star : TypeArgumentNode<Nothing>

  public class Projection<T>(public val variance: ProjectionVariance, public val type: T) :
    TypeArgumentNode<T>
}

public fun <T> TypeReader<T>.typeIdentity(type: T): TypeIdentity =
  when (val node = read(type)) {
    is TypeNode.Class ->
      TypeIdentity.Class(
        classId = node.classId,
        arguments = node.arguments.map { argumentIdentity(it) },
        nullability = node.nullability,
      )
    is TypeNode.TypeParameter ->
      TypeIdentity.TypeParameter(node.ownerId, node.index, node.name, node.nullability)
    is TypeNode.Flexible ->
      TypeIdentity.Flexible(typeIdentity(node.lower), typeIdentity(node.upper))
    is TypeNode.Intersection ->
      TypeIdentity.Intersection(node.types.map { typeIdentity(it) }.sortedBy { it.encoded })
    is TypeNode.Unresolved -> TypeIdentity.Unresolved(node.description)
  }

private fun <T> TypeReader<T>.argumentIdentity(
  argument: TypeArgumentNode<T>,
): TypeArgumentIdentity =
  when (argument) {
    is TypeArgumentNode.Star -> TypeArgumentIdentity.Star
    is TypeArgumentNode.Projection ->
      TypeArgumentIdentity.Projection(argument.variance, typeIdentity(argument.type))
  }

/**
 * Reads one compiler or IDE annotation and its argument values. [annotationIdentity] walks the
 * rest.
 */
public interface AnnotationReader<A, V, T> {
  public val typeReader: TypeReader<T>

  /** Class literals compare differently on the JVM. See [classLiteralIdentity]. */
  public val isJvm: Boolean

  public fun classId(annotation: A): String

  /**
   * Returns one value per constructor parameter, in declaration order. Each value is the explicit
   * argument or, when it's omitted, the parameter's default. It's null when neither is available.
   */
  public fun arguments(annotation: A): List<V?>

  public fun value(value: V): AnnotationValueNode<A, V, T>
}

public sealed interface AnnotationValueNode<out A, out V, out T> {
  public class Constant(public val kind: ConstantKind, public val value: String) :
    AnnotationValueNode<Nothing, Nothing, Nothing>

  public class EnumEntry(public val classId: String, public val name: String) :
    AnnotationValueNode<Nothing, Nothing, Nothing>

  public class ClassLiteral<T>(public val type: T) : AnnotationValueNode<Nothing, Nothing, T>

  public class Array<V>(public val elements: List<V>) : AnnotationValueNode<Nothing, V, Nothing>

  public class Nested<A>(public val annotation: A) : AnnotationValueNode<A, Nothing, Nothing>

  public class Unresolved(public val description: String) :
    AnnotationValueNode<Nothing, Nothing, Nothing>
}

public fun <A, V, T> AnnotationReader<A, V, T>.annotationIdentity(
  annotation: A,
): AnnotationIdentity =
  AnnotationIdentity(
    classId = classId(annotation),
    arguments =
      arguments(annotation).map { value ->
        if (value == null) {
          AnnotationValueIdentity.Absent
        } else {
          valueIdentity(value)
        }
      },
  )

private fun <A, V, T> AnnotationReader<A, V, T>.valueIdentity(
  value: V,
): AnnotationValueIdentity =
  when (val node = value(value)) {
    is AnnotationValueNode.Constant -> AnnotationValueIdentity.Constant(node.kind, node.value)
    is AnnotationValueNode.EnumEntry -> AnnotationValueIdentity.EnumEntry(node.classId, node.name)
    is AnnotationValueNode.ClassLiteral ->
      AnnotationValueIdentity.ClassLiteral(
        classLiteralIdentity(typeReader.typeIdentity(node.type), isJvm),
      )
    is AnnotationValueNode.Array ->
      AnnotationValueIdentity.Array(node.elements.map { valueIdentity(it) })
    is AnnotationValueNode.Nested ->
      AnnotationValueIdentity.Nested(annotationIdentity(node.annotation))
    is AnnotationValueNode.Unresolved -> AnnotationValueIdentity.Unresolved(node.description)
  }

/**
 * Class literals identify only their class. On the JVM, `Nothing::class` can appear as
 * `java.lang.Void` in annotation defaults, and array class literals keep their component types at
 * runtime.
 */
internal fun classLiteralIdentity(type: TypeIdentity, isJvm: Boolean): TypeIdentity {
  if (type !is TypeIdentity.Class) {
    return type
  }
  if (isJvm && type.classId == JAVA_VOID_CLASS_ID) {
    return TypeIdentity.Class(NOTHING_CLASS_ID)
  }
  if (isJvm && type.classId == ARRAY_CLASS_ID) {
    val componentType =
      when (val argument = type.arguments.singleOrNull()) {
        is TypeArgumentIdentity.Projection -> classLiteralIdentity(argument.type, isJvm)
        else -> TypeIdentity.Class(ANY_CLASS_ID)
      }
    val component = TypeArgumentIdentity.Projection(ProjectionVariance.Invariant, componentType)
    return TypeIdentity.Class(ARRAY_CLASS_ID, listOf(component))
  }
  return TypeIdentity.Class(type.classId)
}
