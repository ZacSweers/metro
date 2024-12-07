/*
 * Copyright (C) 2024 Zac Sweers
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
package dev.zacsweers.lattice.fir

import dev.zacsweers.lattice.LatticeClassIds
import dev.zacsweers.lattice.fir.checkers.AssistedInjectChecker
import dev.zacsweers.lattice.fir.checkers.ComponentCreatorChecker
import dev.zacsweers.lattice.fir.checkers.InjectConstructorChecker
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

internal class LatticeFirExtensionRegistrar(private val latticeClassIds: LatticeClassIds) :
  FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +LatticeFirCheckers.getFactory(latticeClassIds)
  }
}

internal class LatticeFirCheckers(
  session: FirSession,
  private val latticeClassIds: LatticeClassIds,
) : FirAdditionalCheckersExtension(session) {
  companion object {
    fun getFactory(latticeClassIds: LatticeClassIds) = Factory { session ->
      LatticeFirCheckers(session, latticeClassIds)
    }
  }

  override val declarationCheckers: DeclarationCheckers =
    object : DeclarationCheckers() {
      override val classCheckers: Set<FirClassChecker>
        get() =
          setOf(
            InjectConstructorChecker(session, latticeClassIds),
            AssistedInjectChecker(session, latticeClassIds),
            ComponentCreatorChecker(session, latticeClassIds),
          )
    }
}
