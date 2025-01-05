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

import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.name.Name

internal interface LatticeFirValueParameter {
  val symbol: FirValueParameterSymbol
  val name: Name
  val contextKey: FirContextualTypeKey
  val isAssisted: Boolean

  companion object {
    operator fun invoke(
      session: FirSession,
      symbol: FirValueParameterSymbol,
      name: Name = symbol.name,
    ): LatticeFirValueParameter =
      object : LatticeFirValueParameter {
        override val symbol = symbol
        override val name = name
        override val isAssisted: Boolean by unsafeLazy { symbol.isAnnotatedWithAny(session, session.latticeClassIds.assistedAnnotations) }

        /**
         * Must be lazy because we may create this sooner than the [FirResolvePhase.TYPES] resolve
         * phase.
         */
        override val contextKey by unsafeLazy { FirContextualTypeKey.from(session, symbol) }
      }
  }
}
