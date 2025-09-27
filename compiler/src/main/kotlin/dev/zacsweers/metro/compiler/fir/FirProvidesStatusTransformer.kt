// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.Symbols
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.copyWithNewDefaults
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.utils.hasBody
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider

internal class FirProvidesStatusTransformer(session: FirSession) :
  FirStatusTransformerExtension(session) {
  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(session.predicates.providesAnnotationPredicate)
  }

  override fun needTransformStatus(declaration: FirDeclaration): Boolean {
    if (declaration !is FirCallableDeclaration) return false

    val isProvides =
      session.predicateBasedProvider.matches(
        session.predicates.providesAnnotationPredicate,
        declaration,
      )

    if (!isProvides) return false

    if (session.metroFirBuiltIns.options.enableDaggerRuntimeInterop) {
      declaration.getContainingClassSymbol()?.let {
        if (it.hasAnnotation(Symbols.DaggerSymbols.ClassIds.DAGGER_MODULE, session)) {
          // Don't transform the status here because dagger may generate a factory that needs access
          return false
        }
      }
    }

    if (declaration.symbol.rawStatus.isOverride) return false
    if (declaration !is FirSimpleFunction) return false

    // A later FIR checker will check this case
    if (!declaration.hasBody) return false
    return true
  }

  override fun transformStatus(
    status: FirDeclarationStatus,
    declaration: FirDeclaration,
  ): FirDeclarationStatus {
    return when ((declaration as FirSimpleFunction).visibility) {
      Visibilities.Unknown -> {
        status.copyWithNewDefaults(
          visibility = Visibilities.Private,
          defaultVisibility = Visibilities.Private,
        )
      }
      else -> {
        // Leave explicitly defined visibility as-is
        status
      }
    }
  }
}
