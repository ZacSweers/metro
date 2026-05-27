// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.hilt

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension.ContributionHint
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.resolveClassId
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
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
  private val typeResolverFactory by memoize { MetroFirTypeResolver.Factory(session) }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(HiltSymbols.installInPredicate)
    register(HiltSymbols.modulePredicate)
    register(HiltSymbols.entryPointPredicate)
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
    for (installIn in findInRoundInstallIns(session, typeResolverFactory)) {
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
internal fun findInRoundInstallIns(
  session: FirSession,
  typeResolverFactory: MetroFirTypeResolver.Factory? = null,
): List<InRoundInstallIn> {
  val symbols = session.predicateBasedProvider.getSymbolsByPredicate(HiltSymbols.installInPredicate)
  if (symbols.isEmpty()) return emptyList()

  val result = mutableListOf<InRoundInstallIn>()
  for (symbol in symbols) {
    val classSymbol = symbol as? FirRegularClassSymbol ?: continue
    // Force annotation-argument resolution: the predicate-based provider triggers resolution
    // only for the predicate's annotation (`@InstallIn`), leaving sibling annotations on the
    // same class (here, `@Module` / `@EntryPoint`) unresolved at FIR supertype-generation phase.
    // Without this nudge `resolvedCompilerAnnotationsWithClassIds` skips them.
    classSymbol.lazyResolveToPhase(FirResolvePhase.ANNOTATION_ARGUMENTS)

    // Use raw `fir.annotations` rather than `resolvedCompilerAnnotationsWithClassIds`. The latter
    // is cached the first time it's accessed (e.g., by the predicate-based provider for the
    // predicate's annotation) and may not include sibling annotations resolved later. The raw
    // annotation list contains all annotations on the class; `toAnnotationClassIdSafe` handles
    // both resolved and unresolved forms.
    val rawAnnotations = @OptIn(SymbolInternals::class) classSymbol.fir.annotations
    val annoClassIds =
      rawAnnotations.mapNotNullTo(mutableSetOf()) { it.toAnnotationClassIdSafe(session) }
    val installInAnnotation =
      rawAnnotations.firstOrNull { it.toAnnotationClassIdSafe(session) == HiltSymbols.InstallIn }
        ?: continue

    val typeResolver = typeResolverFactory?.create(classSymbol)
    val components = installInAnnotation.installInComponents(session, typeResolver)
    if (components.isEmpty()) continue

    val isModule = HiltSymbols.Module in annoClassIds
    val isEntryPoint = HiltSymbols.EntryPoint in annoClassIds
    if (!isModule && !isEntryPoint) continue

    result += InRoundInstallIn(classSymbol.classId, components, isModule, isEntryPoint)
  }
  return result
}

/**
 * Reads the `value: Class<?>[]` parameter of `@InstallIn`. Handles every shape the FIR pipeline
 * produces for vararg class arrays: a bare [FirGetClassCall] when written with a single component
 * (`@InstallIn(SingletonComponent::class)`), or a [FirVarargArgumentsExpression] containing the
 * class calls when multiple components are listed (or when fir2ir wraps a single one).
 *
 * When [typeResolver] is provided, falls back to it for class arguments that aren't fully resolved
 * yet, which is necessary at FIR supertype-generation phase where annotation arguments may still
 * appear as `FirClassReferenceExpression` rather than `FirResolvedQualifier`. Matches the pattern
 * Metro itself uses in [resolveClassId] and friends in `fir.kt`.
 */
private fun FirAnnotation.installInComponents(
  session: FirSession,
  typeResolver: MetroFirTypeResolver?,
): List<ClassId> {
  val arg =
    argumentAsOrNull<FirExpression>(session, StandardNames.DEFAULT_VALUE_PARAMETER, index = 0)
      ?: return emptyList()
  val classCalls: List<FirGetClassCall> =
    when (arg) {
      is FirGetClassCall -> listOf(arg)
      is FirVarargArgumentsExpression -> arg.arguments.filterIsInstance<FirGetClassCall>()
      else -> emptyList()
    }
  return classCalls.mapNotNull { call ->
    if (typeResolver != null) call.resolveClassId(typeResolver)
    else (call.argument as? FirResolvedQualifier)?.classId
  }
}
