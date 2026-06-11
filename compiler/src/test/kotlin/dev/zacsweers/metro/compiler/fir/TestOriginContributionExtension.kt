// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension
import dev.zacsweers.metro.compiler.asName
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

internal class TestOriginContributionExtension(private val session: FirSession) :
  MetroContributionExtension {

  private val externalContributionClassId = ClassId(FqName("test"), "ExternalContribution".asName())
  private val appScopeClassId = ClassId(FqName("dev.zacsweers.metro"), "AppScope".asName())
  private val predicate = annotated(externalContributionClassId.asSingleFqName())

  private val annotatedClasses by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(predicate)
      .filterIsInstance<FirRegularClassSymbol>()
      .toList()
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(predicate)
  }

  override fun getContributions(
    scopeClassId: ClassId,
    typeResolverFactory: MetroFirTypeResolver.Factory,
  ): List<MetroContributionExtension.Contribution> {
    if (scopeClassId != appScopeClassId) return emptyList()
    return annotatedClasses.map { classSymbol ->
      MetroContributionExtension.Contribution(
        supertype = classSymbol.defaultType(),
        replaces = emptyList(),
        originClassId = classSymbol.classId,
      )
    }
  }
}
