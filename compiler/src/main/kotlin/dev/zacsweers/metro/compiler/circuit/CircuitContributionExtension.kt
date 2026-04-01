// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension
import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.allSessions
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classArgument
import dev.zacsweers.metro.compiler.fir.resolveClassId
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Contribution extension that provides metadata for Circuit-generated factories to Metro's
 * dependency graph merging.
 */
public class CircuitContributionExtension(
  private val session: FirSession,
  compatContext: CompatContext,
) : MetroContributionExtension, CompatContext by compatContext {

  private val annotatedSymbols by lazy {
    session.allSessions.flatMap {
      it.predicateBasedProvider.getSymbolsByPredicate(CircuitSymbols.circuitInjectPredicate)
    }
  }

  private val annotatedClasses by lazy {
    annotatedSymbols.filterIsInstance<FirRegularClassSymbol>().toList()
  }

  private val annotatedFunctions by lazy {
    annotatedSymbols
      .filterIsInstance<FirNamedFunctionSymbol>()
      .filter { it.callableId.classId == null } // Only top-level functions
      .toList()
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(CircuitSymbols.circuitInjectPredicate)
  }

  override fun getContributions(
    scopeClassId: ClassId,
    typeResolverFactory: MetroFirTypeResolver.Factory,
  ): List<MetroContributionExtension.Contribution> {
    val contributions = mutableListOf<MetroContributionExtension.Contribution>()

    // Process annotated classes
    for (classSymbol in annotatedClasses) {
      val typeResolver = typeResolverFactory.create(classSymbol) ?: continue
      val contribution = computeContributionForClass(classSymbol, scopeClassId, typeResolver)
      if (contribution != null) {
        contributions.add(contribution)
      }
    }

    // Process annotated functions
    for (function in annotatedFunctions) {
      val typeResolver = typeResolverFactory.create(function) ?: continue
      val contribution = computeContributionForFunction(function, scopeClassId, typeResolver)
      if (contribution != null) {
        contributions.add(contribution)
      }
    }

    return contributions
  }

  private fun computeContributionForClass(
    classSymbol: FirRegularClassSymbol,
    requestedScopeClassId: ClassId,
    typeResolver: MetroFirTypeResolver,
  ): MetroContributionExtension.Contribution? {
    val annotation =
      classSymbol.annotationsIn(session, setOf(CircuitClassIds.CircuitInject)).firstOrNull()
        ?: return null

    val scopeClassId = extractScopeClassId(annotation, typeResolver) ?: return null

    // Only return contribution if scope matches
    if (scopeClassId != requestedScopeClassId) return null

    // Check if this is a valid UI or Presenter type
    // TODO checker
    //    val isValidType = symbols.isUiType(classSymbol) || symbols.isPresenterType(classSymbol)
    //    if (!isValidType) return null

    val factoryClassId = classSymbol.classId.createNestedClassId(CircuitNames.Factory)

    // Compute the MetroContribution ClassId and construct the type directly.
    // We can't use symbolProvider here because the MetroContribution class is itself generated
    // (by ContributionsFirGenerator) inside the circuit-generated factory class. During supertype
    // resolution, the symbol may not be resolvable yet due to the two-level generation chain.
    val metroContributionClassId =
      MetroContributions.metroContributionClassId(factoryClassId, scopeClassId)

    return MetroContributionExtension.Contribution(
      supertype = metroContributionClassId.constructClassLikeType(emptyArray()),
      replaces = emptyList(),
      originClassId = factoryClassId,
    )
  }

  private fun computeContributionForFunction(
    function: FirNamedFunctionSymbol,
    requestedScopeClassId: ClassId,
    typeResolver: MetroFirTypeResolver,
  ): MetroContributionExtension.Contribution? {
    val annotation =
      function.annotationsIn(session, setOf(CircuitClassIds.CircuitInject)).firstOrNull()
        ?: return null

    val scopeClassId = extractScopeClassId(annotation, typeResolver) ?: return null

    // Only return contribution if scope matches
    if (scopeClassId != requestedScopeClassId) return null

    val functionName = function.name.asString()
    val factoryClassId =
      ClassId(
        function.callableId.packageName,
        Name.identifier("${functionName.capitalizeUS()}Factory"),
      )

    // Construct the type directly (see comment in computeContributionForClass)
    val metroContributionClassId =
      MetroContributions.metroContributionClassId(factoryClassId, scopeClassId)

    return MetroContributionExtension.Contribution(
      supertype = metroContributionClassId.constructClassLikeType(emptyArray()),
      replaces = emptyList(),
      originClassId = factoryClassId,
    )
  }

  private fun extractScopeClassId(
    annotation: FirAnnotation,
    typeResolver: MetroFirTypeResolver,
  ): ClassId? {
    if (annotation !is FirAnnotationCall) return null
    // Second arg is scope
    return annotation
      .classArgument(session, Symbols.Names.scope, index = 1)
      ?.resolveClassId(typeResolver)
  }

  public class Factory : MetroContributionExtension.Factory {
    override fun create(
      session: FirSession,
      options: MetroOptions,
      compatContext: CompatContext,
    ): MetroContributionExtension? {
      if (!options.enableCircuitCodegen) return null
      return CircuitContributionExtension(session, compatContext)
    }
  }
}
