// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import androidx.collection.mutableScatterSetOf
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import dev.zacsweers.metro.compiler.api.ir.MetroIrContributionExtension
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
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

private typealias Scope = ClassId

// External contribution lookups are visibility-sensitive. The same scope can see different
// internal hint functions depending on which graph is doing the lookup.
private data class ScopeLookupKey(val scope: Scope, val caller: ClassId?)

@Inject
@SingleIn(IrScope::class)
@ContributesBinding(IrScope::class)
internal class IrContributionData(
  private val metroContext: IrMetroContext,
  private val metroDeclarations: MetroDeclarations,
  private val irContributionExtensions: List<MetroIrContributionExtension>,
) : Lockable by Lockable() {

  private val contributions = MutableScatterMap<Scope, MutableScatterSet<IrType>>()
  private val directSupertypeContributions = MutableScatterMap<Scope, MutableScatterSet<IrClass>>()
  // Lazily populated caches use ConcurrentHashMap for thread-safe access during parallel
  // graph extension validation. These are not structural mutations (just caching lookups),
  // so they remain writable after lock().
  private val externalContributions = ConcurrentHashMap<ScopeLookupKey, Set<IrType>>()
  private val scopeHintCache = ConcurrentHashMap<Scope, CallableId>()

  private fun scopeHintFor(scope: Scope): CallableId =
    scopeHintCache.getOrPut(scope) { Symbols.CallableIds.scopeHint(scope) }

  private fun lookupKey(scope: Scope, callingDeclaration: IrDeclaration): ScopeLookupKey =
    ScopeLookupKey(scope, (callingDeclaration as? IrClass)?.classId)

  private val bindingContainerContributions = MutableScatterMap<Scope, MutableScatterSet<IrClass>>()
  private val externalBindingContainerContributions =
    MutableScatterMap<ScopeLookupKey, ScatterSet<IrClass>>()

  // External "raw" supertype contributions from IR extensions. Kept separate from `contributions`
  // because the latter holds nested `MetroContribution` markers, while these are top-level
  // interfaces (e.g., Hilt entry points) that don't have a contributing-class parent.
  private val extensionSupertypes = MutableScatterMap<ScopeLookupKey, Set<IrClass>>()

  // Cache for findVisibleContributionClassesForScopeInHints results.
  // This avoids redundant lookups when both findExternalContributions and
  // findExternalBindingContainerContributions are called for the same scope.
  private val visibleContributionClassesCache = MutableScatterMap<ScopeLookupKey, Set<IrClass>>()

  fun addContribution(scope: Scope, contribution: IrType) {
    checkNotLocked()
    contributions.getAndAdd(scope, contribution)
  }

  fun addDirectSupertypeContribution(scope: Scope, contribution: IrClass) {
    checkNotLocked()
    directSupertypeContributions.getAndAdd(scope, contribution)
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
    findExternalBindingContainerContributions(scope, callingDeclaration).forEach(::add)
  }

  /**
   * Returns source-level interfaces that should be added directly as graph supertypes.
   *
   * Legacy FIR generation stores most contributions as nested `MetroContribution` markers, and the
   * merger promotes those markers back to their parent classes. IR-only class generation skips
   * those FIR markers, so direct `@ContributesTo` interfaces are tracked here and merged as raw
   * supertypes. Registered [MetroIrContributionExtension]s use the same path for external raw
   * supertypes such as Hilt entry points.
   */
  context(traceScope: TraceScope)
  fun getExtensionSupertypes(scope: Scope, callingDeclaration: IrDeclaration): Set<IrClass> {
    trackScopeHintLookup(scope, callingDeclaration)
    return extensionSupertypes.getOrPut(lookupKey(scope, callingDeclaration)) {
      trace("Look up extension supertypes for $scope") {
        buildSet {
          // Interfaces contributed in this compilation are collected before metadata hint lookup.
          directSupertypeContributions[scope]?.forEach(::add)

          if (metroContext.options.generateClassesInIr) {
            // Cross-module direct interfaces are discovered from regular contribution hints. Hidden
            // generated markers are metadata-visible too, but only source interfaces belong in the
            // graph impl's direct supertype list.
            addAll(
              findVisibleContributionClassesForScopeInHints(scope, callingDeclaration).filter {
                it.isDirectContributedInterface(scope)
              }
            )
          }

          // Extension APIs can add raw supertypes that have no MetroContribution marker parent.
          addAll(
            irContributionExtensions.flatMapToSet { extension ->
              extension.contributeSupertypes(scope, callingDeclaration).mapNotNullToSet { type ->
                type.classOrNull?.owner?.also { irClass ->
                  with(metroContext) { trackClassLookup(callingDeclaration, irClass) }
                }
              }
            }
          )
        }
      }
    }
  }

  private fun IrClass.isDirectContributedInterface(scope: Scope): Boolean {
    if (kind != ClassKind.INTERFACE) return false
    if (isAnnotatedWithAny(metroContext.metroSymbols.classIds.graphExtensionFactoryAnnotations)) {
      return false
    }
    return annotationsIn(metroContext.metroSymbols.classIds.contributesToAnnotations).any {
      it.scopeOrNull() == scope
    }
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
      visibleContributionClassesCache[lookupKey(scope, callingDeclaration)]?.let { cached ->
        // Still need to track class lookups for IC even on cache hit
        for (irClass in cached) {
          context(metroContext) { trackClassLookup(callingDeclaration, irClass) }
        }
        return cached
      }
    }

    val functionsInPackage =
      context(metroContext) { callingDeclaration.lookupFunctions(scopeHintFor(scope)) }

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
            includeNonFriendInternals || hintFunction.isVisibleAsInternalTo(callingDeclaration)
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
      visibleContributionClassesCache[lookupKey(scope, callingDeclaration)] = contributingClasses
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
    return externalContributions.getOrPut(lookupKey(scope, callingDeclaration)) {
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
    return externalBindingContainerContributions
      .getOrPut(lookupKey(scope, callingDeclaration)) {
        trace("Look up external contributions for $scope") {
          val contributingClasses =
            findVisibleContributionClassesForScopeInHints(scope, callingDeclaration)
          val finalSet = mutableScatterSetOf<IrClass>()
          // nativeContributions
          getScopedContributions(contributingClasses, scope, bindingContainersOnly = true).forEach {
            it.classOrNull
              ?.owner
              ?.takeIf { irClass -> metroDeclarations.findBindingContainer(irClass) != null }
              ?.let(finalSet::add)
          }
          if (metroContext.options.generateContributionProviders) {
            contributingClasses.forEach { irClass ->
              if (!irClass.usesGeneratedContributionProviderPath()) {
                return@forEach
              }
              val container =
                irClass.lookupContributionProviderContainer(scope, callingDeclaration)
                  ?: return@forEach
              if (metroDeclarations.findBindingContainer(container) != null) {
                finalSet.add(container)
              }
            }
          }
          // extensionContributions
          irContributionExtensions.forEach { extension ->
            for (irClass in extension.contributeBindingContainers(scope, callingDeclaration)) {
              with(metroContext) { trackClassLookup(callingDeclaration, irClass) }
              finalSet.add(irClass)
            }
          }
          finalSet
        }
      }
      .asSet()
  }

  private fun IrClass.usesGeneratedContributionProviderPath(): Boolean {
    return usesContributionProviderPath(metroContext.options, metroContext.metroSymbols.classIds)
  }

  private fun IrClass.lookupContributionProviderContainer(
    scope: Scope,
    callingDeclaration: IrDeclaration,
  ): IrClass? {
    val containerClassId = MetroContributions.containerObjectClassId(classIdOrFail, scope)
    return context(metroContext) { callingDeclaration.lookupClass(containerClassId)?.owner }
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
      contributingClasses.flatMapToSet { irClass ->
        with(metroContext) {
          if (irClass.isBindingContainer()) {
            // Top-level @BindingContainer class
            if (bindingContainersOnly) {
              setOf(irClass.defaultType)
            } else {
              emptySet()
            }
          } else {
            // Walk nested @MetroContribution classes; route by their own @BindingContainer
            // annotation so pure-binding contributions land in the binding-container bucket
            // instead of being merged in as graph supertypes.
            irClass.nestedClasses.mapNotNullToSet { nestedClass ->
              val metroContribution =
                nestedClass.findAnnotations(Symbols.ClassIds.metroContribution).singleOrNull()
                  ?: return@mapNotNullToSet null
              val contributionScope =
                metroContribution.scopeOrNull()
                  ?: reportCompilerBug("No scope found for @MetroContribution annotation")
              if (contributionScope != scope) return@mapNotNullToSet null
              val isNestedContainer = nestedClass.isBindingContainer()
              if (bindingContainersOnly == isNestedContainer) {
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
