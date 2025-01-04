/*
 * Copyright (C) 2025 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.compiler.fir

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
        declaration.isAnnotatedWithAny(session, session.latticeClassIds.providesAnnotations)
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
