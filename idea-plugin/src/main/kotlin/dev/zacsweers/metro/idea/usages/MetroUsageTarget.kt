// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.usages

import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.MetroIdeModuleState
import dev.zacsweers.metro.idea.MetroIdeProjectService
import dev.zacsweers.metro.idea.index.snapshot.annotationShortNamesIncludingAliases
import dev.zacsweers.metro.idea.index.snapshot.sweepAnnotationIds
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty

private val defaultMetroAnnotationIds = sweepAnnotationIds(MetroOptions())

internal fun PsiElement.metroSourceDeclaration(): KtDeclaration? {
  if (!isValid) return null
  if (this is KtDeclaration) return this
  PsiTreeUtil.getParentOfType(this, KtDeclaration::class.java, false)?.let {
    return it
  }

  val navigationElement = navigationElement
  if (!navigationElement.isValid) return null
  if (navigationElement is KtDeclaration) return navigationElement
  return PsiTreeUtil.getParentOfType(navigationElement, KtDeclaration::class.java, false)
}

internal fun KtDeclaration.hasPotentialMetroContext(): Boolean {
  val stateService = project.service<MetroIdeProjectService>()
  val module = ModuleUtilCore.findModuleForPsiElement(this)
  if (module != null) {
    val state = stateService.state(module)
    return state.isEnabled && hasPotentialMetroContext(state)
  }

  return ModuleManager.getInstance(project).modules.any { projectModule ->
    ProgressManager.checkCanceled()
    val state = stateService.state(projectModule)
    state.isEnabled && hasPotentialMetroContext(state)
  }
}

/**
 * Checks annotation names using cached options. Null means the module options are still unknown.
 */
internal fun KtDeclaration.hasPotentialCachedMetroContext(): Boolean? {
  val stateService = project.service<MetroIdeProjectService>()
  val module = ModuleUtilCore.findModuleForPsiElement(this)
  if (module != null) {
    val state = stateService.currentStateOrSchedule(this) ?: return null
    return state.isEnabled && hasPotentialMetroContext(state)
  }

  val annotationIds = mutableSetOf<ClassId>()
  var hasIncompleteState = false
  for (projectModule in ModuleManager.getInstance(project).modules) {
    ProgressManager.checkCanceled()
    val state = stateService.currentStateOrNull(projectModule)
    if (state == null) {
      hasIncompleteState = true
      continue
    }
    if (state.isEnabled) annotationIds += sweepAnnotationIds(state.options)
  }
  if (annotationIds.isNotEmpty() && hasPotentialMetroContext(annotationIds)) return true
  return if (hasIncompleteState) null else false
}

private fun KtDeclaration.hasPotentialMetroContext(state: MetroIdeModuleState): Boolean {
  return hasPotentialMetroContext(sweepAnnotationIds(state.options))
}

private fun KtDeclaration.hasPotentialMetroContext(annotationIds: Set<ClassId>): Boolean {
  val annotationNames = containingKtFile.annotationShortNamesIncludingAliases(annotationIds)
  return hasPotentialAnnotationContext { it.hasAnyAnnotationNamed(annotationNames) }
}

/** Checks default annotation names so action updates can run before module options are loaded. */
internal fun KtDeclaration.hasPotentialDefaultMetroContext(): Boolean {
  val annotationNames =
    containingKtFile.annotationShortNamesIncludingAliases(defaultMetroAnnotationIds)
  return hasPotentialAnnotationContext { it.hasAnyAnnotationNamed(annotationNames) }
}

private fun KtDeclaration.hasPotentialAnnotationContext(
  hasMatchingAnnotation: (KtAnnotated?) -> Boolean
): Boolean {
  var context: PsiElement? = this
  while (context != null && context !is KtFile) {
    if (hasMatchingAnnotation(context as? KtAnnotated)) return true
    context = context.parent
  }

  return when (this) {
    is KtClassOrObject ->
      hasMatchingAnnotation(primaryConstructor) ||
        secondaryConstructors.any { hasMatchingAnnotation(it) }
    is KtProperty -> hasMatchingAnnotation(getter)
    else -> false
  }
}

private fun KtAnnotated?.hasAnyAnnotationNamed(names: Set<String>): Boolean {
  return this != null && annotationEntries.any { it.shortName?.asString() in names }
}
