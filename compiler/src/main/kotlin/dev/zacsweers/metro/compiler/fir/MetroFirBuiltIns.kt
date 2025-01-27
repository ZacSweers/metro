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
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent.Factory
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.ir.util.kotlinPackageFqn
import org.jetbrains.kotlin.name.StandardClassIds

internal class MetroFirBuiltIns(
  session: FirSession,
  val classIds: ClassIds,
  val options: MetroOptions,
) : FirExtensionSessionComponent(session) {

  val errorFunctionSymbol by unsafeLazy {
    session.symbolProvider.getTopLevelFunctionSymbols(kotlinPackageFqn, "error".asName()).single()
  }

  val originClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroOrigin)
      as FirRegularClassSymbol
  }

  val injectedFunctionClassClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroInjectedFunctionClass)
      as FirRegularClassSymbol
  }

  val composableClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.composable)
      as FirRegularClassSymbol
  }

  val stableClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.stable)
      as FirRegularClassSymbol
  }

  val kClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(StandardClassIds.KClass)
      as FirRegularClassSymbol
  }

  val injectClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroInject)
      as FirRegularClassSymbol
  }

  val providesClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroProvides)
      as FirRegularClassSymbol
  }

  val bindsClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroBinds)
      as FirRegularClassSymbol
  }

  val intoSetClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroIntoSet)
      as FirRegularClassSymbol
  }

  val intoMapClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroIntoMap)
      as FirRegularClassSymbol
  }

  companion object {
    fun getFactory(classIds: ClassIds, options: MetroOptions) = Factory { session ->
      MetroFirBuiltIns(session, classIds, options)
    }
  }
}

internal val FirSession.metroFirBuiltIns: MetroFirBuiltIns by FirSession.sessionComponentAccessor()

internal val FirSession.classIds: ClassIds
  get() = metroFirBuiltIns.classIds
