// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiAnnotationMethod
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.GlobalSearchScope
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaAnnotationValueSnapshot
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClassLiteralExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.toUElement

internal fun KaAnnotated.hasAnyAnnotation(classIds: Set<ClassId>): Boolean {
  return annotations.any { it.classId in classIds }
}

/** Converts a resolved [KaAnnotation] to its session-free structured snapshot. */
@OptIn(KaExperimentalApi::class)
internal fun KaSession.toKaAnnotationSnapshot(annotation: KaAnnotation): KaAnnotationSnapshot? {
  val classId = annotation.classId ?: return null
  val explicitArguments = annotation.arguments.associateBy { it.name }
  val constructorParameters = annotation.constructorSymbol?.valueParameters.orEmpty()
  if (constructorParameters.isEmpty()) {
    return KaAnnotationSnapshot(
      classId,
      explicitArguments.toSortedMap(compareBy { it.asString() }).map { (name, value) ->
        name to toValueSnapshot(value.expression)
      },
    )
  }

  // Default arguments share fallback lookups for this snapshot. Explicit arguments skip them.
  var uAnnotation: UAnnotation? = null
  var didResolveUAnnotation = false
  var resolvedClass: PsiClass? = null
  var didResolveClass = false
  val snapshots = mutableListOf<Pair<Name, KaAnnotationValueSnapshot>>()
  for (parameter in constructorParameters) {
    val explicit = explicitArguments[parameter.name]
    if (explicit != null) {
      snapshots += parameter.name to toValueSnapshot(explicit.expression)
      continue
    }

    if (!parameter.hasDefaultValue) continue
    val kotlinDefault = (parameter.psi as? KtParameter)?.defaultValue
    val kotlinValue = kotlinDefault?.evaluate()?.value
    if (kotlinValue != null) {
      snapshots += parameter.name to KaAnnotationValueSnapshot.Literal(kotlinValue)
      continue
    }

    val kotlinClassLiteral = kotlinDefault as? KtClassLiteralExpression
    val classLiteralType = kotlinClassLiteral?.receiverExpression?.expressionType
    val classLiteralId = (classLiteralType?.fullyExpandedType as? KaClassType)?.classId
    if (classLiteralId != null) {
      snapshots += parameter.name to KaAnnotationValueSnapshot.KClassRef(classLiteralId)
      continue
    }

    val kotlinSnapshot = kotlinDefault?.toUElement(UExpression::class.java)?.toValueSnapshot()
    if (kotlinSnapshot != null) {
      snapshots += parameter.name to kotlinSnapshot
      continue
    }

    if (!didResolveUAnnotation) {
      uAnnotation = annotation.psi?.toUElement(UAnnotation::class.java)
      didResolveUAnnotation = true
    }
    val uastDefault = uAnnotation?.findAttributeValue(parameter.name.asString())
    val uastSnapshot = uastDefault?.toValueSnapshot()
    if (uastSnapshot != null) {
      snapshots += parameter.name to uastSnapshot
      continue
    }

    if (!didResolveClass) {
      val project = annotation.psi?.project ?: annotation.constructorSymbol?.psi?.project
      resolvedClass = project?.let {
        JavaPsiFacade.getInstance(it)
          .findClass(classId.asFqNameString(), GlobalSearchScope.allScope(it))
      }
      didResolveClass = true
    }
    val javaDefault =
      resolvedClass
        ?.findMethodsByName(parameter.name.asString(), false)
        ?.filterIsInstance<PsiAnnotationMethod>()
        ?.firstOrNull()
        ?.defaultValue
    javaDefault?.toValueSnapshot()?.let { snapshots += parameter.name to it }
  }
  return KaAnnotationSnapshot(classId, snapshots)
}

internal fun KaSession.toValueSnapshot(
  annotationValue: KaAnnotationValue
): KaAnnotationValueSnapshot {
  return when (annotationValue) {
    is KaAnnotationValue.ConstantValue ->
      KaAnnotationValueSnapshot.Literal(annotationValue.value.value)
    is KaAnnotationValue.EnumEntryValue ->
      KaAnnotationValueSnapshot.EnumEntry(annotationValue.callableId)
    // classId may be unpopulated for binary-deserialized values; the type still carries it
    is KaAnnotationValue.ClassLiteralValue ->
      KaAnnotationValueSnapshot.KClassRef(
        annotationValue.classId ?: (annotationValue.type as? KaClassType)?.classId
      )
    is KaAnnotationValue.ArrayValue ->
      KaAnnotationValueSnapshot.Array(annotationValue.values.map { toValueSnapshot(it) })
    is KaAnnotationValue.NestedAnnotationValue ->
      toKaAnnotationSnapshot(annotationValue.annotation)?.let {
        KaAnnotationValueSnapshot.Nested(it)
      } ?: KaAnnotationValueSnapshot.Unsupported
    else -> KaAnnotationValueSnapshot.Unsupported
  }
}

private fun UExpression.toValueSnapshot(): KaAnnotationValueSnapshot? {
  if (this is UClassLiteralExpression) {
    val classType = type as? PsiClassType ?: return null
    val qualifiedName = classType.resolve()?.qualifiedName ?: return null
    return KaAnnotationValueSnapshot.KClassRef(classIdForJavaName(qualifiedName))
  }
  if (this is UReferenceExpression) {
    val target = resolve()
    if (target is PsiEnumConstant) {
      val owner = target.containingClass?.qualifiedName ?: return null
      return KaAnnotationValueSnapshot.EnumEntry(
        CallableId(ClassId.topLevel(FqName(owner)), Name.identifier(target.name))
      )
    }
  }
  if (this is UCallExpression && returnType is com.intellij.psi.PsiArrayType) {
    return KaAnnotationValueSnapshot.Array(valueArguments.mapNotNull { it.toValueSnapshot() })
  }
  val value = evaluate() ?: return null
  return KaAnnotationValueSnapshot.Literal(value)
}

private fun PsiAnnotationMemberValue.toValueSnapshot(): KaAnnotationValueSnapshot? {
  return when (this) {
    is PsiArrayInitializerMemberValue ->
      KaAnnotationValueSnapshot.Array(initializers.mapNotNull { it.toValueSnapshot() })
    is PsiClassObjectAccessExpression -> {
      val classType = operand.type as? PsiClassType ?: return null
      val qualifiedName = classType.resolve()?.qualifiedName ?: return null
      KaAnnotationValueSnapshot.KClassRef(classIdForJavaName(qualifiedName))
    }
    is PsiReferenceExpression -> {
      val target = resolve()
      if (target is PsiEnumConstant) {
        val owner = target.containingClass?.qualifiedName ?: return null
        KaAnnotationValueSnapshot.EnumEntry(
          CallableId(ClassId.topLevel(FqName(owner)), Name.identifier(target.name))
        )
      } else {
        val value =
          JavaPsiFacade.getInstance(project)
            .constantEvaluationHelper
            .computeConstantExpression(this)
        value?.let(KaAnnotationValueSnapshot::Literal)
      }
    }
    is PsiAnnotation -> {
      val qualifiedName = qualifiedName ?: return null
      val values =
        parameterList.attributes.mapNotNull { attribute ->
          val name = Name.identifier(attribute.name ?: "value")
          attribute.value?.toValueSnapshot()?.let { name to it }
        }
      KaAnnotationValueSnapshot.Nested(
        KaAnnotationSnapshot(ClassId.topLevel(FqName(qualifiedName)), values)
      )
    }
    else -> {
      val value =
        JavaPsiFacade.getInstance(project).constantEvaluationHelper.computeConstantExpression(this)
      value?.let(KaAnnotationValueSnapshot::Literal)
    }
  }
}

private fun classIdForJavaName(qualifiedName: String): ClassId {
  val fqName = FqName(qualifiedName)
  return JavaToKotlinClassMap.mapJavaToKotlin(fqName) ?: ClassId.topLevel(fqName)
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
      toKaAnnotationSnapshot(annotation)
    } else {
      null
    }
  }
}

/**
 * Finds the first qualifier annotation (an annotation meta-annotated with `@Qualifier`). A
 * property's own qualifier takes precedence over a getter use-site qualifier.
 */
internal fun KaSession.qualifierAnnotation(
  annotated: KaAnnotated,
  options: MetroOptions,
): KaAnnotationSnapshot? {
  val qualifier = findMetaAnnotated(annotated, options.qualifierAnnotations)
  if (qualifier != null) return qualifier
  val getter = (annotated as? KaPropertySymbol)?.getter ?: return null
  return findMetaAnnotated(getter, options.qualifierAnnotations)
}

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
