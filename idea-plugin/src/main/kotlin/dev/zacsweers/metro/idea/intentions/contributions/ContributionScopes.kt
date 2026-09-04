// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.intentions.contributions

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.annotationScopeKeys
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.metroIdeState
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.createUseSiteVisibilityChecker
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.renderer.render

/** A known scope or an editor field for a scope that has not appeared in the index yet. */
internal data class ContributionScope(
  val className: String,
  val editable: Boolean = false,
  val classId: ClassId? = null,
) {
  val label: String
    get() = if (editable) "Enter scope in editor" else className
}

/** Reads existing scope references only when the user opens the contribution action. */
internal fun contributionScopes(
  owner: KtClassOrObject,
  preferredScope: String?,
): List<ContributionScope> {
  val index = owner.project.service<MetroResolutionService>().cachedIndex(owner)
  val scopeIds = linkedSetOf<ClassId>()
  for (graph in index.graphs) scopeIds += graph.scopeKeys
  for (contribution in index.contributions) scopeIds += contribution.scopeKeys
  val options = owner.metroIdeState().options
  val scopeAnnotations =
    options.dependencyGraphAnnotations +
      options.graphExtensionAnnotations +
      options.allContributesAnnotations
  return analyze(owner) {
    val ownerSymbol = owner.symbol as? KaNamedClassSymbol
    ownerSymbol
      ?.annotations
      ?.firstOrNull { it.classId == MetroClassIds.singleIn }
      ?.let {
        scopeIds += annotationScopeKeys(it)
      }
    // The current file can contain a new graph that the published index has not captured yet.
    for (declaration in owner.containingKtFile.collectDescendantsOfType<KtClassOrObject>()) {
      ProgressManager.checkCanceled()
      val symbol = declaration.symbol as? KaNamedClassSymbol ?: continue
      for (annotation in symbol.annotations) {
        if (annotation.classId in scopeAnnotations) scopeIds += annotationScopeKeys(annotation)
      }
    }
    val checker = createUseSiteVisibilityChecker(owner.containingKtFile.symbol, null, owner)
    val scopes = scopeIds.mapNotNull { id ->
      ProgressManager.checkCanceled()
      val symbol = contributionScopeSymbol(id, options) ?: return@mapNotNull null
      if (!checker.isVisible(symbol)) return@mapNotNull null
      knownContributionScope(id)
    }
    scopes.sortedWith(compareBy({ it.className != preferredScope }, { it.className })) +
      ContributionScope("YourScope", editable = true)
  }
}

/** Resolves only the user's selected scope when checking the final edit. */
internal fun contributionScope(owner: KtClassOrObject, classId: ClassId): ContributionScope? {
  val options = owner.metroIdeState().options
  return analyze(owner) {
    val symbol = contributionScopeSymbol(classId, options) ?: return@analyze null
    val checker = createUseSiteVisibilityChecker(owner.containingKtFile.symbol, null, owner)
    if (!checker.isVisible(symbol)) return@analyze null
    knownContributionScope(classId)
  }
}

private fun KaSession.contributionScopeSymbol(
  id: ClassId,
  options: MetroOptions,
): KaNamedClassSymbol? {
  val symbol = findClass(id) as? KaNamedClassSymbol ?: return null
  if (symbol.hasAnyAnnotation(options.scopeAnnotations)) return null
  if (symbol.hasAnyAnnotation(options.dependencyGraphAnnotations)) return null
  if (symbol.hasAnyAnnotation(options.graphExtensionAnnotations)) return null
  return symbol
}

private fun knownContributionScope(id: ClassId): ContributionScope {
  val name = id.asSingleFqName().pathSegments().joinToString(".") { it.render() }
  return ContributionScope(name, classId = id)
}
