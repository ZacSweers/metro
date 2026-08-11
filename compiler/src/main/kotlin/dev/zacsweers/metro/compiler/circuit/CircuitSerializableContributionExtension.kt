// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension
import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.allSessions
import dev.zacsweers.metro.compiler.fir.annotationsIn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.ClassId

/** Provides Circuit serializer registration metadata to Metro's graph merging. */
public class CircuitSerializableContributionExtension(private val session: FirSession) :
  MetroContributionExtension {

  private val annotatedSerializableClasses by lazy {
    session.allSessions.flatMap { it.findCircuitSerializableClasses() }
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(CircuitSymbols.circuitSerializablePredicate)
  }

  override fun getContributions(
    scopeClassId: ClassId,
    typeResolverFactory: MetroFirTypeResolver.Factory,
  ): List<MetroContributionExtension.Contribution> {
    return annotatedSerializableClasses.mapNotNull { serializedType ->
      val typeResolver = typeResolverFactory.create(serializedType) ?: return@mapNotNull null
      computeContribution(serializedType, scopeClassId, typeResolver)
    }
  }

  private fun computeContribution(
    serializedType: FirRegularClassSymbol,
    requestedScopeClassId: ClassId,
    typeResolver: MetroFirTypeResolver,
  ): MetroContributionExtension.Contribution? {
    val annotation =
      serializedType
        .annotationsIn(session, setOf(CircuitClassIds.CircuitSerializable))
        .firstOrNull() ?: return null
    val scopeClassId =
      annotation.extractCircuitScopeClassId(session, typeResolver, argumentIndex = 0) ?: return null
    if (scopeClassId != requestedScopeClassId) return null

    val registrationClassId = serializedType.classId.circuitSerializerRegistrationClassId()
    val metroContributionClassId =
      MetroContributions.metroContributionClassId(registrationClassId, scopeClassId)
    return MetroContributionExtension.Contribution(
      supertype = metroContributionClassId.constructClassLikeType(emptyArray()),
      replaces = emptyList(),
      originClassId = registrationClassId,
    )
  }

  public class Factory : MetroContributionExtension.Factory {
    override fun create(
      session: FirSession,
      options: MetroOptions,
      compatContext: CompatContext,
    ): MetroContributionExtension? {
      if (!options.enableCircuitCodegen || options.generateClassesInIr) return null
      return CircuitSerializableContributionExtension(session)
    }
  }
}
