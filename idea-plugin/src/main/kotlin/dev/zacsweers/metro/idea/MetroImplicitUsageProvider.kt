// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.FqName
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
  val options = metroIdeOptions()
  if (!options.enabled) return false

  val declaration = ownerDeclaration() ?: return false
  return when (declaration) {
    is KtClass -> declaration.hasGeneratedInjectionEntryPoint(options)
    is KtConstructor<*> ->
      declaration.hasAnyMetroAnnotation(options.constructorInjectionAnnotations)
    is KtNamedFunction -> declaration.hasAnyMetroAnnotation(options.functionDeclarationAnnotations)
    is KtProperty -> declaration.hasAnyMetroAnnotationOnPropertyOrGetter(options)
    is KtPropertyAccessor ->
      declaration.isGetter &&
        declaration.hasAnyMetroAnnotation(options.moduleDeclarationAnnotations)
    is KtParameter -> declaration.hasAnyMetroAnnotation(options.providesAnnotations)
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

private fun KtClass.hasGeneratedInjectionEntryPoint(options: MetroIdeOptions): Boolean {
  return hasAnyMetroAnnotation(options.classLevelInjectionAnnotations) ||
    hasContributionProviderGeneratedUsage(options) ||
    primaryConstructor.hasAnyMetroAnnotation(options.constructorInjectionAnnotations) ||
    secondaryConstructors.any { it.hasAnyMetroAnnotation(options.constructorInjectionAnnotations) }
}

private fun KtClass.hasContributionProviderGeneratedUsage(options: MetroIdeOptions): Boolean {
  return options.generateContributionProviders &&
    hasAnyMetroAnnotation(options.bindingContributionAnnotations) &&
    !hasAnyMetroAnnotation(options.contributionProviderExclusionAnnotations)
}

private fun KtProperty.hasAnyMetroAnnotationOnPropertyOrGetter(options: MetroIdeOptions): Boolean {
  return hasAnyMetroAnnotation(options.moduleDeclarationAnnotations) ||
    getter.hasAnyMetroAnnotation(options.moduleDeclarationAnnotations)
}

private fun KtAnnotated?.hasAnyMetroAnnotation(fqNames: Set<FqName>): Boolean {
  return this != null && annotationEntries.any { it.isAnyMetroAnnotation(fqNames) }
}

private fun KtAnnotationEntry.isAnyMetroAnnotation(fqNames: Set<FqName>): Boolean {
  toUElement(UAnnotation::class.java)?.qualifiedName?.let { fqName ->
    if (FqName(fqName) in fqNames) return true
  }

  val annotationClass = calleeExpression?.constructorReferenceExpression?.mainReference?.resolve()
  val annotationFqName =
    when (annotationClass) {
      is KtClassOrObject -> annotationClass.fqName
      is PsiClass -> annotationClass.qualifiedName?.let(::FqName)
      is PsiMember -> annotationClass.containingClass?.qualifiedName?.let(::FqName)
      else -> null
    }
  return annotationFqName in fqNames
}
