// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.mapNotNullToSet
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.name.ClassId

internal class IrContributionData(private val metroContext: IrMetroContext) {
  private val contributions = mutableMapOf<ClassId, MutableSet<IrType>>()
  private val externalContributions = mutableMapOf<ClassId, Set<IrType>>()

  // Scoped inject classes are currently tracked separately from contributions because we need to
  // maintain the full scope info (e.g. @Singleton, @SingleIn(AppScope)) for accurate comparisons.
  // Conversely, contributions only ever have a scope arg available (e.g. @ContributesTo(AppScope),
  // @ContributesTo(Singleton)), so we can't effectively map between and compare the two for the
  // types of hints that we want to generate.
  private val scopeToInjectClasses = mutableMapOf<IrAnnotation, MutableSet<IrTypeKey>>()
  private val externalScopeToInjectClasses = mutableMapOf<IrAnnotation, Set<IrTypeKey>>()

  fun addContribution(scope: ClassId, contribution: IrType) {
    contributions.getOrPut(scope) { mutableSetOf() }.add(contribution)
  }

  fun getContributions(scope: ClassId): Set<IrType> = buildSet {
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
          contribution.owner.regularParameters.single().type.classOrFail.owner
        }
      getScopedContributions(contributingClasses, scopeClassId)
    }
  }

  private fun getScopedContributions(
    contributingClasses: List<IrClass>,
    scopeClassId: ClassId,
  ): Set<IrType> {
    val filteredContributions = contributingClasses.toMutableList()

    // Remove replaced contributions
    filteredContributions
      .flatMap { contributingType ->
        contributingType
          .annotationsIn(metroContext.symbols.classIds.allContributesAnnotations)
          .flatMap { annotation -> annotation.replacedClasses() }
      }
      .distinct()
      .forEach { replacedClass ->
        filteredContributions.removeIf { it.symbol == replacedClass.symbol }
      }

    return filteredContributions
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

  fun addScopedInject(scope: IrAnnotation, contribution: IrTypeKey) {
    scopeToInjectClasses.getOrPut(scope, ::mutableSetOf).add(contribution)
  }

  fun getScopedInjectClasses(scope: IrAnnotation): Set<IrTypeKey> = buildSet {
    scopeToInjectClasses[scope]?.let(::addAll)
    addAll(findExternalScopedInjects(scope))
  }

  private fun findExternalScopedInjects(scope: IrAnnotation): Set<IrTypeKey> {
    return externalScopeToInjectClasses.getOrPut(scope) {
      val unfilteredScopedInjectClasses =
        metroContext.pluginContext
          .referenceFunctions(Symbols.CallableIds.scopedInjectClassHint(scope))
          .map { hintFunction ->
            hintFunction.owner.regularParameters.single().type.classOrFail.owner
          }

      return unfilteredScopedInjectClasses.mapNotNullToSet { clazz ->
        // We filter by the source class annotations instead of the hint function because there's
        // currently an IC issue where old hint functions do not get invalidated, so we can't rely
        // on them without validating against the source again
        val classScopes =
          clazz.annotationsAnnotatedWithAny(metroContext.symbols.classIds.scopeAnnotations).map {
            IrAnnotation(it)
          }
        if (scope in classScopes) {
          with(metroContext) { IrTypeKey(clazz) }
        } else {
          null
        }
      }
    }
  }
}
