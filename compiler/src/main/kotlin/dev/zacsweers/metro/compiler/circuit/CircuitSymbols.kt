// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.fir.implements
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.name.ClassId

internal sealed interface CircuitSymbols {

  companion object {
    val circuitInjectPredicate = annotated(CircuitClassIds.CircuitInject.asSingleFqName())
  }

  class Fir
  private constructor(
    private val session: FirSession,
    // Core runtime types (required)
    val circuitInject: FirClassLikeSymbol<*>,
    val screen: FirClassLikeSymbol<*>,
    val navigator: FirClassLikeSymbol<*>,
    val circuitContext: FirClassLikeSymbol<*>,
    val circuitUiState: FirClassLikeSymbol<*>,
    // UI types (optional — separate artifact, may not be on classpath for presenter-only modules)
    val modifier: FirClassLikeSymbol<*>?,
    val ui: FirClassLikeSymbol<*>?,
    val uiFactory: FirClassLikeSymbol<*>?,
    // Presenter types (optional — separate artifact, may not be on classpath for UI-only modules)
    val presenter: FirClassLikeSymbol<*>?,
    val presenterFactory: FirClassLikeSymbol<*>?,
  ) : CircuitSymbols {

    companion object {
      /**
       * Returns null if required core Circuit types can't be found on the classpath. UI and
       * Presenter types are optional (separate artifacts).
       */
      operator fun invoke(session: FirSession): Fir? {
        val sp = session.symbolProvider
        return Fir(
          session = session,
          // Required
          circuitInject =
            sp.getClassLikeSymbolByClassId(CircuitClassIds.CircuitInject) ?: return null,
          screen = sp.getClassLikeSymbolByClassId(CircuitClassIds.Screen) ?: return null,
          navigator = sp.getClassLikeSymbolByClassId(CircuitClassIds.Navigator) ?: return null,
          circuitContext =
            sp.getClassLikeSymbolByClassId(CircuitClassIds.CircuitContext) ?: return null,
          circuitUiState =
            sp.getClassLikeSymbolByClassId(CircuitClassIds.CircuitUiState) ?: return null,
          // Optional
          modifier = sp.getClassLikeSymbolByClassId(CircuitClassIds.Modifier),
          ui = sp.getClassLikeSymbolByClassId(CircuitClassIds.Ui),
          uiFactory = sp.getClassLikeSymbolByClassId(CircuitClassIds.UiFactory),
          presenter = sp.getClassLikeSymbolByClassId(CircuitClassIds.Presenter),
          presenterFactory = sp.getClassLikeSymbolByClassId(CircuitClassIds.PresenterFactory),
        )
      }
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

    /** Returns true if [classId] is or implements the given [target] Circuit type. */
    fun isOrImplements(classId: ClassId, target: ClassId): Boolean {
      if (classId == target) return true
      val symbol =
        session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirClassSymbol<*>
          ?: return false
      return symbol.implements(target, session)
    }

    fun isScreenType(classId: ClassId): Boolean = isOrImplements(classId, CircuitClassIds.Screen)

    fun isUiStateType(classId: ClassId): Boolean =
      isOrImplements(classId, CircuitClassIds.CircuitUiState)

    fun isModifierType(classId: ClassId): Boolean =
      isOrImplements(classId, CircuitClassIds.Modifier)

    fun isNavigatorType(classId: ClassId): Boolean = classId == CircuitClassIds.Navigator

    fun isCircuitContextType(classId: ClassId): Boolean = classId == CircuitClassIds.CircuitContext
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

    val modifier: IrClassSymbol by lazy {
      pluginContext.referenceClass(CircuitClassIds.Modifier)
        ?: error("Could not find ${CircuitClassIds.Modifier}")
    }

    val composableAnnotationCtor: IrConstructorSymbol by lazy {
      pluginContext.referenceClass(Symbols.ClassIds.Composable)!!.constructors.first()
    }

    val presenterOfFun: IrSimpleFunctionSymbol by lazy {
      pluginContext.referenceFunctions(CircuitCallableIds.presenterOf).singleOrNull()
        ?: error("Could not find ${CircuitCallableIds.presenterOf}")
    }

    val uiFun: IrSimpleFunctionSymbol by lazy {
      pluginContext.referenceFunctions(CircuitCallableIds.ui).singleOrNull()
        ?: error("Could not find ${CircuitCallableIds.ui}")
    }

    val originAnnotationCtor: IrConstructorSymbol by lazy {
      pluginContext.referenceClass(Symbols.ClassIds.metroOrigin)!!.constructors.first()
    }
  }
}
