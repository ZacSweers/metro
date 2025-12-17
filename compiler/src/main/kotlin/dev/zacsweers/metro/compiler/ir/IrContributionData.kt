// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

private typealias Scope = ClassId

internal class IrContributionData(private val metroContext: IrMetroContext) {

  private val contributions = mutableMapOf<Scope, MutableSet<IrType>>()
  private val externalContributions = mutableMapOf<Scope, Set<IrType>>()
  private val scopeHintCache = mutableMapOf<Scope, CallableId>()

  private fun scopeHintFor(scope: Scope): CallableId =
    scopeHintCache.getOrPut(scope) { Symbols.CallableIds.scopeHint(scope) }

  private val bindingContainerContributions = mutableMapOf<Scope, MutableSet<IrClass>>()
  private val externalBindingContainerContributions = mutableMapOf<Scope, Set<IrClass>>()

  fun addContribution(scope: Scope, contribution: IrType) {
    contributions.getAndAdd(scope, contribution)
  }

  fun getContributions(scope: Scope, callingDeclaration: IrDeclaration? = null): Set<IrType> =
    buildSet {
      contributions[scope]?.let(::addAll)
      addAll(findExternalContributions(scope, callingDeclaration))
    }

  fun addBindingContainerContribution(scope: Scope, contribution: IrClass) {
    bindingContainerContributions.getAndAdd(scope, contribution)
  }

  fun getBindingContainerContributions(
    scope: Scope,
    callingDeclaration: IrDeclaration? = null,
  ): Set<IrClass> = buildSet {
    bindingContainerContributions[scope]?.let(::addAll)
    addAll(findExternalBindingContainerContributions(scope, callingDeclaration))
  }

  /**
   * Tracks a lookup on scope hint functions for incremental compilation. This should be called
   * before checking any caches to ensure all callers register their dependency on scope hint
   * changes.
   */
  fun trackScopeHintLookup(scope: Scope, callingDeclaration: IrDeclaration?) {
    callingDeclaration?.let { caller ->
      with(metroContext) {
        val scopeHintName = scopeHintFor(scope)
        trackClassLookup(
          callingDeclaration = caller,
          container = scopeHintName.packageName,
          declarationName = scopeHintName.callableName.asString(),
        )
      }
    }
  }

  fun findVisibleContributionClassesForScopeInHints(
    scope: Scope,
    includeNonFriendInternals: Boolean = false,
    callingDeclaration: IrDeclaration? = null,
  ): Set<IrClass> {
    val functionsInPackage = metroContext.referenceFunctions(scopeHintFor(scope))

    trackScopeHintLookup(scope, callingDeclaration)

    val contributingClasses =
      functionsInPackage
        .filter {
          if (it.owner.visibility == DescriptorVisibilities.INTERNAL) {
            includeNonFriendInternals ||
              it.owner.fileOrNull?.let { file -> it.owner.isVisibleAsInternal(file) } ?: false
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
  private fun findExternalContributions(
    scope: Scope,
    callingDeclaration: IrDeclaration?,
  ): Set<IrType> {
    // Track the lookup before checking the cache so all callers register their dependency
    trackScopeHintLookup(scope, callingDeclaration)
    return externalContributions.getOrPut(scope) {
      val contributingClasses = findVisibleContributionClassesForScopeInHints(scope)
      getScopedContributions(contributingClasses, scope, bindingContainersOnly = false)
    }
  }

  // TODO this may do multiple lookups of the same origin class if it contributes to multiple scopes
  //  something we could possibly optimize in the future.
  private fun findExternalBindingContainerContributions(
    scope: Scope,
    callingDeclaration: IrDeclaration?,
  ): Set<IrClass> {
    // Track the lookup before checking the cache so all callers register their dependency
    trackScopeHintLookup(scope, callingDeclaration)
    return externalBindingContainerContributions.getOrPut(scope) {
      val contributingClasses = findVisibleContributionClassesForScopeInHints(scope)
      getScopedContributions(contributingClasses, scope, bindingContainersOnly = true)
        .mapNotNullToSet {
          it.classOrNull?.owner?.takeIf { irClass ->
            with(metroContext) { irClass.isBindingContainer() }
          }
        }
    }
  }

  // Replacement processing is intentionally NOT done here. It's handled in
  // IrContributionMerger.computeContributions() after exclusions are applied, so that
  // excluded classes don't have their `replaces` effect applied.
  private fun getScopedContributions(
    contributingClasses: Collection<IrClass>,
    scope: Scope,
    bindingContainersOnly: Boolean,
  ): Set<IrType> {
    return contributingClasses
      .let { contributions ->
        if (bindingContainersOnly) {
          contributions.filter { irClass -> with(metroContext) { irClass.isBindingContainer() } }
        } else {
          contributions.filterNot { irClass -> with(metroContext) { irClass.isBindingContainer() } }
        }
      }
      .flatMapToSet { irClass ->
        with(metroContext) {
          if (irClass.isBindingContainer()) {
            setOf(irClass.defaultType)
          } else {
            irClass.nestedClasses.mapNotNullToSet { nestedClass ->
              val metroContribution =
                nestedClass.findAnnotations(Symbols.ClassIds.metroContribution).singleOrNull()
                  ?: return@mapNotNullToSet null
              val contributionScope =
                metroContribution.scopeOrNull()
                  ?: reportCompilerBug("No scope found for @MetroContribution annotation")
              if (contributionScope == scope) {
                nestedClass.defaultType
              } else {
                null
              }
            }
          }
        }
      }
  }
}
