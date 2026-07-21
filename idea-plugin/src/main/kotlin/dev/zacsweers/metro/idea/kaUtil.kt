// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaAnnotationValueSnapshot
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal fun KaAnnotated.hasAnyAnnotation(classIds: Set<ClassId>): Boolean {
  return annotations.any { it.classId in classIds }
}

/** Converts a resolved [KaAnnotation] to its session-free structured snapshot. */
internal fun KaAnnotation.toKaAnnotationSnapshot(): KaAnnotationSnapshot? {
  val classId = classId ?: return null
  return KaAnnotationSnapshot(classId, arguments.map { it.name to it.expression.toValueSnapshot() })
}

internal fun KaAnnotationValue.toValueSnapshot(): KaAnnotationValueSnapshot {
  return when (this) {
    is KaAnnotationValue.ConstantValue -> KaAnnotationValueSnapshot.Literal(value.value)
    is KaAnnotationValue.EnumEntryValue -> KaAnnotationValueSnapshot.EnumEntry(callableId)
    // classId may be unpopulated for binary-deserialized values; the type still carries it
    is KaAnnotationValue.ClassLiteralValue ->
      KaAnnotationValueSnapshot.KClassRef(classId ?: (type as? KaClassType)?.classId)
    is KaAnnotationValue.ArrayValue ->
      KaAnnotationValueSnapshot.Array(values.map { it.toValueSnapshot() })
    is KaAnnotationValue.NestedAnnotationValue ->
      annotation.toKaAnnotationSnapshot()?.let { KaAnnotationValueSnapshot.Nested(it) }
        ?: KaAnnotationValueSnapshot.Unsupported
    else -> KaAnnotationValueSnapshot.Unsupported
  }
}

/** Finds the first annotation whose class is meta-annotated with any of [metaAnnotations]. */
internal fun KaSession.findMetaAnnotated(
  annotated: KaAnnotated,
  metaAnnotations: Set<ClassId>,
): KaAnnotationSnapshot? = findAllMetaAnnotated(annotated, metaAnnotations).firstOrNull()

/** Finds all annotations whose classes are meta-annotated with any of [metaAnnotations]. */
internal fun KaSession.findAllMetaAnnotated(
  annotated: KaAnnotated,
  metaAnnotations: Set<ClassId>,
): List<KaAnnotationSnapshot> {
  return annotated.annotations.mapNotNull { annotation ->
    val classId = annotation.classId ?: return@mapNotNull null
    val annotationClass = findClass(classId) ?: return@mapNotNull null
    if (annotationClass.annotations.any { it.classId in metaAnnotations }) {
      annotation.toKaAnnotationSnapshot()
    } else {
      null
    }
  }
}

/** Finds the first qualifier annotation (an annotation meta-annotated with `@Qualifier`). */
internal fun KaSession.qualifierAnnotation(
  annotated: KaAnnotated,
  options: MetroOptions,
): KaAnnotationSnapshot? = findMetaAnnotated(annotated, options.qualifierAnnotations)

/** Finds the first scope annotation (an annotation meta-annotated with `@Scope`). */
internal fun KaSession.scopeAnnotation(
  annotated: KaAnnotated,
  options: MetroOptions,
): KaAnnotationSnapshot? = findMetaAnnotated(annotated, options.scopeAnnotations)

/** Finds all scope annotations (annotations meta-annotated with `@Scope`). */
internal fun KaSession.scopeAnnotations(
  annotated: KaAnnotated,
  options: MetroOptions,
): List<KaAnnotationSnapshot> = findAllMetaAnnotated(annotated, options.scopeAnnotations)

/**
 * The `@SingleIn(scope)` implicitly conveyed by a graph annotation's aggregation [scopeClassId].
 */
internal fun implicitSingleInAnnotation(scopeClassId: ClassId): KaAnnotationSnapshot {
  return KaAnnotationSnapshot(
    MetroClassIds.singleIn,
    listOf(Name.identifier("scope") to KaAnnotationValueSnapshot.KClassRef(scopeClassId)),
  )
}

/** Extracts `scope`/`additionalScopes` class-literal arguments. */
internal fun annotationScopeKeys(annotation: KaAnnotation): Set<ClassId> {
  val result = mutableSetOf<ClassId>()
  for (argument in annotation.arguments) {
    when (argument.name.asString()) {
      "scope" -> classLiteralClassId(argument.expression)?.let(result::add)
      "additionalScopes",
      "scopes" -> {
        val values = (argument.expression as? KaAnnotationValue.ArrayValue)?.values.orEmpty()
        values.forEach { value -> classLiteralClassId(value)?.let(result::add) }
      }
    }
  }
  return result
}

internal fun classLiteralClassId(value: KaAnnotationValue): ClassId? {
  val classLiteral = value as? KaAnnotationValue.ClassLiteralValue ?: return null
  // classId may be unpopulated for binary-deserialized values; the type still carries it
  return classLiteral.classId ?: (classLiteral.type as? KaClassType)?.classId
}
