/*
 * Copyright (C) 2025 Zac Sweers
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
package dev.zacsweers.lattice.compiler.fir.generators

import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.expectAsOrNull
import dev.zacsweers.lattice.compiler.fir.LatticeFirValueParameter
import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.abstractFunctions
import dev.zacsweers.lattice.compiler.fir.constructType
import dev.zacsweers.lattice.compiler.fir.copyParameters
import dev.zacsweers.lattice.compiler.fir.generateMemberFunction
import dev.zacsweers.lattice.compiler.fir.hasOrigin
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.wrapInProvider
import dev.zacsweers.lattice.compiler.mapToArray
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.analysis.checkers.typeParameterSymbols
import org.jetbrains.kotlin.fir.copy
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
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

  private val assistedFactoryAnnotationPredicate by unsafeLazy {
    annotated(session.latticeClassIds.assistedFactoryAnnotations.map { it.asSingleFqName() })
  }

  private val FirClassSymbol<*>.isAssistedImplClass: Boolean
    get() =
      hasOrigin(LatticeKeys.AssistedFactoryImplClassDeclaration) ||
        hasOrigin(LatticeKeys.AssistedFactoryImplCompanionDeclaration)

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(assistedFactoryAnnotationPredicate)
  }

  class AssistedFactoryImpl(val source: FirClassSymbol<*>) {
    private var computed = false
    lateinit var function: FirNamedFunctionSymbol
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

      function = createFunction
      computed = true
    }
  }

  private val implClasses = mutableMapOf<ClassId, AssistedFactoryImpl>()

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    return if (
      classSymbol.isAnnotatedWithAny(session, session.latticeClassIds.assistedFactoryAnnotations)
    ) {
      val classId = classSymbol.classId.createNestedClassId(LatticeSymbols.Names.latticeImpl)
      implClasses[classId] = AssistedFactoryImpl(classSymbol)
      setOf(classId.shortClassName)
    } else if (classSymbol.hasOrigin(LatticeKeys.AssistedFactoryImplClassDeclaration)) {
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
        createCompanionObject(owner, LatticeKeys.AssistedFactoryImplCompanionDeclaration).symbol
      }
      LatticeSymbols.Names.latticeImpl -> {
        // TODO if there's no assisted params, we could optimize this to just be an object?
        createNestedClass(owner, name, LatticeKeys.AssistedFactoryImplClassDeclaration) {
            for (typeParam in owner.typeParameterSymbols) {
              typeParameter(typeParam.name, typeParam.variance, key = LatticeKeys.Default) {
                if (typeParam.isBound) {
                  typeParam.resolvedBounds.forEach { bound -> bound(bound.coneType) }
                }
              }
            }

            superType { typeParameterRefs -> owner.constructType(typeParameterRefs) }
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
        createDefaultPrivateConstructor(context.owner, LatticeKeys.Default)
      } else {
        val implClass = implClasses[context.owner.classId] ?: return emptyList()
        implClass.computeTargetType(session)

        // val param = ctor.addValueParameter(DELEGATE_FACTORY_NAME, generatedFactory.typeWith())
        val owner = context.owner
        createConstructor(
          owner,
          LatticeKeys.Default,
          isPrimary = true,
          generateDelegatedNoArgConstructorCall = true,
        ) {
          valueParameter(
            name = LatticeSymbols.Names.delegateFactory,
            typeProvider = { typeParameterRefs ->
              implClass.injectedClass.classId
                .createNestedClassId(LatticeSymbols.Names.latticeFactory)
                .constructClassLikeType(typeParameterRefs.mapToArray { it.toConeType() })
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

    return if (classSymbol.hasOrigin(LatticeKeys.AssistedFactoryImplClassDeclaration)) {
      // Override create function
      val implClass = implClasses[classSymbol.classId] ?: return emptySet()
      implClass.computeTargetType(session)
      setOf(SpecialNames.INIT, implClass.function.name)
    } else if (classSymbol.hasOrigin(LatticeKeys.AssistedFactoryImplCompanionDeclaration)) {
      // Add create function
      setOf(SpecialNames.INIT, LatticeSymbols.Names.create)
    } else {
      emptySet()
    }
  }

  @OptIn(SymbolInternals::class)
  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val context = context ?: return emptyList()
    if (!context.owner.isAssistedImplClass) return emptyList()

    // implement creator, create function
    val implClassSymbol =
      if (context.owner.isCompanion) {
        context.owner.getContainingClassSymbol() ?: return emptyList()
      } else {
        context.owner
      }
    val implClassId = implClassSymbol.classId
    val implClass = implClasses[implClassId] ?: return emptyList()

    val creator =
      if (context.owner.classKind == ClassKind.OBJECT) {
        // companion object, declare creator
        val owner = context.owner
        createMemberFunction(
          owner,
          LatticeKeys.Default,
          LatticeSymbols.Names.create,
          returnTypeProvider = { implClass.source.constructType(it).wrapInProvider() },
        ) {
          // Delegate factory
          valueParameter(
            LatticeSymbols.Names.delegateFactory,
            typeProvider = {
              implClass.injectedClass.classId
                .createNestedClassId(LatticeSymbols.Names.latticeFactory)
                .constructClassLikeType(it.mapToArray { it.toConeType() })
            },
            key = LatticeKeys.ValueParameter,
          )
        }
      } else {
        // declare the function override
        generateMemberFunction(
          context.owner,
          returnTypeProvider = { implClass.injectedClass.constructType(it) },
          CallableId(context.owner.classId, LatticeSymbols.Names.create),
          LatticeKeys.AssistedFactoryImplCreatorFunctionDeclaration.origin,
        ) {
          status = status.copy(isOverride = true)
          copyParameters(
            functionBuilder = this,
            sourceParameters =
              implClass.function.valueParameterSymbols.map {
                LatticeFirValueParameter(session, it)
              },
            copyParameterDefaults = false,
          )
        }
      }

    return listOf(creator.symbol)
  }
}
