// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.abstractFunctions
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.constructType
import dev.zacsweers.metro.compiler.fir.copyTypeParametersFrom
import dev.zacsweers.metro.compiler.fir.hasOrigin
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.wrapInProviderIfNecessary
import dev.zacsweers.metro.compiler.mapToArray
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.analysis.checkers.typeParameterSymbols
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.getSuperTypes
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.resolve.toTypeParameterSymbol
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/** Generates impl classes for `@AssistedFactory` types. */
internal class AssistedFactoryImplFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  private val FirClassSymbol<*>.isAssistedImplClass: Boolean
    get() =
      hasOrigin(Keys.AssistedFactoryImplClassDeclaration) ||
        hasOrigin(Keys.AssistedFactoryImplCompanionDeclaration)

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(session.predicates.assistedFactoryAnnotationPredicate)
  }

  class AssistedFactoryImpl(val source: FirClassSymbol<*>) {
    private var computed = false
    lateinit var injectedClass: FirClassSymbol<*>

    @OptIn(SymbolInternals::class)
    fun computeTargetType(session: FirSession) {
      if (computed) return
      val createFunction = source.abstractFunctions(session).single()

      val returnTypeClass = createFunction.resolvedReturnType.toClassSymbol(session)
      if (returnTypeClass != null) {
        injectedClass = returnTypeClass
      } else {
        // TODO this is all super hacky. I think there's substitution APIs in FIR that could help
        // but it's not
        //  clear how to use them
        val typeParameterSymbol = createFunction.resolvedReturnType.toTypeParameterSymbol(session)
        if (typeParameterSymbol != null) {
          val parentSymbol = typeParameterSymbol.containingDeclarationSymbol
          val typeParameterIndex = parentSymbol.typeParameterSymbols!!.indexOf(typeParameterSymbol)
          val resolvedSupertype =
            source.getSuperTypes(session).find { it.toClassSymbol(session) == parentSymbol }
          val resolvedReturnType =
            resolvedSupertype
              ?.typeArguments
              ?.get(typeParameterIndex)
              ?.expectAsOrNull<ConeClassLikeType>()
              ?.toClassSymbol(session)
          if (resolvedReturnType != null) {
            injectedClass = resolvedReturnType
          } else {
            error("No class symbol found for ${createFunction.fir.render()}")
          }
        } else {
          error("No class symbol found for ${createFunction.fir.render()}")
        }
      }

      computed = true
    }
  }

  private val implClasses = mutableMapOf<ClassId, AssistedFactoryImpl>()

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    return if (
      classSymbol.isAnnotatedWithAny(session, session.classIds.assistedFactoryAnnotations)
    ) {
      val classId = classSymbol.classId.createNestedClassId(Symbols.Names.metroImpl)
      implClasses[classId] = AssistedFactoryImpl(classSymbol)
      setOf(classId.shortClassName)
    } else if (classSymbol.hasOrigin(Keys.AssistedFactoryImplClassDeclaration)) {
      // Needs its companion
      setOf(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
    } else {
      emptySet()
    }
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    // Impl class or companion
    return when (name) {
      SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT -> {
        if (!owner.isAssistedImplClass) return null
        // It's an impl's companion object, just generate the declaration
        createCompanionObject(owner, Keys.AssistedFactoryImplCompanionDeclaration).symbol
      }
      Symbols.Names.metroImpl -> {
        // TODO if there's no assisted params, we could optimize this to just be an object?
        createNestedClass(owner, name, Keys.AssistedFactoryImplClassDeclaration) {
            copyTypeParametersFrom(owner, session)
            superType(owner::constructType)
          }
          .symbol
      }
      else -> null
    }
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    if (!context.owner.isAssistedImplClass) return emptyList()

    val constructor =
      if (context.owner.classKind == ClassKind.OBJECT) {
        createDefaultPrivateConstructor(context.owner, Keys.Default)
      } else {
        val implClass = implClasses[context.owner.classId] ?: return emptyList()
        implClass.computeTargetType(session)

        // val param = ctor.addValueParameter(DELEGATE_FACTORY_NAME, generatedFactory.typeWith())
        val owner = context.owner
        createConstructor(
          owner,
          Keys.Default,
          isPrimary = true,
          generateDelegatedNoArgConstructorCall = true,
        ) {
          valueParameter(
            name = Symbols.Names.delegateFactory,
            typeProvider = { typeParameterRefs ->
              implClass.injectedClass.classId
                .createNestedClassId(Symbols.Names.metroFactory)
                .constructClassLikeType(
                  typeParameterRefs.mapToArray(FirTypeParameterRef::toConeType)
                )
            },
          )
        }
      }
    return listOf(constructor.symbol)
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    if (!context.owner.isAssistedImplClass) return emptySet()

    return if (classSymbol.hasOrigin(Keys.AssistedFactoryImplClassDeclaration)) {
      // Override create function
      val implClass = implClasses[classSymbol.classId] ?: return emptySet()
      implClass.computeTargetType(session)
      setOf(SpecialNames.INIT)
    } else if (classSymbol.hasOrigin(Keys.AssistedFactoryImplCompanionDeclaration)) {
      // Add create function
      setOf(SpecialNames.INIT, Symbols.Names.create)
    } else {
      emptySet()
    }
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val nonNullContext = context ?: return emptyList()
    if (!nonNullContext.owner.isAssistedImplClass) return emptyList()

    // implement creator, create function
    val implClassSymbol =
      if (nonNullContext.owner.isCompanion) {
        nonNullContext.owner.getContainingClassSymbol() ?: return emptyList()
      } else {
        nonNullContext.owner
      }
    val implClassId = implClassSymbol.classId
    val implClass = implClasses[implClassId] ?: return emptyList()

    val creator =
      if (nonNullContext.owner.classKind == ClassKind.OBJECT) {
        // companion object, declare creator
        val owner = nonNullContext.owner
        createMemberFunction(
          owner,
          Keys.Default,
          Symbols.Names.create,
          returnTypeProvider = {
            implClass.source.constructType(it).wrapInProviderIfNecessary(session)
          },
        ) {
          // Delegate factory
          valueParameter(
            Symbols.Names.delegateFactory,
            typeProvider = {
              implClass.injectedClass.classId
                .createNestedClassId(Symbols.Names.metroFactory)
                .constructClassLikeType(it.mapToArray(FirTypeParameterRef::toConeType))
            },
            key = Keys.ValueParameter,
          )
        }
      } else {
        return emptyList()
      }

    return listOf(creator.symbol)
  }
}
