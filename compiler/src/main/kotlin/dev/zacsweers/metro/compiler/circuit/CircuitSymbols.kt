// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.implements
import dev.zacsweers.metro.compiler.mapToSet
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.name.ClassId

internal sealed interface CircuitSymbols {

  companion object {
    val circuitInjectPredicate =
      annotated(
        CircuitCodegenTarget.entries.mapToSet {
          it.injectAnnotation.asSingleFqName()
        }
      )
  }

  class Fir(session: FirSession) : FirExtensionSessionComponent(session) {

    companion object {
      fun getFactory(): Factory = Factory { session -> Fir(session) }
    }

    fun isUiType(clazz: FirClass, target: CircuitCodegenTarget): Boolean {
      return clazz.implements(target.uiClassId, session)
    }

    fun isPresenterType(clazz: FirClass, target: CircuitCodegenTarget): Boolean {
      return clazz.implements(target.presenterClassId, session)
    }

    fun isScreenType(clazz: FirClassSymbol<*>, target: CircuitCodegenTarget): Boolean {
      return clazz.implements(target.screenClassId, session)
    }

    fun isUiStateType(clazz: FirClassSymbol<*>, target: CircuitCodegenTarget): Boolean {
      return clazz.implements(target.uiStateClassId, session)
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

    fun isScreenType(classId: ClassId, target: CircuitCodegenTarget): Boolean =
      isOrImplements(classId, target.screenClassId)

    fun isUiStateType(classId: ClassId, target: CircuitCodegenTarget): Boolean =
      isOrImplements(classId, target.uiStateClassId)

    fun isModifierType(classId: ClassId): Boolean =
      isOrImplements(classId, CircuitClassIds.Modifier)

    fun isNavigatorType(classId: ClassId): Boolean = classId == CircuitClassIds.Navigator

    fun isCircuitContextType(classId: ClassId): Boolean = classId == CircuitClassIds.CircuitContext
  }

  class Ir(private val builtinsFinder: CompatContext.DeclarationFinderCompat) : CircuitSymbols {

    val modifier: IrClassSymbol by lazy {
      builtinsFinder.findClass(CircuitClassIds.Modifier)
        ?: error("Could not find ${CircuitClassIds.Modifier}")
    }

    fun uiState(target: CircuitCodegenTarget): IrClassSymbol = require(target.uiStateClassId)

    fun ui(target: CircuitCodegenTarget): IrClassSymbol = require(target.uiClassId)

    private fun require(classId: ClassId): IrClassSymbol =
      builtinsFinder.findClass(classId) ?: error("Could not find $classId")

    val presenterOfFun: IrSimpleFunctionSymbol by lazy {
      builtinsFinder.findFunctions(CircuitCallableIds.presenterOf).singleOrNull()
        ?: error("Could not find ${CircuitCallableIds.presenterOf}")
    }

    val uiFun: IrSimpleFunctionSymbol by lazy {
      builtinsFinder.findFunctions(CircuitCallableIds.ui).singleOrNull()
        ?: error("Could not find ${CircuitCallableIds.ui}")
    }
  }
}

/**
 * Session accessor for [CircuitSymbols.Fir]. Null if Circuit runtime types aren't on the classpath.
 */
internal val FirSession.circuitFirSymbols: CircuitSymbols.Fir? by
  FirSession.sessionComponentAccessor<CircuitSymbols.Fir>()
