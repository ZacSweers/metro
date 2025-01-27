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
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.name.Name

internal interface MetroFirValueParameter {
  val symbol: FirCallableSymbol<*>
  val name: Name
  val contextKey: FirContextualTypeKey
  val isAssisted: Boolean
  val memberInjectorFunctionName: Name

  companion object {
    operator fun invoke(
      session: FirSession,
      symbol: FirCallableSymbol<*>,
      name: Name = symbol.name,
      memberKey: Name = name,
      wrapInProvider: Boolean = false,
    ): MetroFirValueParameter =
      object : MetroFirValueParameter {
        override val symbol = symbol
        override val name = name
        override val memberInjectorFunctionName: Name by unsafeLazy {
          "inject${memberKey.capitalizeUS().asString()}".asName()
        }
        override val isAssisted: Boolean by unsafeLazy {
          symbol.isAnnotatedWithAny(session, session.classIds.assistedAnnotations)
        }

        /**
         * Must be lazy because we may create this sooner than the [FirResolvePhase.TYPES] resolve
         * phase.
         */
        private val contextKeyLazy = unsafeLazy {
          FirContextualTypeKey.from(session, symbol, wrapInProvider = wrapInProvider)
        }
        override val contextKey
          get() = contextKeyLazy.value

        override fun toString(): String {
          return buildString {
            append(name)
            if (isAssisted) {
              append(" (assisted)")
            }
            if (contextKeyLazy.isInitialized()) {
              append(": $contextKey")
            } else {
              append(": <uninitialized>")
            }
          }
        }
      }
  }
}
