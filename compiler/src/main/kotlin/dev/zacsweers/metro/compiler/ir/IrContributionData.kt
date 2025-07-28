// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.mapToSet
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.moduleDescriptor
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.name.ClassId

internal class IrContributionData(private val metroContext: IrMetroContext) {
  private val contributions = mutableMapOf<ClassId, MutableSet<IrType>>()
  private val externalContributions = mutableMapOf<ClassId, Set<IrType>>()

  private val bindingContainerContributions = mutableMapOf<ClassId, MutableSet<IrClass>>()
  private val externalBindingContainerContributions = mutableMapOf<ClassId, Set<IrClass>>()

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

  fun addBindingContainerContribution(scope: ClassId, contribution: IrClass) {
    bindingContainerContributions.getOrPut(scope) { mutableSetOf() }.add(contribution)
  }

  fun getBindingContainerContributions(scope: ClassId): Set<IrClass> = buildSet {
    bindingContainerContributions[scope]?.let(::addAll)
    addAll(findExternalBindingContainerContributions(scope))
  }

  private fun findVisibleContributionClassesForScopeInHints(scopeClassId: ClassId): Set<IrClass> {
    val functionsInPackage =
      metroContext.referenceFunctions(Symbols.CallableIds.scopeHint(scopeClassId))
    val contributingClasses =
      functionsInPackage
        .filter {
          if (it.owner.visibility == Visibilities.Internal) {
            it.owner.isVisibleAsInternal(it.owner.file)
          } else {
            true
          }
        }
        .mapToSet { contribution ->
          // This is the single value param
          contribution.owner.regularParameters.single().type.classOrFail.owner
        }
    return contributingClasses
  }

  // TODO this may do multiple lookups of the same origin class if it contributes to multiple scopes
  //  something we could possibly optimize in the future.
  private fun findExternalContributions(scopeClassId: ClassId): Set<IrType> {
    return externalContributions.getOrPut(scopeClassId) {
      val contributingClasses = findVisibleContributionClassesForScopeInHints(scopeClassId)
      getScopedContributions(contributingClasses, scopeClassId, bindingContainersOnly = false)
    }
  }

  // TODO this may do multiple lookups of the same origin class if it contributes to multiple scopes
  //  something we could possibly optimize in the future.
  private fun findExternalBindingContainerContributions(scopeClassId: ClassId): Set<IrClass> {
    return externalBindingContainerContributions.getOrPut(scopeClassId) {
      val contributingClasses = findVisibleContributionClassesForScopeInHints(scopeClassId)
      getScopedContributions(contributingClasses, scopeClassId, bindingContainersOnly = true)
        .mapNotNullToSet {
          it.classOrNull?.owner?.takeIf {
            it.isAnnotatedWithAny(metroContext.symbols.classIds.bindingContainerAnnotations)
          }
        }
    }
  }

  private fun getScopedContributions(
    contributingClasses: Collection<IrClass>,
    scopeClassId: ClassId,
    bindingContainersOnly: Boolean,
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
      .let { contributions ->
        if (bindingContainersOnly) {
          contributions.filter {
            it.isAnnotatedWithAny(metroContext.symbols.classIds.bindingContainerAnnotations)
          }
        } else {
          contributions.filterNot {
            it.isAnnotatedWithAny(metroContext.symbols.classIds.bindingContainerAnnotations)
          }
        }
      }
      .flatMapToSet {
        if (it.isAnnotatedWithAny(metroContext.symbols.classIds.bindingContainerAnnotations)) {
          setOf(it.defaultType)
        } else {
          it.nestedClasses.mapNotNullToSet { nestedClass ->
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
        metroContext
          .referenceFunctions(Symbols.CallableIds.scopedInjectClassHint(scope))
          .filter { hintFunction ->
            hintFunction.owner.annotations.any { IrAnnotation(it) == scope }
          }
          .mapToSet { hintFunction ->
            IrTypeKey(hintFunction.owner.regularParameters.single().type)
          }

      return unfilteredScopedInjectClasses
    }
  }

  // Copied from CheckerUtils.kt
  private fun IrDeclarationWithVisibility.isVisibleAsInternal(file: IrFile): Boolean {
    val referencedDeclarationPackageFragment = getPackageFragment()
    val module = file.module
    return module.descriptor.shouldSeeInternalsOf(
      referencedDeclarationPackageFragment.moduleDescriptor
    )
  }
}
