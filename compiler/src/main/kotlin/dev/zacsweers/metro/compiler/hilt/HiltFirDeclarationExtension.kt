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
 * Bridges in-round Hilt declarations into Metro's normal contribution pipeline.
 *
 * Modules only need contribution hints; Dagger interop later recognizes them as binding containers.
 * Entry points also report [MetroFirDeclarationGenerationExtension.ContributionTarget]s so
 * [ContributionsFirGenerator] can generate the same nested contribution interface used for
 * `@ContributesTo`.
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

    // Upstream Hilt-processed modules need Metro hints for classpath discovery.
    for (dep in scanner.getAllDeps()) {
      if (dep.modules.isEmpty()) continue
      val scopes = dep.components.mapNotNull(componentScopes::resolveScope)
      if (scopes.isEmpty()) continue
      for (moduleClassId in dep.modules) {
        for (scope in scopes) hints += ContributionHint(moduleClassId, scope)
      }
    }

    // Current-compilation modules and entry points also emit hints for downstream modules.
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
 * Reads the `value: Class<?>[]` argument of `@InstallIn`.
 *
 * FIR may represent the argument as a single class call or as vararg class calls. The resolver
 * fallback covers phases where annotation arguments have not yet been fully resolved.
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
