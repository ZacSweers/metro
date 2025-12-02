// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.generatedContextParameterName
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.resolve.isContextParameter
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames.UNDERSCORE_FOR_UNUSED_VAR

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
      name: Name? = null,
      memberKey: Name? = null,
      wrapInProvider: Boolean = false,
    ): MetroFirValueParameter =
      object : MetroFirValueParameter {
        override val symbol = symbol
        override val name by memoize {
          name
            ?: symbol.name.letIf(
              symbol.isContextParameter() && symbol.name == UNDERSCORE_FOR_UNUSED_VAR
            ) {
              symbol.resolvedReturnType.classId?.let { generatedContextParameterName(it) }
                ?: symbol.name
            }
        }
        override val memberInjectorFunctionName: Name by memoize {
          "inject${(memberKey ?: this.name).capitalizeUS().asString()}".asName()
        }
        override val isAssisted: Boolean by memoize {
          symbol.isAnnotatedWithAny(session, session.classIds.assistedAnnotations)
        }

        /**
         * Must be lazy because we may create this sooner than the [FirResolvePhase.TYPES] resolve
         * phase.
         */
        private val contextKeyLazy = memoize {
          FirContextualTypeKey.from(session, symbol, wrapInProvider = wrapInProvider)
        }
        override val contextKey
          get() = contextKeyLazy.value

        override fun toString(): String {
          return buildString {
            append(name ?: symbol.name)
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
