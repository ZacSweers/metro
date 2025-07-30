// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.copyWithNewDefaults
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.utils.hasBody
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
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
    val isProvides =
      session.predicateBasedProvider.matches(
        session.predicates.providesAnnotationPredicate,
        declaration,
      )

    if (!isProvides) return false

    return when (declaration) {
      is FirCallableDeclaration -> {
        if (declaration.symbol.rawStatus.isOverride) return false
        if (declaration !is FirSimpleFunction) return false

        // A later FIR checker will check this case
        if (!declaration.hasBody) return false
        true
      }
      else -> false
    }
  }

  override fun transformStatus(
    status: FirDeclarationStatus,
    declaration: FirDeclaration,
  ): FirDeclarationStatus {
    val visibility = (declaration as FirSimpleFunction).visibility
    return when (visibility) {
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
