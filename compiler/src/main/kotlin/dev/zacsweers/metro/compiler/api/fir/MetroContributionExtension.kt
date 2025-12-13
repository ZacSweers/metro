// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.api.fir

import dev.zacsweers.metro.compiler.MetroOptions
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.name.ClassId

/**
 * Extension point for third-party code generators to contribute bindings to Metro's dependency
 * graph merging.
 *
 * This allows external extensions to generate classes annotated with contribution annotations
 * (like `@ContributesBinding`, `@ContributesTo`) and have Metro's native merging infrastructure
 * pick them up when constructing dependency graphs.
 *
 * Unlike [MetroFirDeclarationGenerationExtension] which plugs into FIR declaration generation,
 * this extension provides a higher-level API specifically for Metro's contribution merging. This
 * is necessary because the merging logic needs metadata (replaces, origin) that may reference
 * classes that don't exist yet during FIR processing.
 *
 * Extensions are loaded via [ServiceLoader][java.util.ServiceLoader] using [Factory]
 * implementations.
 *
 * ## Usage
 *
 * 1. Create a class that implements [MetroContributionExtension]
 * 2. Implement a [Factory] that creates instances of your extension
 * 3. Register your factory via ServiceLoader in
 *    `META-INF/services/dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension$Factory`
 *
 * ## Example
 *
 * ```kotlin
 * class MyContributionExtension(
 *   private val session: FirSession
 * ) : MetroContributionExtension {
 *
 *   override fun FirDeclarationPredicateRegistrar.registerPredicates() {
 *     register(myCustomPredicate)
 *   }
 *
 *   override fun getContributions(scopeClassId: ClassId): List<MetroContributionExtension.Contribution> {
 *     // Return contributions for the given scope
 *     return listOf(
 *       Contribution(
 *         supertype = myGeneratedType,
 *         replaces = listOf(replacedClassId),
 *         originClassId = originalClassId,
 *       )
 *     )
 *   }
 *
 *   class Factory : MetroContributionExtension.Factory {
 *     override fun create(
 *       session: FirSession,
 *       options: MetroOptions
 *     ): MetroContributionExtension = MyContributionExtension(session)
 *   }
 * }
 * ```
 */
public interface MetroContributionExtension {

  /**
   * Register predicates that this extension cares about.
   *
   * Predicates determine which source declarations trigger this extension's contribution
   * generation.
   */
  public fun FirDeclarationPredicateRegistrar.registerPredicates()

  /**
   * Returns contributions that this extension provides for the given scope.
   *
   * Called during dependency graph supertype computation to gather all contributions that should
   * be merged into graphs with the specified scope.
   *
   * @param scopeClassId The scope class ID to get contributions for
   * @return List of contributions for this scope, empty if none
   */
  public fun getContributions(scopeClassId: ClassId): List<Contribution>

  /**
   * Represents a contribution to be merged into a dependency graph.
   *
   * @property supertype The supertype to add to the graph (typically a generated
   *   `MetroContribution` interface type)
   * @property replaces Classes that this contribution replaces (for replacement merging)
   * @property originClassId The origin class this contribution came from (used for exclusion
   *   matching when the graph excludes certain classes)
   */
  public data class Contribution(
    val supertype: ConeKotlinType,
    val replaces: List<ClassId>,
    val originClassId: ClassId,
  )

  /**
   * Factory for creating [MetroContributionExtension] instances.
   *
   * Implementations should be registered via ServiceLoader in
   * `META-INF/services/dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension$Factory`
   */
  public interface Factory {
    /**
     * Create an extension instance for the given session.
     *
     * Called once per FIR session during compiler initialization.
     *
     * @param session The FIR session for this compilation
     * @param options Metro configuration options
     * @return A new extension instance, or null if this extension should not participate in this
     *   compilation
     */
    public fun create(
      session: FirSession,
      options: MetroOptions,
    ): MetroContributionExtension?
  }
}
