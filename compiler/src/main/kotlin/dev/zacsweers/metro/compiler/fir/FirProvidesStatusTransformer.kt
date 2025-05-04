// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.copyWithNewDefaults
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.utils.hasBody
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.extensions.FirStatusTransformerExtension

internal class FirProvidesStatusTransformer(session: FirSession) :
  FirStatusTransformerExtension(session) {
  override fun needTransformStatus(declaration: FirDeclaration): Boolean {
    return when (declaration) {
      is FirCallableDeclaration -> {
        if (declaration.visibility == Visibilities.Private) return false
        if (declaration.isOverride) return false
        if (declaration is FirProperty) return false
        // Property accessors must match parent property visibility
        if (declaration is FirPropertyAccessor) {
          return false
        }
        if (declaration !is FirSimpleFunction) return false

        // A later FIR checker will check this case
        if (!declaration.hasBody) return false

        var isProvides = false
        // Can't be applied to annotations with KClass args at the moment
        // https://youtrack.jetbrains.com/issue/KT-76257/
        var hasKClassArg = false
        for (annotation in declaration.annotations) {
          if (annotation.toAnnotationClassIdSafe(session) in session.classIds.providesAnnotations) {
            isProvides = true
            continue
          }
          if (annotation !is FirAnnotationCall) continue
          if (annotation.argumentList.arguments.any { it is FirGetClassCall }) {
            hasKClassArg = true
            break
          }
        }
        isProvides && !hasKClassArg
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
