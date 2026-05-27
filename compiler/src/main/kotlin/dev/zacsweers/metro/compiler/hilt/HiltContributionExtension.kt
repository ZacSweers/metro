// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.hilt

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
 * FIR-side Hilt interop. Surfaces Hilt `@InstallIn @EntryPoint` interfaces as graph supertypes for
 * FIR-merged graphs. Both classpath `@AggregatedDeps` markers (via [HiltAggregatedDepsScanner]) and
 * in-round source `@InstallIn @EntryPoint` classes (via [findInRoundInstallIns]) are included.
 *
 * Hilt `@InstallIn @Module` interop is handled separately:
 * - In-round modules: emitted as contribution hints by [HiltFirDeclarationExtension] and routed
 *   through Metro's existing hint pipeline.
 * - Compiled modules: discovered on the IR side by [HiltIrContributionExtension] (the FIR-merged
 *   path doesn't process binding containers).
 *
 * The `@InstallIn` predicate is registered once by [HiltFirDeclarationExtension]; this extension's
 * [registerPredicates] is a no-op.
 */
public class HiltContributionExtension(
  private val session: FirSession,
  compatContext: CompatContext,
) : MetroContributionExtension, CompatContext by compatContext {

  private val scanner by memoize { HiltAggregatedDepsScanner(session) }
  private val componentScopes by memoize { HiltComponentScopeMapping(session) }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    // The `@InstallIn` predicate is registered by [HiltFirDeclarationExtension]; predicates are
    // session-global so we can query it here without re-registering.
  }

  override fun getContributions(
    scopeClassId: ClassId,
    typeResolverFactory: MetroFirTypeResolver.Factory,
  ): List<MetroContributionExtension.Contribution> {
    val contributions = mutableListOf<MetroContributionExtension.Contribution>()

    // Classpath `@AggregatedDeps` entry points.
    for (dep in scanner.deps()) {
      if (dep.entryPoints.isEmpty()) continue
      if (dep.components.none { componentScopes.resolveScope(it) == scopeClassId }) continue
      for (entryPointClassId in dep.entryPoints) {
        contributions += contributionFor(entryPointClassId)
      }
    }

    // In-round source `@InstallIn @EntryPoint` interfaces.
    for (installIn in findInRoundInstallIns(session, typeResolverFactory)) {
      if (!installIn.isEntryPoint) continue
      if (scopeClassId !in installIn.resolvedScopes(componentScopes)) continue
      contributions += contributionFor(installIn.classId)
    }

    return contributions
  }

  private fun contributionFor(entryPointClassId: ClassId): MetroContributionExtension.Contribution =
    MetroContributionExtension.Contribution(
      supertype = entryPointClassId.constructClassLikeType(emptyArray()),
      replaces = emptyList(),
      originClassId = entryPointClassId,
    )

  public class Factory : MetroContributionExtension.Factory {
    override fun create(
      session: FirSession,
      options: MetroOptions,
      compatContext: CompatContext,
    ): MetroContributionExtension? {
      if (!options.enableHiltInterop) return null
      return HiltContributionExtension(session, compatContext)
    }
  }
}
