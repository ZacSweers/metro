// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.hilt

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.ir.MetroIrContributionExtension
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.Fir2IrComponents
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.ClassId

/**
 * IR-side Hilt interop.
 *
 * - [contributeBindingContainers]: surfaces Hilt `@InstallIn @Module` classes for both classpath
 *   `@AggregatedDeps` markers and in-round source classes. We always route in-round modules here
 *   too because the FIR-side hint emission ([HiltFirDeclarationExtension.getContributionHints])
 *   only runs when `generateContributionHintsInFir` is enabled. Going through this method directly
 *   makes Hilt interop work regardless of that flag.
 * - [contributeSupertypes]: surfaces Hilt `@InstallIn @EntryPoint` interfaces (both classpath
 *   `@AggregatedDeps` markers and in-round source classes) for the IR-only graph path
 *   (`@MergeContributionsInIr` graphs and `@GraphExtension`s).
 *
 * `IrPluginContext` has no public API to enumerate a package, so we obtain the FIR session via the
 * `Fir2IrComponents` bridge that every classpath `IrClass` implements (see
 * `IrRankedBindingProcessing.kt` for the same pattern). With the FIR session we can call back into
 * [HiltAggregatedDepsScanner] and [findInRoundInstallIns] exactly like the FIR-side extensions do.
 */
public class HiltIrContributionExtension(private val pluginContext: IrPluginContext) :
  MetroIrContributionExtension {

  /**
   * Lazily resolved on first scan. Null if Hilt isn't on the classpath or the K2 IR bridge isn't
   * available (both cases mean there's nothing for us to contribute).
   */
  private val bridge: Bridge? by memoize {
    val anyHiltClass =
      @Suppress("DEPRECATION") pluginContext.referenceClass(HiltSymbols.InstallIn)?.owner
        ?: return@memoize null
    val components = anyHiltClass as? Fir2IrComponents ?: return@memoize null
    Bridge(
      session = components.session,
      scanner = HiltAggregatedDepsScanner(components.session),
      componentScopes = HiltComponentScopeMapping(components.session),
    )
  }

  private class Bridge(
    val session: FirSession,
    val scanner: HiltAggregatedDepsScanner,
    val componentScopes: HiltComponentScopeMapping,
  )

  override fun contributeBindingContainers(
    scope: ClassId,
    callingDeclaration: IrDeclaration,
  ): List<IrClass> {
    val bridge = bridge ?: return emptyList()

    val result = mutableListOf<IrClass>()

    // Classpath `@AggregatedDeps` modules.
    for (dep in bridge.scanner.deps()) {
      if (dep.modules.isEmpty()) continue
      if (dep.components.none { bridge.componentScopes.resolveScope(it) == scope }) continue
      for (moduleClassId in dep.modules) {
        val irClass =
          @Suppress("DEPRECATION") pluginContext.referenceClass(moduleClassId)?.owner ?: continue
        result += irClass
      }
    }

    for (installIn in findInRoundInstallIns(bridge.session)) {
      if (!installIn.isModule) continue
      if (scope !in installIn.resolvedScopes(bridge.componentScopes)) continue
      val irClass =
        @Suppress("DEPRECATION") pluginContext.referenceClass(installIn.classId)?.owner ?: continue
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

    // Classpath `@AggregatedDeps` entry points.
    for (dep in bridge.scanner.deps()) {
      if (dep.entryPoints.isEmpty()) continue
      if (dep.components.none { bridge.componentScopes.resolveScope(it) == scope }) continue
      for (entryPointClassId in dep.entryPoints) {
        val irClass =
          @Suppress("DEPRECATION") pluginContext.referenceClass(entryPointClassId)?.owner
            ?: continue
        result += irClass.defaultType
      }
    }

    // In-round source `@InstallIn @EntryPoint` interfaces - read off the same FIR session that
    // HiltFirDeclarationExtension's predicate registration populated.
    for (installIn in findInRoundInstallIns(bridge.session)) {
      if (!installIn.isEntryPoint) continue
      if (scope !in installIn.resolvedScopes(bridge.componentScopes)) continue
      val irClass =
        @Suppress("DEPRECATION") pluginContext.referenceClass(installIn.classId)?.owner ?: continue
      result += irClass.defaultType
    }

    return result
  }

  public class Factory : MetroIrContributionExtension.Factory {
    override fun create(
      pluginContext: IrPluginContext,
      options: MetroOptions,
    ): MetroIrContributionExtension? {
      if (!options.enableHiltInterop) return null
      return HiltIrContributionExtension(pluginContext)
    }
  }
}
