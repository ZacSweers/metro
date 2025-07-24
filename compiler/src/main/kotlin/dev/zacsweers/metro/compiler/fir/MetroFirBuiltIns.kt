// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.ir.util.kotlinPackageFqn
import org.jetbrains.kotlin.name.StandardClassIds

internal class MetroFirBuiltIns(
  session: FirSession,
  val classIds: ClassIds,
  val predicates: ExtensionPredicates,
  val options: MetroOptions,
) : FirExtensionSessionComponent(session) {

  val errorFunctionSymbol by unsafeLazy {
    session.symbolProvider.getTopLevelFunctionSymbols(kotlinPackageFqn, Symbols.Names.error).first {
      it.valueParameterSymbols.size == 1
    }
  }

  val asContribution by unsafeLazy {
    session.symbolProvider
      .getTopLevelFunctionSymbols(Symbols.FqNames.metroRuntimePackage, Symbols.Names.asContribution)
      .first()
  }

  val createGraph by unsafeLazy {
    session.symbolProvider
      .getTopLevelFunctionSymbols(Symbols.FqNames.metroRuntimePackage, Symbols.Names.createGraph)
      .first()
  }

  val createGraphFactory by unsafeLazy {
    session.symbolProvider
      .getTopLevelFunctionSymbols(
        Symbols.FqNames.metroRuntimePackage,
        Symbols.Names.createGraphFactory,
      )
      .first()
  }

  val injectedFunctionClassClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroInjectedFunctionClass)
      as FirRegularClassSymbol
  }

  val providesCallableIdClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.ProvidesCallableId)
      as FirRegularClassSymbol
  }

  val graphFactoryInvokeFunctionMarkerClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(
      Symbols.ClassIds.GraphFactoryInvokeFunctionMarkerClass
    ) as FirRegularClassSymbol
  }

  val composableClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.Composable)
      as FirRegularClassSymbol
  }

  val stableClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.Stable)
      as FirRegularClassSymbol
  }

  val nonRestartableComposable by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.Stable)
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

  val assistedClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroAssisted)
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

  val mapClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(StandardClassIds.Map)
      as FirRegularClassSymbol
  }

  val metroContributionClassSymbol by unsafeLazy {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroContribution)
      as FirRegularClassSymbol
  }

  companion object {
    fun getFactory(classIds: ClassIds, options: MetroOptions) = Factory { session ->
      MetroFirBuiltIns(session, classIds, ExtensionPredicates(classIds), options)
    }
  }
}

internal val FirSession.metroFirBuiltIns: MetroFirBuiltIns by FirSession.sessionComponentAccessor()

internal val FirSession.classIds: ClassIds
  get() = metroFirBuiltIns.classIds

internal val FirSession.predicates: ExtensionPredicates
  get() = metroFirBuiltIns.predicates
