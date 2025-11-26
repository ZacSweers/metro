// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.symbols.Symbols
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

  val errorFunctionSymbol by memoize {
    session.symbolProvider.getTopLevelFunctionSymbols(kotlinPackageFqn, Symbols.Names.error).first {
      it.valueParameterSymbols.size == 1
    }
  }

  val asContribution by memoize {
    session.symbolProvider
      .getTopLevelFunctionSymbols(Symbols.FqNames.metroRuntimePackage, Symbols.Names.asContribution)
      .first()
  }

  val createGraph by memoize {
    session.symbolProvider
      .getTopLevelFunctionSymbols(Symbols.FqNames.metroRuntimePackage, Symbols.Names.createGraph)
      .first()
  }

  val createGraphFactory by memoize {
    session.symbolProvider
      .getTopLevelFunctionSymbols(
        Symbols.FqNames.metroRuntimePackage,
        Symbols.Names.createGraphFactory,
      )
      .first()
  }

  val createDynamicGraph by memoize {
    session.symbolProvider
      .getTopLevelFunctionSymbols(
        Symbols.FqNames.metroRuntimePackage,
        Symbols.Names.createDynamicGraph,
      )
      .first()
  }

  val createDynamicGraphFactory by memoize {
    session.symbolProvider
      .getTopLevelFunctionSymbols(
        Symbols.FqNames.metroRuntimePackage,
        Symbols.Names.createDynamicGraphFactory,
      )
      .first()
  }

  val createGraphIntrinsicCallableIds by memoize {
    listOf(createGraph, createGraphFactory, createDynamicGraph, createDynamicGraphFactory)
      .associateBy { it.callableId }
  }

  val injectedFunctionClassClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroInjectedFunctionClass)
      as FirRegularClassSymbol
  }

  val callableMetadataClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.CallableMetadata)
      as FirRegularClassSymbol
  }

  val graphFactoryInvokeFunctionMarkerClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(
      Symbols.ClassIds.GraphFactoryInvokeFunctionMarkerClass
    ) as FirRegularClassSymbol
  }

  val composableClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.Composable)
      as FirRegularClassSymbol
  }

  val stableClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.Stable)
      as FirRegularClassSymbol
  }

  val nonRestartableComposable by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.Stable)
      as FirRegularClassSymbol
  }

  val kClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(StandardClassIds.KClass)
      as FirRegularClassSymbol
  }

  val injectClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroInject)
      as FirRegularClassSymbol
  }

  val assistedClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroAssisted)
      as FirRegularClassSymbol
  }

  val assistedMarkerClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroAssistedMarker)
      as FirRegularClassSymbol
  }

  val providesClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroProvides)
      as FirRegularClassSymbol
  }

  val bindsClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroBinds)
      as FirRegularClassSymbol
  }

  val intoSetClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroIntoSet)
      as FirRegularClassSymbol
  }

  val intoMapClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroIntoMap)
      as FirRegularClassSymbol
  }

  val mapClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(StandardClassIds.Map)
      as FirRegularClassSymbol
  }

  val metroContributionClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroContribution)
      as FirRegularClassSymbol
  }

  val metroImplMarkerClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroImplMarker)
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

@Suppress("UnusedReceiverParameter")
internal val FirSession.compatContext: CompatContext
  get() = CompatContext.getInstance()
