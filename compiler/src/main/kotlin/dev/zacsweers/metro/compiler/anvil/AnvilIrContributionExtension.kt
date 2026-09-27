// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.anvil

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.ir.MetroIrContributionExtension
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.ir.finderFor
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.fir.backend.Fir2IrComponents
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.ClassId

/**
 * Contributes classes from Anvil's classpath hints to IR graph merging.
 *
 * Modules become binding containers for every graph. Interfaces feed the paths that merge
 * supertypes in IR, such as `@MergeContributionsInIr` graphs and graph extensions.
 *
 * `IrPluginContext` can't list a package's declarations. This reads hints through the FIR session
 * that `Fir2IrComponents` exposes.
 */
public class AnvilIrContributionExtension(
  private val pluginContext: IrPluginContext,
  private val compatContext: CompatContext,
) : MetroIrContributionExtension {

  /**
   * Metro's runtime is always on the classpath, so its classes reach the FIR session. This is null
   * when the K2 IR bridge is unavailable.
   */
  private val scanner: AnvilHintScanner? by memoize {
    val runtimeClass =
      with(compatContext) {
          pluginContext.finderForBuiltinsCompat().findClass(Symbols.ClassIds.metroOrigin)
        }
        ?.owner ?: return@memoize null
    val components = runtimeClass as? Fir2IrComponents ?: return@memoize null
    AnvilHintScanner(components.session)
  }

  override fun contributeBindingContainers(
    scope: ClassId,
    callingDeclaration: IrDeclaration,
  ): List<IrClass> {
    return contributedClasses(scope, callingDeclaration, bindingContainers = true)
  }

  override fun contributeSupertypes(
    scope: ClassId,
    callingDeclaration: IrDeclaration,
  ): List<IrType> {
    return contributedClasses(scope, callingDeclaration, bindingContainers = false).map {
      it.defaultType
    }
  }

  private fun contributedClasses(
    scope: ClassId,
    callingDeclaration: IrDeclaration,
    bindingContainers: Boolean,
  ): List<IrClass> {
    val scanner = scanner ?: return emptyList()
    return scanner
      .contributions(scope)
      .filter { it.isBindingContainer == bindingContainers }
      .mapNotNull { findClass(it.classId, callingDeclaration) }
  }

  private fun findClass(classId: ClassId, callingDeclaration: IrDeclaration): IrClass? {
    return with(compatContext) {
        pluginContext.finderFor(callingDeclaration).findClass(classId)
      }
      ?.owner
  }

  public class Factory : MetroIrContributionExtension.Factory {
    override fun create(
      pluginContext: IrPluginContext,
      compatContext: CompatContext,
      options: MetroOptions,
    ): MetroIrContributionExtension? {
      if (!options.enableDaggerAnvilInterop) {
        return null
      }
      return AnvilIrContributionExtension(pluginContext, compatContext)
    }
  }
}
