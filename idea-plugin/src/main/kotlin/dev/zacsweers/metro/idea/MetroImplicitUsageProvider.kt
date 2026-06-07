// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.psi.PsiElement
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
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor

private val BINDS = FqName("dev.zacsweers.metro.Binds")
private val PROVIDES = FqName("dev.zacsweers.metro.Provides")
private val MULTIBINDS = FqName("dev.zacsweers.metro.Multibinds")
private val INJECT = FqName("dev.zacsweers.metro.Inject")
private val METRO_PACKAGE = FqName("dev.zacsweers.metro")
private val MODULE_DECLARATION_ANNOTATIONS = setOf(BINDS, PROVIDES, MULTIBINDS)
private val FUNCTION_DECLARATION_ANNOTATIONS = MODULE_DECLARATION_ANNOTATIONS + INJECT
private val PROVIDES_ANNOTATION = setOf(PROVIDES)

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
  if (!isMetroEnabled()) return false

  val declaration = ownerDeclaration() ?: return false
  return when (declaration) {
    is KtClass -> declaration.hasInjectConstructor()
    is KtConstructor<*> -> declaration.hasMetroAnnotation(INJECT)
    is KtNamedFunction -> declaration.hasAnyMetroAnnotation(FUNCTION_DECLARATION_ANNOTATIONS)
    is KtProperty -> declaration.hasAnyMetroAnnotationOnPropertyOrGetter()
    is KtPropertyAccessor ->
      declaration.isGetter && declaration.hasAnyMetroAnnotation(MODULE_DECLARATION_ANNOTATIONS)
    is KtParameter -> declaration.hasAnyMetroAnnotation(PROVIDES_ANNOTATION)
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

private fun KtClass.hasInjectConstructor(): Boolean {
  return primaryConstructor.hasMetroAnnotation(INJECT) ||
    secondaryConstructors.any { it.hasMetroAnnotation(INJECT) }
}

private fun KtAnnotated?.hasMetroAnnotation(fqName: FqName): Boolean {
  return hasAnyMetroAnnotation(setOf(fqName))
}

private fun KtProperty.hasAnyMetroAnnotationOnPropertyOrGetter(): Boolean {
  return hasAnyMetroAnnotation(MODULE_DECLARATION_ANNOTATIONS) ||
    getter.hasAnyMetroAnnotation(MODULE_DECLARATION_ANNOTATIONS)
}

private fun KtAnnotated?.hasAnyMetroAnnotation(fqNames: Set<FqName>): Boolean {
  return this != null && annotationEntries.any { it.isAnyMetroAnnotation(fqNames) }
}

private fun KtAnnotationEntry.isAnyMetroAnnotation(fqNames: Set<FqName>): Boolean {
  val shortName = shortName?.asString() ?: return false
  val candidates = fqNames.filter { it.shortName().asString() == shortName }
  if (candidates.isEmpty()) return false

  val text = typeReference?.text
  if (candidates.any { text == it.asString() }) return true

  val ktFile = containingKtFile()
  if (
    ktFile.importDirectives.any { import ->
      val importedFqName = import.importedFqName
      importedFqName in candidates || (import.isAllUnder && importedFqName == METRO_PACKAGE)
    }
  ) {
    return true
  }

  return (calleeExpression?.constructorReferenceExpression?.mainReference?.resolve()
      as? KtClassOrObject)
    ?.fqName in candidates
}

private fun KtAnnotationEntry.containingKtFile(): KtFile {
  return containingFile as KtFile
}
