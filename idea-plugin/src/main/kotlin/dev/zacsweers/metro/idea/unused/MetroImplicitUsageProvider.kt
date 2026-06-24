// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.unused

import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.MetroIdeAnnotationClassIds
import dev.zacsweers.metro.idea.MetroSettings
import dev.zacsweers.metro.idea.metroIdeState
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.utils.classId
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.toUElement

/**
 * Marks Metro framework entry points as implicitly used for IntelliJ's general dead-code analysis.
 *
 * This is the broad IDE signal for declarations that Metro consumes through generated graph code,
 * even when normal source references do not exist.
 */
class MetroImplicitUsageProvider : ImplicitUsageProvider {

  override fun isImplicitUsage(element: PsiElement): Boolean {
    return element.isMetroImplicitUsage()
  }

  override fun isImplicitRead(element: PsiElement): Boolean {
    return false
  }

  override fun isImplicitWrite(element: PsiElement): Boolean {
    return false
  }
}

internal fun PsiElement.isMetroImplicitUsage(): Boolean {
  if (!MetroSettings.getInstance(project).state.suppressUnusedWarnings) return false
  val state = metroIdeState()
  val options = state.options
  if (!options.enabled) return false
  val annotationClassIds = state.annotationClassIds

  val declaration = ownerDeclaration() ?: return false
  return when (declaration) {
    is KtClass -> declaration.hasGeneratedInjectionEntryPoint(options, annotationClassIds)
    is KtConstructor<*> ->
      declaration.hasAnyMetroAnnotation(annotationClassIds.constructorInjectionAnnotations)
    is KtNamedFunction -> declaration.hasAnyMetroAnnotation(annotationClassIds.functionAnnotations)
    is KtProperty -> declaration.hasAnyMetroAnnotationOnPropertyOrGetter(annotationClassIds)
    is KtPropertyAccessor ->
      declaration.isGetter &&
        declaration.hasAnyMetroAnnotation(annotationClassIds.bindingContainerCallableAnnotations)
    is KtParameter -> declaration.hasAnyMetroAnnotation(annotationClassIds.providesAnnotations)
    else -> false
  }
}

private fun PsiElement.ownerDeclaration(): KtDeclaration? {
  if (navigationElement !== this) {
    (navigationElement as? KtDeclaration)?.let {
      return it
    }
    PsiTreeUtil.getParentOfType(navigationElement, KtDeclaration::class.java, false)?.let {
      return it
    }
  }

  return when (this) {
    is KtDeclaration -> this
    is PsiNameIdentifierOwner -> parent as? KtDeclaration
    else -> PsiTreeUtil.getParentOfType(this, KtDeclaration::class.java, false)
  }
}

private fun KtClass.hasGeneratedInjectionEntryPoint(
  options: MetroOptions,
  annotationClassIds: MetroIdeAnnotationClassIds,
): Boolean {
  if (hasAnyMetroAnnotation(annotationClassIds.classLevelInjectionAnnotations)) return true
  if (hasContributionProviderGeneratedUsage(options, annotationClassIds)) return true

  return hasInjectAnnotatedConstructor(annotationClassIds.constructorInjectionAnnotations)
}

private fun KtClass.hasInjectAnnotatedConstructor(constructorAnnotations: Set<ClassId>): Boolean {
  return primaryConstructor.hasAnyMetroAnnotation(constructorAnnotations) ||
    secondaryConstructors.any { it.hasAnyMetroAnnotation(constructorAnnotations) }
}

private fun KtClass.hasContributionProviderGeneratedUsage(
  options: MetroOptions,
  annotationClassIds: MetroIdeAnnotationClassIds,
): Boolean {
  return options.generateContributionProviders &&
    hasAnyMetroAnnotation(annotationClassIds.bindingContributionAnnotations) &&
    !hasAnyMetroAnnotation(annotationClassIds.contributionProviderExclusionAnnotations)
}

private fun KtProperty.hasAnyMetroAnnotationOnPropertyOrGetter(
  annotationClassIds: MetroIdeAnnotationClassIds
): Boolean {
  return hasAnyMetroAnnotation(annotationClassIds.bindingContainerCallableAnnotations) ||
    getter.hasAnyMetroAnnotation(annotationClassIds.bindingContainerCallableAnnotations)
}

private fun KtAnnotated?.hasAnyMetroAnnotation(classIds: Set<ClassId>): Boolean {
  return this != null && annotationEntries.any { it.isAnyMetroAnnotation(classIds) }
}

private fun KtAnnotationEntry.isAnyMetroAnnotation(classIds: Set<ClassId>): Boolean {
  val annotationClassId =
    when (val annotationClass = typeReference?.mainReference?.resolve()) {
      is KtClassOrObject -> annotationClass.fqName?.let(ClassId::topLevel)
      is PsiClass -> annotationClass.classId
      is PsiMember -> annotationClass.containingClass?.classId
      else -> null
    }
  if (annotationClassId != null) return annotationClassId in classIds

  val uastClassId = toUElement(UAnnotation::class.java)?.resolve()?.classId
  if (uastClassId != null) return uastClassId in classIds

  // PSI/UAST reference resolution can fail for library annotations outside JVM contexts (e.g.
  // klib-backed annotations in KMP common source sets); the Analysis API is authoritative.
  val typeReference = typeReference ?: return false
  return allowAnalysisOnEdt {
    analyze(typeReference) {
      val classId = (typeReference.type.fullyExpandedType as? KaClassType)?.classId
      classId != null && classId in classIds
    }
  }
}
