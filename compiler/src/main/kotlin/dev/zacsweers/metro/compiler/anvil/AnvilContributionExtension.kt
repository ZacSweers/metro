// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.anvil

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.ClassId

/**
 * Contributes interfaces from Anvil's classpath hints as FIR graph supertypes.
 *
 * Modules from the same hints are binding containers. [AnvilIrContributionExtension] contributes
 * those in IR.
 */
public class AnvilContributionExtension(session: FirSession) : MetroContributionExtension {

  private val scanner by memoize { session.anvilHintScanner }

  // Hints only come from the classpath, so there are no source predicates to register.
  override fun FirDeclarationPredicateRegistrar.registerPredicates() {}

  override fun getContributions(
    scopeClassId: ClassId,
    typeResolverFactory: MetroFirTypeResolver.Factory,
  ): List<MetroContributionExtension.Contribution> {
    return scanner.contributions(scopeClassId).mapNotNull { contribution ->
      if (contribution.isBindingContainer) {
        return@mapNotNull null
      }
      MetroContributionExtension.Contribution(
        supertype = contribution.classId.constructClassLikeType(emptyArray()),
        replaces = contribution.replaces.toList(),
        originClassId = contribution.originClassId,
      )
    }
  }

  public class Factory : MetroContributionExtension.Factory {
    override fun create(
      session: FirSession,
      options: MetroOptions,
      compatContext: CompatContext,
    ): MetroContributionExtension? {
      if (!options.enableDaggerAnvilInterop) {
        return null
      }
      return AnvilContributionExtension(session)
    }
  }
}
