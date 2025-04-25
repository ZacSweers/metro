// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.mapNotNullToSet
import kotlin.collections.flatMap
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.name.ClassId

internal class IrContributionData(private val metroContext: IrMetroContext) {
  private val contributions = mutableMapOf<ClassId, MutableSet<IrType>>()
  private val externalContributions = mutableMapOf<ClassId, Set<IrType>>()

  fun addContribution(scope: ClassId, contribution: IrType) {
    contributions.getOrPut(scope) { mutableSetOf() }.add(contribution)
  }

  operator fun get(scope: ClassId): Set<IrType> = buildSet {
    contributions[scope]?.let(::addAll)
    addAll(findExternalContributions(scope))
  }

  // TODO this may do multiple lookups of the same origin class if it contributes to multiple scopes
  //  something we could possibly optimize in the future.
  private fun findExternalContributions(scopeClassId: ClassId): Set<IrType> {
    return externalContributions.getOrPut(scopeClassId) {
      val functionsInPackage =
        metroContext.pluginContext.referenceFunctions(Symbols.CallableIds.scopeHint(scopeClassId))
      val contributingClasses =
        functionsInPackage.map { contribution ->
          // This is the single value param
          contribution.owner.valueParameters.single().type.classOrFail.owner
        }
      getScopedContributions(contributingClasses, scopeClassId)
    }
  }

  private fun getScopedContributions(
    contributingClasses: List<IrClass>,
    scopeClassId: ClassId,
  ): Set<IrType> {
    return contributingClasses
      .flatMap { it.nestedClasses }
      .mapNotNullToSet { nestedClass ->
        val metroContribution =
          nestedClass.findAnnotations(Symbols.ClassIds.metroContribution).singleOrNull()
            ?: return@mapNotNullToSet null
        val contributionScope =
          metroContribution.scopeOrNull()
            ?: error("No scope found for @MetroContribution annotation")
        if (contributionScope == scopeClassId) {
          nestedClass.defaultType
        } else {
          null
        }
      }
  }
}
