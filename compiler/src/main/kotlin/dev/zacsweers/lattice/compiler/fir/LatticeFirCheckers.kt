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
package dev.zacsweers.lattice.compiler.fir

import dev.zacsweers.lattice.compiler.fir.checkers.AssistedInjectChecker
import dev.zacsweers.lattice.compiler.fir.checkers.DependencyGraphChecker
import dev.zacsweers.lattice.compiler.fir.checkers.DependencyGraphCreatorChecker
import dev.zacsweers.lattice.compiler.fir.checkers.InjectConstructorChecker
import dev.zacsweers.lattice.compiler.fir.checkers.ProvidesChecker
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

internal class LatticeFirCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
  override val declarationCheckers: DeclarationCheckers =
    object : DeclarationCheckers() {
      override val classCheckers: Set<FirClassChecker>
        get() =
          setOf(
            InjectConstructorChecker,
            AssistedInjectChecker,
            DependencyGraphCreatorChecker,
            DependencyGraphChecker,
          )

      override val callableDeclarationCheckers: Set<FirCallableDeclarationChecker>
        get() = setOf(ProvidesChecker)
    }
}
