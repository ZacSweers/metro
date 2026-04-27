// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.excludesArgument
import dev.zacsweers.metro.compiler.fir.replacesArgument
import dev.zacsweers.metro.compiler.fir.resolvedClassId
import dev.zacsweers.metro.compiler.fir.usesContributionProviderPath
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol

/**
 * When `generateContributionProviders` is enabled, a contributing class's impl is hidden behind a
 * generated provider unless annotated with `@ExposeImplBinding`. Naming such a hidden impl as a
 * `replaces`/`excludes` target is misleading because the impl was never a graph binding to begin
 * with — require the target to opt-in via `@ExposeImplBinding`.
 *
 * Note: `@Contributes*` annotations only support `replaces`; `@DependencyGraph` only supports
 * `excludes`. The [argName] makes the diagnostic precise about which argument is at fault.
 */
context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun FirAnnotation.checkReplacesAndExcludesTargetsExposed(
  session: FirSession,
  argName: ContributionTargetArg,
) {
  val targetCalls =
    when (argName) {
      ContributionTargetArg.REPLACES -> replacesArgument(session)?.argumentList?.arguments
      ContributionTargetArg.EXCLUDES -> excludesArgument(session)?.argumentList?.arguments
    }.orEmpty()

  for (argument in targetCalls) {
    val getClassCall = argument as? FirGetClassCall ?: continue
    val targetClassId = getClassCall.resolvedClassId() ?: continue
    val targetSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(targetClassId) as? FirClassSymbol<*>
        ?: continue
    if (!targetSymbol.usesContributionProviderPath(session)) continue
    reporter.reportOn(
      getClassCall.source ?: source,
      MetroDiagnostics.REPLACES_OR_EXCLUDES_TARGET_NOT_EXPOSED,
      "`${argName.value}` target '${targetClassId.asSingleFqName()}' is hidden behind a generated " +
        "contribution provider since `generateContributionProviders` is enabled and not annotated with " +
        "`@ExposeImplBinding`, so its impl class won't be a binding on the graph and specifying it here " +
        "won't have the intended effect. Annotate '${targetClassId.asSingleFqName()}' with " +
        "`@ExposeImplBinding` to expose its impl type.",
    )
  }
}

internal enum class ContributionTargetArg(val value: String) {
  REPLACES("replaces"),
  EXCLUDES("excludes"),
}
