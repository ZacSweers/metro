// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.fir.implements
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent.Factory
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
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

  class Fir(session: FirSession) : FirExtensionSessionComponent(session) {

    private fun require(classId: ClassId) =
      session.symbolProvider.getClassLikeSymbolByClassId(classId)
        ?: error(
          "Circuit codegen is enabled but ${classId.asFqNameString()} was not found on the classpath."
        )

    // Core runtime types (required — lazily resolved)
    val circuitInject by lazy { require(CircuitClassIds.CircuitInject) }
    val screen by lazy { require(CircuitClassIds.Screen) }
    val navigator by lazy { require(CircuitClassIds.Navigator) }
    val circuitContext by lazy { require(CircuitClassIds.CircuitContext) }
    val circuitUiState by lazy { require(CircuitClassIds.CircuitUiState) }

    // UI types (optional — separate artifact, may not be on classpath for presenter-only modules)
    val modifier by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.Modifier)
    }
    val ui by lazy { session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.Ui) }
    val uiFactory by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.UiFactory)
    }

    // Presenter types (optional — separate artifact, may not be on classpath for UI-only modules)
    val presenter by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.Presenter)
    }
    val presenterFactory by lazy {
      session.symbolProvider.getClassLikeSymbolByClassId(CircuitClassIds.PresenterFactory)
    }

    companion object {
      fun getFactory(): Factory = Factory { session -> Fir(session) }
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

/**
 * Session accessor for [CircuitSymbols.Fir]. Null if Circuit runtime types aren't on the classpath.
 */
internal val FirSession.circuitFirSymbols: CircuitSymbols.Fir? by
  FirSession.sessionComponentAccessor<CircuitSymbols.Fir>()
