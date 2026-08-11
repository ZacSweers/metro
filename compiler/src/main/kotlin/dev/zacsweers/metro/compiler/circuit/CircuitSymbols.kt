// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.callableSymbols
import dev.zacsweers.metro.compiler.fir.implements
import dev.zacsweers.metro.compiler.fir.nestedClasses
import dev.zacsweers.metro.compiler.ir.abstractFunctions
import dev.zacsweers.metro.compiler.ir.contextParameters
import dev.zacsweers.metro.compiler.ir.extensionReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.mapToSet
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.name.ClassId

internal sealed interface CircuitSymbols {

  companion object {
    val circuitInjectPredicate =
      annotated(
        CircuitCodegenTarget.entries.mapToSet {
          it.injectAnnotation.asSingleFqName()
        }
      )

    val circuitSerializablePredicate =
      annotated(setOf(CircuitClassIds.CircuitSerializable.asSingleFqName()))
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

    /** Finds the no-argument `serializer()` function on [serializedClass] or its companion. */
    context(context: CheckerContext)
    fun serializerFunction(serializedClass: FirClassSymbol<*>): FirFunctionSymbol<*>? {
      val serializerOwners = buildList {
        add(serializedClass)
        addAll(serializedClass.nestedClasses().filter { it.isCompanion })
      }
      return serializerOwners
        .asSequence()
        .flatMap { it.callableSymbols() }
        .filterIsInstance<FirFunctionSymbol<*>>()
        .firstOrNull { function ->
          val returnType = function.resolvedReturnTypeRef.coneType
          val serializedTypeArgument = returnType.typeArguments.singleOrNull()?.type?.classId
          function.name == CircuitNames.serializer &&
            function.receiverParameterSymbol == null &&
            function.contextParameterSymbols.isEmpty() &&
            function.valueParameterSymbols.isEmpty() &&
            function.typeParameterSymbols.isEmpty() &&
            returnType.classId == CircuitClassIds.KSerializer &&
            serializedTypeArgument == serializedClass.classId
        }
    }
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

    val serializerRegistration: IrClassSymbol? by lazy {
      builtinsFinder.findClass(CircuitClassIds.CircuitSerializerRegistration)
    }

    val polymorphicSubclassFunction: IrSimpleFunctionSymbol? by lazy {
      builtinsFinder.findClass(CircuitClassIds.PolymorphicModuleBuilder)?.functions?.firstOrNull {
        function ->
        function.owner.name == CircuitNames.subclass && function.owner.regularParameters.size == 2
      }
    }

    /** Finds the no-argument `serializer()` function on [serializedClass] or its companion. */
    fun serializerFunction(serializedClass: IrClassSymbol): IrSimpleFunctionSymbol? {
      val serializedClassOwner = serializedClass.owner
      val serializerOwners =
        listOfNotNull(serializedClassOwner, serializedClassOwner.companionObject())
      val serializerFunction =
        serializerOwners
          .asSequence()
          .flatMap { it.functions }
          .firstOrNull { function ->
            val receiverClass = function.dispatchReceiverParameter?.type?.classOrNull?.owner
            val canInvokeThroughSerializedType =
              function.dispatchReceiverParameter == null || receiverClass?.kind == ClassKind.OBJECT
            val returnType = function.returnType as? IrSimpleType ?: return@firstOrNull false
            val serializedTypeArgument =
              (returnType.arguments.singleOrNull() as? IrTypeProjection)
                ?.type
                ?.classOrNull
                ?.owner
                ?.classId
            function.name == CircuitNames.serializer &&
              canInvokeThroughSerializedType &&
              function.extensionReceiverParameterCompat == null &&
              function.contextParameters.isEmpty() &&
              function.typeParameters.isEmpty() &&
              function.regularParameters.isEmpty() &&
              returnType.classOrNull?.owner?.classId == CircuitClassIds.KSerializer &&
              serializedTypeArgument == serializedClassOwner.classId
          }
          ?.symbol
      return serializerFunction
    }

    fun serializerRegisterFunction(registrationClass: IrClass): IrSimpleFunction? {
      val explicitlyGenerated =
        registrationClass.functions.firstOrNull { function ->
          function.origin.expectAsOrNull<IrDeclarationOrigin.GeneratedByPlugin>()?.pluginKey ==
            CircuitOrigins.SerializerRegistrationFunction
        }
      return explicitlyGenerated
        ?: registrationClass.abstractFunctions().firstOrNull { it.name == CircuitNames.register }
        ?: registrationClass.functions.firstOrNull { it.name == CircuitNames.register }
    }
  }
}

/**
 * Session accessor for [CircuitSymbols.Fir]. Null if Circuit runtime types aren't on the classpath.
 */
internal val FirSession.circuitFirSymbols: CircuitSymbols.Fir? by
  FirSession.sessionComponentAccessor<CircuitSymbols.Fir>()

internal fun FirSession.findCircuitSerializableClasses(): List<FirRegularClassSymbol> {
  return predicateBasedProvider
    .getSymbolsByPredicate(CircuitSymbols.circuitSerializablePredicate)
    .filterIsInstance<FirRegularClassSymbol>()
    // Platform FIR sessions contain both declarations. The expect owns code generation.
    .filterNot { it.rawStatus.isActual }
}
