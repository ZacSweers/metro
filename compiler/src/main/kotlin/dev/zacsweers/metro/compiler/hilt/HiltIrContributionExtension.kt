// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.hilt

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.ir.MetroIrContributionExtension
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.ir.finderFor
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.fir.backend.Fir2IrComponents
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.ClassId

/**
 * Contributes Hilt modules and entry points to IR-only graph merging.
 *
 * The FIR contribution pipeline handles regular graph merging. This extension feeds the paths that
 * merge contributions in IR, such as `@MergeContributionsInIr` graphs, graph extensions, and
 * IR-only generated graph classes.
 *
 * `IrPluginContext` cannot enumerate package classifiers, so this uses the FIR session available
 * from `Fir2IrComponents` to read Hilt aggregated-deps markers.
 */
public class HiltIrContributionExtension(
  private val pluginContext: IrPluginContext,
  private val compatContext: CompatContext,
) : MetroIrContributionExtension {

  /**
   * Lazily resolved on first scan. Null if Hilt isn't on the classpath or the K2 IR bridge isn't
   * available (both cases mean there's nothing for us to contribute).
   */
  private val bridge: Bridge? by memoize {
    val anyHiltClass =
      with(compatContext) {
          pluginContext.finderForBuiltinsCompat().findClass(HiltSymbols.InstallIn)
        }
        ?.owner ?: return@memoize null
    val components = anyHiltClass as? Fir2IrComponents ?: return@memoize null
    val session = components.session
    Bridge(
      scanner = HiltAggregatedDepsScanner(session),
      componentScopes = HiltComponentScopeMapping(session),
    )
  }

  private class Bridge(
    val scanner: HiltAggregatedDepsScanner,
    val componentScopes: HiltComponentScopeMapping,
  ) {
    val inRoundInstallIns: List<InRoundInstallIn>
      get() = componentScopes.inRoundInstallIns
  }

  override fun contributeBindingContainers(
    scope: ClassId,
    callingDeclaration: IrDeclaration,
  ): List<IrClass> {
    val bridge = bridge ?: return emptyList()

    val result = mutableListOf<IrClass>()

    // Modules recorded by upstream Hilt processors.
    for (dep in bridge.scanner.getAllDeps()) {
      if (dep.modules.isEmpty()) continue
      if (dep.components.none { bridge.componentScopes.resolveScope(it) == scope }) continue
      for (moduleClassId in dep.modules) {
        val irClass = findClass(moduleClassId, callingDeclaration) ?: continue
        result += irClass
      }
    }

    for (installIn in bridge.inRoundInstallIns) {
      if (!installIn.isModule) continue
      if (scope !in installIn.resolvedScopes(bridge.componentScopes)) continue
      val irClass = findClass(installIn.classId, callingDeclaration) ?: continue
      result += irClass
    }

    return result
  }

  override fun contributeSupertypes(
    scope: ClassId,
    callingDeclaration: IrDeclaration,
  ): List<IrType> {
    val bridge = bridge ?: return emptyList()

    val result = mutableListOf<IrType>()

    // Entry points recorded by upstream Hilt processors.
    for (dep in bridge.scanner.getAllDeps()) {
      if (dep.entryPoints.isEmpty()) continue
      if (dep.components.none { bridge.componentScopes.resolveScope(it) == scope }) continue
      for (entryPointClassId in dep.entryPoints) {
        val irClass = findClass(entryPointClassId, callingDeclaration) ?: continue
        result += irClass.defaultType
      }
    }

    // Entry points declared in this compilation.
    for (installIn in bridge.inRoundInstallIns) {
      if (!installIn.isEntryPoint) continue
      if (scope !in installIn.resolvedScopes(bridge.componentScopes)) continue
      val irClass = findClass(installIn.classId, callingDeclaration) ?: continue
      result += irClass.defaultType
    }

    return result
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
      if (!options.enableHiltInterop) return null
      return HiltIrContributionExtension(pluginContext, compatContext)
    }
  }
}
