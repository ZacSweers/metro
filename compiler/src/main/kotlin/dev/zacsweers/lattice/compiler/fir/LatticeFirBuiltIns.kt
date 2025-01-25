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

import dev.zacsweers.lattice.compiler.LatticeClassIds
import dev.zacsweers.lattice.compiler.LatticeOptions
import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.asName
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent.Factory
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.ir.util.kotlinPackageFqn
import org.jetbrains.kotlin.name.StandardClassIds

internal class LatticeFirBuiltIns(
  session: FirSession,
  val latticeClassIds: LatticeClassIds,
  val options: LatticeOptions,
) : FirExtensionSessionComponent(session) {

  val errorFunctionSymbol by unsafeLazy {
    session.symbolProvider.getTopLevelFunctionSymbols(kotlinPackageFqn, "error".asName()).single()
  }

  val originClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(LatticeSymbols.ClassIds.latticeOrigin)
      as FirRegularClassSymbol
  }

  val injectedFunctionClassClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(
      LatticeSymbols.ClassIds.latticeInjectedFunctionClass
    ) as FirRegularClassSymbol
  }

  val composableClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(LatticeSymbols.ClassIds.composable)
      as FirRegularClassSymbol
  }

  val stableClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(LatticeSymbols.ClassIds.stable)
      as FirRegularClassSymbol
  }

  val kClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(StandardClassIds.KClass)
      as FirRegularClassSymbol
  }

  val injectClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(LatticeSymbols.ClassIds.latticeInject)
      as FirRegularClassSymbol
  }

  val providesClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(LatticeSymbols.ClassIds.latticeProvides)
      as FirRegularClassSymbol
  }

  val bindsClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(LatticeSymbols.ClassIds.latticeBinds)
      as FirRegularClassSymbol
  }

  val intoSetClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(LatticeSymbols.ClassIds.latticeIntoSet)
      as FirRegularClassSymbol
  }

  val intoMapClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(LatticeSymbols.ClassIds.latticeIntoMap)
      as FirRegularClassSymbol
  }

  companion object {
    fun getFactory(latticeClassIds: LatticeClassIds, options: LatticeOptions) = Factory { session ->
      LatticeFirBuiltIns(session, latticeClassIds, options)
    }
  }
}

internal val FirSession.latticeFirBuiltIns: LatticeFirBuiltIns by
  FirSession.sessionComponentAccessor()

internal val FirSession.latticeClassIds: LatticeClassIds
  get() = latticeFirBuiltIns.latticeClassIds
