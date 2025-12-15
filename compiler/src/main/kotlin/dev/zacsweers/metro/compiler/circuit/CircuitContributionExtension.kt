// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension
import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.fir.annotationsIn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Contribution extension that provides metadata for Circuit-generated factories to Metro's
 * dependency graph merging.
 */
public class CircuitContributionExtension(private val session: FirSession) :
  MetroContributionExtension {

  private val symbols by lazy { CircuitSymbols.Fir(session) }

  private val annotatedSymbols by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(CircuitSymbols.circuitInjectPredicate)
      .toList()
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
    scopeClassId: ClassId
  ): List<MetroContributionExtension.Contribution> {
    val contributions = mutableListOf<MetroContributionExtension.Contribution>()

    // Process annotated classes
    for (classSymbol in annotatedClasses) {
      val contribution = computeContributionForClass(classSymbol, scopeClassId)
      if (contribution != null) {
        contributions.add(contribution)
      }
    }

    // Process annotated functions
    for (function in annotatedFunctions) {
      val contribution = computeContributionForFunction(function, scopeClassId)
      if (contribution != null) {
        contributions.add(contribution)
      }
    }

    return contributions
  }

  private fun computeContributionForClass(
    classSymbol: FirRegularClassSymbol,
    requestedScopeClassId: ClassId,
  ): MetroContributionExtension.Contribution? {
    val annotation =
      classSymbol.annotationsIn(session, setOf(CircuitClassIds.CircuitInject)).firstOrNull()
        ?: return null

    val scopeClassId = extractScopeClassId(annotation) ?: return null

    // Only return contribution if scope matches
    if (scopeClassId != requestedScopeClassId) return null

    // Check if this is a valid UI or Presenter type
    // TODO checker
    //    val isValidType = symbols.isUiType(classSymbol) || symbols.isPresenterType(classSymbol)
    //    if (!isValidType) return null

    val factoryClassId = classSymbol.classId.createNestedClassId(CircuitNames.Factory)

    // Use MetroContributions API to compute the MetroContribution ClassId
    val metroContributionClassId =
      MetroContributions.metroContributionClassId(factoryClassId, scopeClassId)

    val metroContributionSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(metroContributionClassId) ?: return null

    return MetroContributionExtension.Contribution(
      supertype = metroContributionSymbol.defaultType(),
      replaces = emptyList(),
      originClassId = factoryClassId,
    )
  }

  private fun computeContributionForFunction(
    function: FirNamedFunctionSymbol,
    requestedScopeClassId: ClassId,
  ): MetroContributionExtension.Contribution? {
    val annotation =
      function.annotationsIn(session, setOf(CircuitClassIds.CircuitInject)).firstOrNull()
        ?: return null

    val scopeClassId = extractScopeClassId(annotation) ?: return null

    // Only return contribution if scope matches
    if (scopeClassId != requestedScopeClassId) return null

    val functionName = function.name.asString()
    val factoryClassId =
      ClassId(
        function.callableId.packageName,
        Name.identifier("${functionName.capitalizeUS()}Factory"),
      )

    // Use MetroContributions API to compute the MetroContribution ClassId
    val metroContributionClassId =
      MetroContributions.metroContributionClassId(factoryClassId, scopeClassId)

    val metroContributionSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(metroContributionClassId)
        as? FirRegularClassSymbol ?: return null

    return MetroContributionExtension.Contribution(
      supertype = metroContributionSymbol.defaultType(),
      replaces = emptyList(),
      originClassId = factoryClassId,
    )
  }

  private fun extractScopeClassId(annotation: FirAnnotation): ClassId? {
    val mapping = annotation.argumentMapping.mapping
    if (mapping.size < 2) return null

    // Second arg is scope
    val scopeArg = mapping.values.elementAtOrNull(1) as? FirGetClassCall ?: return null
    return scopeArg.getTargetType().classId
  }

  private fun FirGetClassCall.getTargetType(): ConeKotlinType {
    return argument.resolvedType
  }

  public class Factory : MetroContributionExtension.Factory {
    override fun create(session: FirSession, options: MetroOptions): MetroContributionExtension? {
      if (!options.enableCircuitCodegen) return null
      return CircuitContributionExtension(session)
    }
  }
}
