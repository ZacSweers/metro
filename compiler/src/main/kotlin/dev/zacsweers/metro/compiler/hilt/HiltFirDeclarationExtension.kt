// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.hilt

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension.ContributionHint
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.resolvedClassId
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.name.ClassId

/**
 * Plugs Hilt source classes into Metro's existing FIR declaration generation pipeline.
 *
 * Registers the `@InstallIn` predicate so other Hilt extensions (and Metro's predicate-based
 * provider) can find in-round source classes. Reports in-round Hilt `@InstallIn @Module` classes
 * (and modules surfaced via classpath `@AggregatedDeps`) as [contribution hints][ContributionHint]
 * so Metro's existing
 * [ContributionHintFirGenerator][dev.zacsweers.metro.compiler.fir.generators.ContributionHintFirGenerator]
 * emits the per-scope hint functions for them. From there the IR-side classpath-hint scan + the
 * existing `isBindingContainer()` recognition (via `@dagger.Module` registered through
 * [MetroOptions.Builder.includeDaggerAnnotations]) routes them through `IrContributionMerger` like
 * any other binding container.
 *
 * In-round entry points and compiled modules/entry points are not handled here; they flow through
 * [HiltContributionExtension] (FIR-merged graphs) and [HiltIrContributionExtension] (IR-only).
 */
public class HiltFirDeclarationExtension(session: FirSession, compatContext: CompatContext) :
  MetroFirDeclarationGenerationExtension(session), CompatContext by compatContext {

  private val scanner by memoize { HiltAggregatedDepsScanner(session) }
  private val componentScopes by memoize { HiltComponentScopeMapping(session) }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(HiltSymbols.installInPredicate)
  }

  override fun getContributionHints(): List<ContributionHint> {
    val hints = mutableListOf<ContributionHint>()

    // Compiled @AggregatedDeps modules - emit a hint per (module, resolved scope).
    for (dep in scanner.deps()) {
      if (dep.modules.isEmpty()) continue
      val scopes = dep.components.mapNotNull(componentScopes::resolveScope)
      if (scopes.isEmpty()) continue
      for (moduleClassId in dep.modules) {
        for (scope in scopes) hints += ContributionHint(moduleClassId, scope)
      }
    }

    // In-round source classes carrying @InstallIn + @Module.
    for (installIn in findInRoundInstallIns(session)) {
      if (!installIn.isModule) continue
      for (scope in installIn.resolvedScopes(componentScopes)) {
        hints += ContributionHint(installIn.classId, scope)
      }
    }

    return hints
  }

  public class Factory : MetroFirDeclarationGenerationExtension.Factory {
    override fun create(
      session: FirSession,
      options: MetroOptions,
      compatContext: CompatContext,
    ): MetroFirDeclarationGenerationExtension? {
      if (!options.enableHiltInterop) return null
      return HiltFirDeclarationExtension(session, compatContext)
    }
  }
}

/** Parsed shape of an in-round source class annotated with `@InstallIn`. */
internal data class InRoundInstallIn(
  val classId: ClassId,
  val components: List<ClassId>,
  val isModule: Boolean,
  val isEntryPoint: Boolean,
) {
  fun resolvedScopes(componentScopes: HiltComponentScopeMapping): List<ClassId> =
    components.mapNotNull(componentScopes::resolveScope).distinct()
}

/**
 * Predicate-driven discovery of in-round source classes annotated with `@InstallIn`. Returns one
 * [InRoundInstallIn] per qualifying class - only classes that are also annotated with `@Module` or
 * `@EntryPoint` qualify.
 *
 * Callers must ensure [HiltSymbols.installInPredicate] is registered (done by
 * [HiltFirDeclarationExtension.registerPredicates]); FIR's predicate-based provider caches its
 * results per session, so repeated calls across extensions are cheap.
 */
internal fun findInRoundInstallIns(session: FirSession): List<InRoundInstallIn> {
  val symbols = session.predicateBasedProvider.getSymbolsByPredicate(HiltSymbols.installInPredicate)
  if (symbols.isEmpty()) return emptyList()

  val result = mutableListOf<InRoundInstallIn>()
  for (symbol in symbols) {
    val classSymbol = symbol as? FirRegularClassSymbol ?: continue
    val installInAnnotation =
      classSymbol.annotationsIn(session, setOf(HiltSymbols.InstallIn)).firstOrNull() ?: continue

    // Mirrors `resolvedAdditionalScopesClassIds` / `resolvedExcludedClassIds` in fir.kt: read the
    // `@InstallIn.value` `Class<?>[]` via the standard `argumentAsOrNull<FirCall>` primitive, then
    // pull each `FirGetClassCall`'s already-resolved class id.
    val components =
      installInAnnotation
        .argumentAsOrNull<FirCall>(session, StandardNames.DEFAULT_VALUE_PARAMETER, index = 0)
        ?.argumentList
        ?.arguments
        ?.mapNotNull { it.expectAsOrNull<FirGetClassCall>()?.resolvedClassId() }
        .orEmpty()
    if (components.isEmpty()) continue

    val isModule = classSymbol.isAnnotatedWithAny(session, setOf(HiltSymbols.Module))
    val isEntryPoint = classSymbol.isAnnotatedWithAny(session, setOf(HiltSymbols.EntryPoint))
    if (!isModule && !isEntryPoint) continue

    result += InRoundInstallIn(classSymbol.classId, components, isModule, isEntryPoint)
  }
  return result
}
