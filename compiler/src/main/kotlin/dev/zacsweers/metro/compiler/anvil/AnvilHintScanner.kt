// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.anvil

import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classArgument
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.replacesArgument
import dev.zacsweers.metro.compiler.fir.resolveClassId
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import org.jetbrains.kotlin.descriptors.isInterface
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
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
internal class AnvilHintScanner(private val session: FirSession) {

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

  /**
   * Hinted classes resolve from the classpath, so they're safe to read in any FIR phase. Computing
   * every scope up front keeps this immutable across the IDE's resolve threads.
   */
  private val contributionsByScope: Map<ClassId, List<Contribution>> by lazy {
    readHints().mapValues { (scope, classIds) ->
      classIds.mapNotNull { contribution(it, scope) }
    }
  }

  fun contributions(scope: ClassId): List<Contribution> = contributionsByScope[scope].orEmpty()

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
   * A hint only names a scope. The class's own `@ContributesTo` confirms that scope and carries its
   * replacements. Only interfaces and binding containers can be merged.
   */
  private fun contribution(classId: ClassId, scope: ClassId): Contribution? {
    val symbol =
      session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol
        ?: return null
    val contributesTo =
      symbol
        .annotationsIn(session, session.classIds.contributesToAnnotationsWithContainers)
        .filter { it.resolvedScopeClassId(session) == scope }
        .toList()
    if (contributesTo.isEmpty()) {
      return subcomponentFactoryContribution(symbol, scope)
    }
    val isBindingContainer =
      symbol.annotationsIn(session, session.classIds.bindingContainerAnnotations).any()
    if (!isBindingContainer && !symbol.classKind.isInterface) {
      return null
    }
    val replaces = contributesTo.flatMapTo(mutableSetOf()) { it.replacedClassIds() }
    return Contribution(classId, classId, isBindingContainer, replaces)
  }

  /**
   * Returns the factory of a subcomponent contributed to [parentScope]. Subcomponents without a
   * factory are skipped. Anvil exposes those through a parent component interface that it generates
   * where the parent is merged.
   */
  private fun subcomponentFactoryContribution(
    symbol: FirRegularClassSymbol,
    parentScope: ClassId,
  ): Contribution? {
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
    val factory = symbol.nestedSubcomponentFactory() ?: return null
    return Contribution(
      classId = factory.classId,
      originClassId = symbol.classId,
      isBindingContainer = false,
      replaces = emptySet(),
    )
  }

  private fun FirRegularClassSymbol.nestedSubcomponentFactory(): FirRegularClassSymbol? {
    val memberScope = declaredMemberScope(session, memberRequiredPhase = null)
    for (name in memberScope.getClassifierNames()) {
      val nested = memberScope.getSingleClassifier(name) as? FirRegularClassSymbol ?: continue
      val factoryAnnotations = setOf(AnvilSymbols.ContributesSubcomponentFactory)
      if (nested.annotationsIn(session, factoryAnnotations).any()) {
        return nested
      }
    }
    return null
  }

  private fun FirAnnotation.replacedClassIds(): List<ClassId> {
    val replaced = replacesArgument(session)?.argumentList?.arguments.orEmpty()
    return replaced.mapNotNull { it.expectAsOrNull<FirGetClassCall>()?.resolveClassId(session) }
  }
}
