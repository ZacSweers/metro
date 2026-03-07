// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.getAndAdd
import dev.zacsweers.metro.compiler.ir.transformers.Lockable
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

private typealias Scope = ClassId

internal class IrContributionData(private val metroContext: IrMetroContext) :
  Lockable by Lockable() {

  private val contributions = MutableScatterMap<Scope, MutableScatterSet<IrType>>()
  // Lazily populated caches use ConcurrentHashMap for thread-safe access during parallel
  // graph extension validation. These are not structural mutations (just caching lookups),
  // so they remain writable after lock().
  private val externalContributions = ConcurrentHashMap<Scope, Set<IrType>>()
  private val scopeHintCache = ConcurrentHashMap<Scope, CallableId>()

  private fun scopeHintFor(scope: Scope): CallableId =
    scopeHintCache.getOrPut(scope) { Symbols.CallableIds.scopeHint(scope) }

  private val bindingContainerContributions = MutableScatterMap<Scope, MutableScatterSet<IrClass>>()
  private val externalBindingContainerContributions = ConcurrentHashMap<Scope, Set<IrClass>>()

  // Cache for findVisibleContributionClassesForScopeInHints results.
  // This avoids redundant lookups when both findExternalContributions and
  // findExternalBindingContainerContributions are called for the same scope.
  private val visibleContributionClassesCache = ConcurrentHashMap<Scope, Set<IrClass>>()

  fun addContribution(scope: Scope, contribution: IrType) {
    checkNotLocked()
    contributions.getAndAdd(scope, contribution)
  }

  context(traceScope: TraceScope)
  fun getContributions(scope: Scope, callingDeclaration: IrDeclaration): Set<IrType> = buildSet {
    contributions[scope]?.forEach(::add)
    addAll(findExternalContributions(scope, callingDeclaration))
  }

  fun addBindingContainerContribution(scope: Scope, contribution: IrClass) {
    checkNotLocked()
    bindingContainerContributions.getAndAdd(scope, contribution)
  }

  context(traceScope: TraceScope)
  fun getBindingContainerContributions(
    scope: Scope,
    callingDeclaration: IrDeclaration,
  ): Set<IrClass> = buildSet {
    bindingContainerContributions[scope]?.forEach(::add)
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
    callingDeclaration: IrDeclaration,
    includeNonFriendInternals: Boolean = false,
  ): Set<IrClass> {
    // Always track the lookup for incremental compilation
    trackScopeHintLookup(scope, callingDeclaration)

    // Check cache first (unless includeNonFriendInternals which changes the result)
    if (!includeNonFriendInternals) {
      visibleContributionClassesCache[scope]?.let { cached ->
        // Still need to track class lookups for IC even on cache hit
        for (irClass in cached) {
          context(metroContext) { trackClassLookup(callingDeclaration, irClass) }
        }
        return cached
      }
    }

    val functionsInPackage = metroContext.referenceFunctions(scopeHintFor(scope))

    context(metroContext) {
      writeDiagnostic("discovered-hints-ir", "${scope.asFqNameString()}.txt") {
        functionsInPackage.map { it.owner.dumpKotlinLike() }.sorted().joinToString("\n") +
          "\n----\nCalled by:\n${callingDeclaration.expectAsOrNull<IrDeclarationWithName>()?.name}"
      }
    }

    val contributingClasses =
      functionsInPackage
        .filter { hintFunctionSymbol ->
          val hintFunction = hintFunctionSymbol.owner
          if (hintFunction.visibility == DescriptorVisibilities.INTERNAL) {
            includeNonFriendInternals ||
              callingDeclaration.fileOrNull?.let { file -> hintFunction.isVisibleAsInternal(file) }
                ?: false
          } else {
            true
          }
        }
        .mapToSet { contribution ->
          // This is the single value param
          contribution.owner.regularParameters.single().type.classOrFail.owner.also {
            // Ensure we also track the contributing class, not just the hint
            context(metroContext) { trackClassLookup(callingDeclaration, it) }
          }
        }

    // Cache the result (only for the default case without includeNonFriendInternals)
    if (!includeNonFriendInternals) {
      visibleContributionClassesCache[scope] = contributingClasses
    }

    return contributingClasses
  }

  // Note: Origin classes may be looked up multiple times if they contribute to multiple scopes.
  // findVisibleContributionClassesForScopeInHints is cached per scope, so redundant lookups
  // within the same scope are avoided.
  context(traceScope: TraceScope)
  private fun findExternalContributions(
    scope: Scope,
    callingDeclaration: IrDeclaration,
  ): Set<IrType> {
    // Track the lookup before checking the cache so all callers register their dependency
    trackScopeHintLookup(scope, callingDeclaration)
    return externalContributions.getOrPut(scope) {
      trace("Look up external contributions for $scope") {
        val contributingClasses =
          findVisibleContributionClassesForScopeInHints(
            scope,
            callingDeclaration = callingDeclaration,
          )
        getScopedContributions(contributingClasses, scope, bindingContainersOnly = false)
      }
    }
  }

  // Note: Origin classes may be looked up multiple times if they contribute to multiple scopes.
  // findVisibleContributionClassesForScopeInHints is cached per scope, so redundant lookups
  // within the same scope are avoided.
  context(traceScope: TraceScope)
  private fun findExternalBindingContainerContributions(
    scope: Scope,
    callingDeclaration: IrDeclaration,
  ): Set<IrClass> {
    // Track the lookup before checking the cache so all callers register their dependency
    trackScopeHintLookup(scope, callingDeclaration)
    return externalBindingContainerContributions.getOrPut(scope) {
      trace("Look up external contributions for $scope") {
        val contributingClasses =
          findVisibleContributionClassesForScopeInHints(scope, callingDeclaration)
        getScopedContributions(contributingClasses, scope, bindingContainersOnly = true)
          .mapNotNullToSet {
            it.classOrNull?.owner?.takeIf { irClass ->
              with(metroContext) { irClass.isBindingContainer() }
            }
          }
      }
    }
  }

  // Replacement processing is intentionally NOT done here. It's handled in
  // IrContributionMerger.computeContributions() after exclusions are applied, so that
  // excluded classes don't have their `replaces` effect applied.
  context(traceScope: TraceScope)
  private fun getScopedContributions(
    contributingClasses: Collection<IrClass>,
    scope: Scope,
    bindingContainersOnly: Boolean,
  ): Set<IrType> =
    trace("Get scoped contributions for $scope") {
      contributingClasses
        .let { contributions ->
          if (bindingContainersOnly) {
            contributions.filter { irClass -> with(metroContext) { irClass.isBindingContainer() } }
          } else {
            contributions.filterNot { irClass ->
              with(metroContext) { irClass.isBindingContainer() }
            }
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
