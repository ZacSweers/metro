// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.api.ir

import dev.zacsweers.metro.compiler.MetroOptions
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.ClassId

/**
 * IR-side counterpart to
 * [MetroContributionExtension][dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension].
 *
 * Use this when an external framework needs to contribute bindings to Metro's graph at IR time -
 * typically because the contributing classes live on the compile classpath and cannot be discovered
 * through Metro's hint-function mechanism, which only sees in-compilation declarations.
 *
 * Extensions are loaded via [ServiceLoader][java.util.ServiceLoader] using [Factory]
 * implementations.
 *
 * ## Usage
 * 1. Create a class that implements [MetroIrContributionExtension]
 * 2. Implement a [Factory] that creates instances of your extension
 * 3. Register your factory via ServiceLoader in
 *    `META-INF/services/dev.zacsweers.metro.compiler.api.ir.MetroIrContributionExtension$Factory`
 */
public interface MetroIrContributionExtension {

  /**
   * Returns binding container classes to merge into a graph for the given scope. The returned
   * classes are treated like `@BindingContainer`s: their `@Provides` members are pulled into the
   * graph but the classes are not added as supertypes.
   *
   * The default implementation returns an empty list.
   */
  public fun contributeBindingContainers(
    scope: ClassId,
    callingDeclaration: IrDeclaration,
  ): List<IrClass> = emptyList()

  /**
   * Returns supertype contributions for the given scope. These are merged into the graph's
   * supertype list at IR time, analogous to FIR-side
   * [MetroContributionExtension.Contribution][dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension.Contribution]
   * supertypes.
   *
   * The default implementation returns an empty list.
   */
  public fun contributeSupertypes(scope: ClassId, callingDeclaration: IrDeclaration): List<IrType> =
    emptyList()

  /**
   * Factory for creating [MetroIrContributionExtension] instances.
   *
   * Implementations should be registered via ServiceLoader in
   * `META-INF/services/dev.zacsweers.metro.compiler.api.ir.MetroIrContributionExtension$Factory`
   */
  public interface Factory {
    /**
     * Create an extension instance for the given plugin context.
     *
     * Called once per IR generation. Return null to opt out of this compilation.
     */
    public fun create(
      pluginContext: IrPluginContext,
      options: MetroOptions,
    ): MetroIrContributionExtension?
  }
}
