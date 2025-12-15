// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.api.fir

import dev.zacsweers.metro.compiler.MetroOptions
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension

/**
 * Extension point for third-party code generators to plug into Metro's FIR declaration generation
 * pipeline.
 *
 * This allows extensions to generate code that can be consumed by Metro's native declaration
 * generators in the same compilation, working around the limitation that FIR declaration generators
 * normally cannot see each other's generated code.
 *
 * Extensions are loaded via [ServiceLoader][java.util.ServiceLoader] using
 * [MetroFirDeclarationGenerationExtension.Factory] implementations and processed _before_ Metro's
 * native generators, allowing generated code to be subsequently processed by Metro.
 *
 * ## Usage
 * 1. Create a class that extends [MetroFirDeclarationGenerationExtension]
 * 2. Implement a [Factory] that creates instances of your extension class
 * 3. Register your factory via ServiceLoader in
 *    `META-INF/services/dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension$Factory`
 *    or using something like AutoService.
 *
 * ## Example
 *
 * ```kotlin
 * class MyExtension(session: FirSession) : MetroFirDeclarationGenerationExtension(session) {
 *
 *   override fun FirDeclarationPredicateRegistrar.registerPredicates() {
 *     register(myCustomPredicate)
 *   }
 *
 *   override fun getNestedClassifiersNames(
 *     classSymbol: FirClassSymbol<*>,
 *     context: NestedClassGenerationContext,
 *   ): Set<Name> {
 *     // Return names of classes to generate
 *   }
 *
 *   // Implement other generation functions as needed...
 *
 *   class Factory : MetroFirDeclarationGenerationExtension.Factory {
 *     override fun create(
 *       session: FirSession,
 *       options: MetroOptions
 *     ): MetroFirDeclarationGenerationExtension = MyExtension(session)
 *   }
 * }
 * ```
 *
 * ## Ordering
 *
 * External extensions (loaded via ServiceLoader) are processed BEFORE Metro's native generators.
 * This means:
 * - For functions returning sets (like [getNestedClassifiersNames]), results from all extensions
 *   are accumulated
 * - For functions returning single values (like [generateNestedClassLikeDeclaration]), external
 *   extensions get first chance to return a non-null value
 */
public abstract class MetroFirDeclarationGenerationExtension(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  /**
   * Factory for creating [MetroFirDeclarationGenerationExtension] instances.
   *
   * Implementations should be registered via ServiceLoader in
   * `META-INF/services/dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension$Factory`
   */
  public interface Factory {
    /**
     * Create an extension instance for the given session.
     *
     * Called once per FIR session during compiler initialization.
     *
     * @param session The FIR session for this compilation
     * @param options Metro configuration options, provided for extensions that need to read Metro's
     *   configuration
     * @return A new extension instance, or null if this extension should not participate in this
     *   compilation
     */
    public fun create(
      session: FirSession,
      options: MetroOptions,
    ): MetroFirDeclarationGenerationExtension?
  }
}
