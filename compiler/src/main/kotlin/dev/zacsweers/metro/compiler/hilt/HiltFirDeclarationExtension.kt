// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.hilt

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.coneTypeIfResolved
import dev.zacsweers.metro.compiler.fir.generators.ContributionsFirGenerator
import dev.zacsweers.metro.compiler.fir.resolveClassId
import dev.zacsweers.metro.compiler.fir.resolvedArgumentConeKotlinType
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension.TypeResolveService
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.ClassId

/**
 * Treats in-round `@InstallIn @Module` and `@InstallIn @EntryPoint` like `@ContributesTo`:
 * - Modules emit a [ContributionHint] per resolved scope and ride the standard binding-container
 *   recognition (via `@dagger.Module` from [MetroOptions.Builder.includeDaggerAnnotations]).
 * - Entry points emit a hint and a [MetroFirDeclarationGenerationExtension.ContributionTarget], so
 *   [ContributionsFirGenerator] generates the same nested `@MetroContribution`-annotated interface
 *   it generates for `@ContributesTo`.
 *
 * Compiled Hilt-only deps (`@AggregatedDeps` markers) go through [HiltContributionExtension] /
 * [HiltIrContributionExtension] instead.
 */
public class HiltFirDeclarationExtension(session: FirSession, compatContext: CompatContext) :
  MetroFirDeclarationGenerationExtension(session), CompatContext by compatContext {

  private val scanner by memoize { HiltAggregatedDepsScanner(session) }

  /** Owns this extension's single-pass in-round `@InstallIn` scan. */
  private val componentScopes by memoize { HiltComponentScopeMapping(session) }

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

    // In-round @InstallIn source classes. Both `@Module` (recognized downstream as a binding
    // container) and `@EntryPoint` (whose nested MetroContribution interface Metro's
    // ContributionsFirGenerator generates from our getContributionTargets() report) emit hints so
    // the classpath scan can find them.
    for (installIn in componentScopes.inRoundInstallIns) {
      if (!installIn.isModule && !installIn.isEntryPoint) continue
      for (scope in installIn.resolvedScopes(componentScopes)) {
        hints += ContributionHint(installIn.classId, scope)
      }
    }

    return hints
  }

  override fun getContributionTargets(): List<ContributionTarget> {
    val targets = mutableListOf<ContributionTarget>()
    for (installIn in componentScopes.inRoundInstallIns) {
      if (!installIn.isEntryPoint) continue
      for (scope in installIn.resolvedScopes(componentScopes)) {
        targets += ContributionTarget(installIn.classId, scope)
      }
    }
    return targets
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
 * Reads the `value: Class<?>[]` parameter of `@InstallIn`. Handles every shape the FIR pipeline
 * produces for vararg class arrays: a bare [FirGetClassCall] when written with a single component
 * (`@InstallIn(SingletonComponent::class)`), or a [FirVarargArgumentsExpression] containing the
 * class calls when multiple components are listed (or when fir2ir wraps a single one).
 *
 * When [typeResolver] is provided, falls back to it for class arguments that aren't fully resolved
 * yet, which is necessary at FIR supertype-generation phase where annotation arguments may still
 * appear as `FirClassReferenceExpression` rather than `FirResolvedQualifier`.
 */
internal fun FirAnnotation.installInComponents(
  session: FirSession,
  typeResolver: MetroFirTypeResolver?,
): List<ClassId> =
  installInComponentsImpl(session) { call -> typeResolver?.let { call.resolveClassId(it) } }

internal fun FirAnnotation.installInComponents(
  session: FirSession,
  typeResolver: TypeResolveService?,
): List<ClassId> =
  installInComponentsImpl(session) { call ->
    typeResolver?.let { call.resolvedArgumentConeKotlinType(it)?.classId }
  }

private inline fun FirAnnotation.installInComponentsImpl(
  session: FirSession,
  resolveFallback: (FirGetClassCall) -> ClassId?,
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
    call.coneTypeIfResolved()?.classId
      ?: (call.argument as? FirResolvedQualifier)?.classId
      ?: resolveFallback(call)
  }
}
