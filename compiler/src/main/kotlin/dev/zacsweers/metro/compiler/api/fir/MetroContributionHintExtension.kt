// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.api.fir

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.name.ClassId

/**
 * Extension point for contribution hint providers.
 *
 * Contribution hints are top-level FIR functions used by downstream modules to discover contributed
 * classes. They stay in FIR even when Metro generates hidden classes in IR because the current hint
 * lookup model depends on package-level callable discovery.
 */
public interface MetroContributionHintExtension {

  /** Register predicates needed to compute [getContributionHints]. */
  public fun FirDeclarationPredicateRegistrar.registerPredicates() {}

  /**
   * Returns contribution hints for classes generated or discovered by this extension.
   *
   * @return List of contribution hints, empty by default.
   */
  public fun getContributionHints(): List<ContributionHint> = emptyList()

  /**
   * Represents a contribution hint for a generated class that should be discoverable across module
   * boundaries.
   *
   * @property contributingClassId The class ID of the generated contributing class.
   * @property scope The scope class ID this contribution targets.
   */
  public data class ContributionHint(val contributingClassId: ClassId, val scope: ClassId)

  /**
   * Factory for creating [MetroContributionHintExtension] instances.
   *
   * Implementations should be registered via ServiceLoader in
   * `META-INF/services/dev.zacsweers.metro.compiler.api.fir.MetroContributionHintExtension$Factory`.
   */
  public interface Factory {
    public fun create(
      session: FirSession,
      options: MetroOptions,
      compatContext: CompatContext,
    ): MetroContributionHintExtension?
  }
}
