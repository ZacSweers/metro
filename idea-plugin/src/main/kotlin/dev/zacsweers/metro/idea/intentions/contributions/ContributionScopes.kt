// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.intentions.contributions

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import dev.zacsweers.metro.idea.annotationScopeKeys
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.metroIdeState
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.createUseSiteVisibilityChecker
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.renderer.render

/** A known scope or an editor field for a scope that has not appeared in the index yet. */
internal data class ContributionScope(val className: String, val editable: Boolean = false) {
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
    // The current file can contain a new graph that the published index has not captured yet.
    for (declaration in owner.containingKtFile.collectDescendantsOfType<KtClassOrObject>()) {
      ProgressManager.checkCanceled()
      val symbol = declaration.symbol as? KaNamedClassSymbol ?: continue
      for (annotation in symbol.annotations) {
        if (annotation.classId in scopeAnnotations) scopeIds += annotationScopeKeys(annotation)
      }
    }
    val checker = createUseSiteVisibilityChecker(owner.containingKtFile.symbol, null, owner)
    val scopes =
      scopeIds
        .mapNotNull { id ->
          ProgressManager.checkCanceled()
          val symbol = findClass(id) ?: return@mapNotNull null
          if (!checker.isVisible(symbol)) return@mapNotNull null
          if (symbol.hasAnyAnnotation(options.scopeAnnotations)) return@mapNotNull null
          if (symbol.hasAnyAnnotation(options.dependencyGraphAnnotations)) return@mapNotNull null
          if (symbol.hasAnyAnnotation(options.graphExtensionAnnotations)) return@mapNotNull null
          ContributionScope(id.asSingleFqName().pathSegments().joinToString(".") { it.render() })
        }
        .toMutableList()
    if (preferredScope != null && scopes.none { it.className == preferredScope }) {
      scopes += ContributionScope(preferredScope)
    }
    scopes.sortedWith(compareBy({ it.className != preferredScope }, { it.className })) +
      ContributionScope("YourScope", editable = true)
  }
}
