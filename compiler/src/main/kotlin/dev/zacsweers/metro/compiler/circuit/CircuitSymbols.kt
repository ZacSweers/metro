// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.fir.implements
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.resolve.getSuperTypes
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol

internal sealed interface CircuitSymbols {

  companion object {
    val circuitInjectPredicate =
      annotated(CircuitClassIds.CircuitInject.asSingleFqName())
  }

  class Fir(private val session: FirSession) : CircuitSymbols {

    val circuitInject: FirClassLikeSymbol<*> by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.CircuitInject)!!
    }

    val screen: FirClassLikeSymbol<*> by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.Screen)!!
    }

    val navigator: FirClassLikeSymbol<*> by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.Navigator)!!
    }

    val circuitContext: FirClassLikeSymbol<*> by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.CircuitContext)!!
    }

    val circuitUiState: FirClassLikeSymbol<*> by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.CircuitUiState)!!
    }

    val modifier: FirClassLikeSymbol<*> by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.Modifier)!!
    }

    val ui: FirClassLikeSymbol<*> by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.Ui)!!
    }

    val uiFactory: FirClassLikeSymbol<*> by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.UiFactory)!!
    }

    val presenter: FirClassLikeSymbol<*> by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.Presenter)!!
    }

    val presenterFactory: FirClassLikeSymbol<*> by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.PresenterFactory)!!
    }

    fun isUiType(clazz: FirClass): Boolean {
      return clazz.implements(CircuitClassIds.Ui, session)
    }

    fun isPresenterType(clazz: FirClass): Boolean {
      return clazz.implements(CircuitClassIds.Presenter, session)
    }

    fun isScreenType(clazz: FirClassSymbol<*>): Boolean {
      return clazz.implements(CircuitClassIds.Screen, session)
    }

    fun isUiStateType(clazz: FirClassSymbol<*>): Boolean {
      return clazz.implements(CircuitClassIds.CircuitUiState, session)
    }

    fun isNavigatorType(clazz: FirClassSymbol<*>): Boolean {
      return clazz.classId == CircuitClassIds.Navigator
    }

    fun isCircuitContextType(clazz: FirClassSymbol<*>): Boolean {
      return clazz.classId == CircuitClassIds.CircuitContext
    }

    fun isModifierType(clazz: FirClassSymbol<*>): Boolean {
      return clazz.implements(CircuitClassIds.Modifier, session)
    }
  }

  class Ir(private val pluginContext: IrPluginContext) : CircuitSymbols {

    val screen: IrClassSymbol by lazy {
      pluginContext.referenceClass(CircuitClassIds.Screen)
        ?: error("Could not find ${CircuitClassIds.Screen}")
    }

    val navigator: IrClassSymbol by lazy {
      pluginContext.referenceClass(CircuitClassIds.Navigator)
        ?: error("Could not find ${CircuitClassIds.Navigator}")
    }

    val circuitContext: IrClassSymbol by lazy {
      pluginContext.referenceClass(CircuitClassIds.CircuitContext)
        ?: error("Could not find ${CircuitClassIds.CircuitContext}")
    }

    val circuitUiState: IrClassSymbol by lazy {
      pluginContext.referenceClass(CircuitClassIds.CircuitUiState)
        ?: error("Could not find ${CircuitClassIds.CircuitUiState}")
    }

    val ui: IrClassSymbol by lazy {
      pluginContext.referenceClass(CircuitClassIds.Ui)
        ?: error("Could not find ${CircuitClassIds.Ui}")
    }

    val uiFactory: IrClassSymbol by lazy {
      pluginContext.referenceClass(CircuitClassIds.UiFactory)
        ?: error("Could not find ${CircuitClassIds.UiFactory}")
    }

    val presenter: IrClassSymbol by lazy {
      pluginContext.referenceClass(CircuitClassIds.Presenter)
        ?: error("Could not find ${CircuitClassIds.Presenter}")
    }

    val presenterFactory: IrClassSymbol by lazy {
      pluginContext.referenceClass(CircuitClassIds.PresenterFactory)
        ?: error("Could not find ${CircuitClassIds.PresenterFactory}")
    }

    val presenterOfFun: IrSimpleFunctionSymbol by lazy {
      pluginContext.referenceFunctions(CircuitCallableIds.presenterOf).singleOrNull()
        ?: error("Could not find ${CircuitCallableIds.presenterOf}")
    }

    val uiFun: IrSimpleFunctionSymbol by lazy {
      pluginContext.referenceFunctions(CircuitCallableIds.ui).singleOrNull()
        ?: error("Could not find ${CircuitCallableIds.ui}")
    }
  }
}
