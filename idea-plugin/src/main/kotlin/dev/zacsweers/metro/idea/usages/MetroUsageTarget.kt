// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.usages

import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.zacsweers.metro.idea.MetroIdeModuleState
import dev.zacsweers.metro.idea.MetroIdeProjectService
import dev.zacsweers.metro.idea.index.annotationShortNamesIncludingAliases
import dev.zacsweers.metro.idea.index.sweepAnnotationIds
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty

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
    val state = stateService.state(projectModule)
    state.isEnabled && hasPotentialMetroContext(state)
  }
}

private fun KtDeclaration.hasPotentialMetroContext(state: MetroIdeModuleState): Boolean {
  val annotationNames =
    containingKtFile.annotationShortNamesIncludingAliases(sweepAnnotationIds(state.options))

  var context: PsiElement? = this
  while (context != null && context !is KtFile) {
    if ((context as? KtAnnotated).hasAnyAnnotationNamed(annotationNames)) return true
    context = context.parent
  }

  return when (this) {
    is KtClassOrObject ->
      primaryConstructor.hasAnyAnnotationNamed(annotationNames) ||
        secondaryConstructors.any { it.hasAnyAnnotationNamed(annotationNames) }
    is KtProperty -> getter.hasAnyAnnotationNamed(annotationNames)
    else -> false
  }
}

private fun KtAnnotated?.hasAnyAnnotationNamed(names: Set<String>): Boolean {
  return this != null && annotationEntries.any { it.shortName?.asString() in names }
}
