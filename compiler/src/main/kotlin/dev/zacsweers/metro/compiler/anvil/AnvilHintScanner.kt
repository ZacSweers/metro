// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.anvil

import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classArgument
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.replacesArgument
import dev.zacsweers.metro.compiler.fir.resolveClassId
import dev.zacsweers.metro.compiler.fir.resolvedExcludedClassIds
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import org.jetbrains.kotlin.descriptors.isInterface
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.getSingleClassifier
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Reads compiled Anvil contribution hints from [AnvilSymbols.hintPackage].
 *
 * Anvil writes a `_reference` property for each contributed class and one `_scope` property for
 * each of its scopes. The property types name the class and the scopes. These hints only exist for
 * modules that Anvil compiled. Metro-compiled modules use Metro's own hints.
 *
 * Anvil turns `@ContributesBinding` and `@ContributesMultibinding` into generated `@ContributesTo`
 * modules, so those arrive here as binding containers.
 *
 * A `@ContributesSubcomponent` is hinted under its parent scope. Its nested factory merges into the
 * parent graph. Metro merges a graph extension factory annotated with `@ContributesTo` the same
 * way.
 */
internal class AnvilHintScanner(session: FirSession) : FirExtensionSessionComponent(session) {

  /** A class Anvil contributed to one scope. */
  data class Contribution(
    /** The class to merge. For a contributed subcomponent, this is its factory. */
    val classId: ClassId,
    /** The hinted class. Exclusions and replacements name this class. */
    val originClassId: ClassId,
    /** Modules merge as binding containers. Everything else merges as a graph supertype. */
    val isBindingContainer: Boolean,
    val replaces: Set<ClassId>,
  )

  /** A `@ContributesSubcomponent` that Anvil contributed to its parent scope. */
  data class Subcomponent(
    val classId: ClassId,
    /** The subcomponent's own scope. */
    val scope: ClassId?,
    /** Classes the subcomponent excludes from its own scope. */
    val excludes: Set<ClassId>,
    /** The nested `@ContributesSubcomponent.Factory`, which merges into the parent graph. */
    val factory: ClassId?,
    /** Whether a nested interface annotated with `@ContributesTo(parentScope)` exposes it. */
    val hasParentComponent: Boolean,
  ) {
    /**
     * Anvil generates an accessor for a subcomponent with neither a factory nor a parent component.
     * Metro can't, so nothing in the parent graph reaches it.
     */
    val isReachable: Boolean
      get() = factory != null || hasParentComponent
  }

  private class Hints(
    val contributions: Map<ClassId, List<Contribution>>,
    val subcomponents: Map<ClassId, List<Subcomponent>>,
  )

  /**
   * Hinted classes resolve from the classpath, so they're safe to read in any FIR phase. Computing
   * every scope up front keeps this immutable across the IDE's resolve threads.
   */
  private val hints: Hints by lazy { readContributions() }

  fun contributions(scope: ClassId): List<Contribution> = hints.contributions[scope].orEmpty()

  /** Returns the subcomponents contributed to [parentScope], including ones Metro can't reach. */
  fun subcomponents(parentScope: ClassId): List<Subcomponent> =
    hints.subcomponents[parentScope].orEmpty()

  private fun readContributions(): Hints {
    val contributions = mutableMapOf<ClassId, MutableList<Contribution>>()
    val subcomponents = mutableMapOf<ClassId, MutableList<Subcomponent>>()
    for ((scope, classIds) in readHints()) {
      for (classId in classIds) {
        val symbol =
          session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
            ?: continue
        // A hint only names a scope. The class's own @ContributesTo confirms that scope.
        val contributesTo =
          symbol
            .annotationsIn(session, session.classIds.contributesToAnnotationsWithContainers)
            .filter { it.resolvedScopeClassId(session) == scope }
            .toList()
        if (contributesTo.isNotEmpty()) {
          val contribution = contribution(symbol, contributesTo) ?: continue
          contributions.getOrPut(scope, ::mutableListOf) += contribution
          continue
        }
        val subcomponent = subcomponent(symbol, scope) ?: continue
        subcomponents.getOrPut(scope, ::mutableListOf) += subcomponent
        val factory = subcomponent.factory ?: continue
        contributions.getOrPut(scope, ::mutableListOf) +=
          Contribution(
            classId = factory,
            originClassId = subcomponent.classId,
            isBindingContainer = false,
            replaces = emptySet(),
          )
      }
    }
    return Hints(contributions, subcomponents)
  }

  /** Returns hinted classes by scope. Hints are grouped by the name they share before a suffix. */
  private fun readHints(): Map<ClassId, Set<ClassId>> {
    val names =
      session.symbolProvider.symbolNamesProvider
        .getTopLevelCallableNamesInPackage(AnvilSymbols.hintPackage)
        .orEmpty()
    val referencedClasses = mutableMapOf<String, ClassId>()
    val scopesByName = mutableMapOf<String, MutableSet<ClassId>>()
    for (name in names) {
      val propertyName = name.asString()
      val typeArgument = kClassArgument(name) ?: continue
      if (propertyName.endsWith(AnvilSymbols.REFERENCE_SUFFIX)) {
        referencedClasses[propertyName.removeSuffix(AnvilSymbols.REFERENCE_SUFFIX)] = typeArgument
        continue
      }
      val scopeSuffix = AnvilSymbols.scopePropertySuffix.find(propertyName) ?: continue
      val sharedName = propertyName.substring(0, scopeSuffix.range.first)
      scopesByName.getOrPut(sharedName, ::mutableSetOf) += typeArgument
    }

    val result = mutableMapOf<ClassId, MutableSet<ClassId>>()
    for ((sharedName, classId) in referencedClasses) {
      for (scope in scopesByName[sharedName].orEmpty()) {
        result.getOrPut(scope, ::mutableSetOf) += classId
      }
    }
    return result
  }

  /** Reads `T` from a hint property typed `KClass<T>`. */
  private fun kClassArgument(name: Name): ClassId? {
    val property =
      session.symbolProvider
        .getTopLevelPropertySymbols(AnvilSymbols.hintPackage, name)
        .firstOrNull() ?: return null
    return property.resolvedReturnType.typeArguments.firstOrNull()?.type?.classId
  }

  /**
   * Only interfaces and binding containers can be merged. The class's [contributesTo] annotations
   * carry its replacements.
   */
  private fun contribution(
    symbol: FirRegularClassSymbol,
    contributesTo: List<FirAnnotation>,
  ): Contribution? {
    val isBindingContainer =
      symbol.annotationsIn(session, session.classIds.bindingContainerAnnotations).any()
    if (!isBindingContainer && !symbol.classKind.isInterface) {
      return null
    }
    val replaces = contributesTo.flatMapTo(mutableSetOf()) { it.replacedClassIds() }
    return Contribution(symbol.classId, symbol.classId, isBindingContainer, replaces)
  }

  /** Reads a subcomponent that [symbol] contributes to [parentScope]. */
  private fun subcomponent(symbol: FirRegularClassSymbol, parentScope: ClassId): Subcomponent? {
    val contributesSubcomponent =
      symbol.annotationsIn(session, setOf(AnvilSymbols.ContributesSubcomponent)).firstOrNull()
        ?: return null
    val declaredParentScope =
      contributesSubcomponent
        .classArgument(session, AnvilSymbols.parentScope, index = 1)
        ?.resolveClassId(session)
    if (declaredParentScope != parentScope) {
      return null
    }
    return Subcomponent(
      classId = symbol.classId,
      scope = contributesSubcomponent.resolvedScopeClassId(session),
      excludes = contributesSubcomponent.resolvedExcludedClassIds(session),
      factory = symbol.nestedSubcomponentFactory()?.classId,
      hasParentComponent = symbol.hasParentComponent(parentScope),
    )
  }

  private fun FirRegularClassSymbol.nestedSubcomponentFactory(): FirRegularClassSymbol? {
    val factoryAnnotations = setOf(AnvilSymbols.ContributesSubcomponentFactory)
    return nestedClasses().firstOrNull { it.annotationsIn(session, factoryAnnotations).any() }
  }

  /**
   * Anvil's parent component is a nested interface annotated with `@ContributesTo(parentScope)`.
   */
  private fun FirRegularClassSymbol.hasParentComponent(parentScope: ClassId): Boolean {
    val contributesToAnnotations = session.classIds.contributesToAnnotationsWithContainers
    return nestedClasses().any { nested ->
      val contributesToParent =
        nested.annotationsIn(session, contributesToAnnotations).any {
          it.resolvedScopeClassId(session) == parentScope
        }
      nested.classKind.isInterface && contributesToParent
    }
  }

  private fun FirRegularClassSymbol.nestedClasses(): Sequence<FirRegularClassSymbol> {
    val memberScope = declaredMemberScope(session, memberRequiredPhase = null)
    return memberScope.getClassifierNames().asSequence().mapNotNull {
      memberScope.getSingleClassifier(it) as? FirRegularClassSymbol
    }
  }

  private fun FirAnnotation.replacedClassIds(): List<ClassId> {
    val replaced = replacesArgument(session)?.argumentList?.arguments.orEmpty()
    return replaced.mapNotNull { it.expectAsOrNull<FirGetClassCall>()?.resolveClassId(session) }
  }

  companion object {
    fun getFactory(): Factory = Factory { session -> AnvilHintScanner(session) }
  }
}

/** The shared hint scanner. Only registered when Anvil interop is enabled. */
internal val FirSession.anvilHintScanner: AnvilHintScanner by FirSession.sessionComponentAccessor()
