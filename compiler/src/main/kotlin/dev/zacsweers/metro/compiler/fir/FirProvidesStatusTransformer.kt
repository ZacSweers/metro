// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.copyWithNewDefaults
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension

internal class FirProvidesStatusTransformer(session: FirSession) :
  FirStatusTransformerExtension(session) {
  override fun needTransformStatus(declaration: FirDeclaration): Boolean {
    return when (declaration) {
      is FirCallableDeclaration -> {
        declaration.isAnnotatedWithAny(session, session.classIds.providesAnnotations)
      }
      else -> false
    }
  }

  override fun transformStatus(
    status: FirDeclarationStatus,
    declaration: FirDeclaration,
  ): FirDeclarationStatus {
    return when (status.modality) {
      null ->
        status.copyWithNewDefaults(
          visibility = Visibilities.Private,
          defaultVisibility = Visibilities.Private,
        )
      else -> status.copyWithNewDefaults(defaultVisibility = Visibilities.Private)
    }
  }
}
