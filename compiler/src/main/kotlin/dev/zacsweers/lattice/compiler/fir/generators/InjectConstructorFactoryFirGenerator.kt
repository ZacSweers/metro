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
import dev.zacsweers.lattice.compiler.NameAllocator
import dev.zacsweers.lattice.compiler.asName
import dev.zacsweers.lattice.compiler.capitalizeUS
import dev.zacsweers.lattice.compiler.fir.LatticeFirValueParameter
import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.allCallableMembers
import dev.zacsweers.lattice.compiler.fir.isAnnotatedInject
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.lattice.compiler.fir.wrapInProvider
import dev.zacsweers.lattice.compiler.newName
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/** Generates factory declarations for `@Inject`-annotated classes. */
internal class InjectConstructorFactoryFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  private val injectAnnotationPredicate by unsafeLazy {
    annotated(session.latticeClassIds.injectAnnotations.map { it.asSingleFqName() })
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(injectAnnotationPredicate)
  }

  // TODO apparently writing these types of caches is bad and
  //  generate* functions should be side-effect-free, but honestly
  //  how is this practical without this? Or is it ok if it's just an
  //  internal cache? Unclear what "should not leak" means.
  private val injectFactoryClassIdsToInjectedClass = mutableMapOf<ClassId, InjectedClass>()
  private val injectFactoryClassIdsToSymbols = mutableMapOf<ClassId, FirClassLikeSymbol<*>>()

  class InjectedClass(
    val classSymbol: FirClassSymbol<*>,
    val constructor: FirConstructorSymbol,
    val constructorParameters: List<LatticeFirValueParameter>,
  ) {
    private val nameAllocator = NameAllocator()

    init {
      // preallocate constructor param names
      constructorParameters.forEach {
        nameAllocator.newName(it.name.asString())
      }
    }

    val assistedParameters by unsafeLazy { constructorParameters.filter { it.isAssisted } }

    val isAssisted
      get() = assistedParameters.isNotEmpty()

    val injectedMembersParameters = mutableListOf<LatticeFirValueParameter>()

    // TODO extract for member injectors generation
    @OptIn(SymbolInternals::class)
    fun populateMemberInjections(session: FirSession) {
      injectedMembersParameters +=
        classSymbol
          .allCallableMembers(session)
          .filter { callable ->
            if (callable.isAnnotatedInject(session)) {
              true
            } else if (callable is FirPropertySymbol) {
              callable.backingFieldSymbol?.isAnnotatedInject(session) == true ||
                callable.setterSymbol?.isAnnotatedInject(session) == true
            } else {
              false
            }
          }
          .flatMap {
            when (it) {
              is FirPropertySymbol ->
                it.setterSymbol?.valueParameterSymbols.orEmpty()
                  .map {
                    LatticeFirValueParameter(
                      session = session,
                      symbol = it,
                      name = nameAllocator.newName(it.name)
                    )
                  }
              is FirNamedFunctionSymbol -> {
                it.valueParameterSymbols
                  .map {
                    LatticeFirValueParameter(
                      session = session,
                      symbol = it,
                      name = nameAllocator.newName(it.name)
                    )
                  }
              }
              else -> emptyList()
            }
          }
    }

    // TODO dedupe keys?
    val allParameters by unsafeLazy {
      buildList {
        addAll(constructorParameters)
        addAll(injectedMembersParameters)
      }
    }
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    return if (
      classSymbol.isCompanion &&
        classSymbol.getContainingClassSymbol()?.origin ==
          LatticeKeys.ProviderFactoryClassDeclaration.origin
    ) {
      // It's a factory's companion object
      emptySet()
    } else if (classSymbol.classId in injectFactoryClassIdsToSymbols) {
      // It's a generated factory, give it a companion object if it isn't going to be an object
      if (classSymbol.classKind == ClassKind.OBJECT) {
        emptySet()
      } else {
        setOf(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
      }
    } else {
      // Checkers don't run first so we need to do superficial ones here before proceeding
      if (classSymbol.classKind != ClassKind.CLASS) return emptySet()

      // If the class is annotated with @Inject, look for its primary constructor
      val isClassAnnotated =
        classSymbol.isAnnotatedWithAny(session, session.latticeClassIds.injectAnnotations)
      val injectConstructor =
        if (isClassAnnotated) {
          classSymbol.declarationSymbols
            .asSequence()
            .filterIsInstance<FirConstructorSymbol>()
            .firstOrNull { it.isPrimary }
        } else {
          // If the class is not annotated with @Inject, look for an @Inject-annotated constructor
          classSymbol.declarationSymbols
            .asSequence()
            .filterIsInstance<FirConstructorSymbol>()
            .find { it.isAnnotatedWithAny(session, session.latticeClassIds.injectAnnotations) }
        }
      return if (injectConstructor != null) {
        val params =
          injectConstructor.valueParameterSymbols.map { LatticeFirValueParameter(session, it) }
        val injectedClass = InjectedClass(classSymbol, injectConstructor, params)
        injectFactoryClassIdsToInjectedClass[
          classSymbol.classId.createNestedClassId(LatticeSymbols.Names.latticeFactory)] =
          injectedClass
        setOf(LatticeSymbols.Names.latticeFactory)
      } else {
        emptySet()
      }
    }
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    return when (name) {
      SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT -> {
        // It's a factory's companion object, just generate the declaration
        createCompanionObject(owner, LatticeKeys.Default).symbol
      }
      LatticeSymbols.Names.latticeFactory -> {
        // It's a factory class itself
        val classId = owner.classId.createNestedClassId(name)
        val injectedClass = injectFactoryClassIdsToInjectedClass[classId] ?: return null

        // Populate member injections
        // TODO can this delay?
        injectedClass.populateMemberInjections(session)

        val classKind =
          if (
            injectedClass.classSymbol.typeParameterSymbols.isEmpty() &&
              injectedClass.allParameters.isEmpty()
          ) {
            ClassKind.OBJECT
          } else {
            ClassKind.CLASS
          }

        createNestedClass(
            owner,
            name.capitalizeUS(),
            LatticeKeys.InjectConstructorFactoryClassDeclaration,
            classKind = classKind,
          ) {
            // TODO what about backward-referencing type params?
            injectedClass.classSymbol.typeParameterSymbols.forEach { typeParameter ->
              typeParameter(typeParameter.name, typeParameter.variance, key = LatticeKeys.Default) {
                if (typeParameter.isBound) {
                  typeParameter.resolvedBounds.forEach { bound -> bound(bound.coneType) }
                }
              }
            }

            if (!injectedClass.isAssisted) {
              superType(
                LatticeSymbols.ClassIds.latticeFactory.constructClassLikeType(
                  arrayOf(injectedClass.classSymbol.defaultType())
                )
              )
            }
          }
          .apply { markAsDeprecatedHidden(session) }
          .symbol
          .also { injectFactoryClassIdsToSymbols[it.classId] = it }
      }
      else -> {
        null
      }
    }
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    return buildSet {
      add(SpecialNames.INIT)
      if (!classSymbol.isCompanion) {
        add(LatticeSymbols.Names.invoke)
      }
      if (classSymbol.classKind == ClassKind.OBJECT) {
        // Generate create() and newInstance headers
        add(LatticeSymbols.Names.create)
        add(LatticeSymbols.Names.newInstanceFunction)
      }
    }
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val constructor =
      if (context.owner.classKind == ClassKind.OBJECT) {
        createDefaultPrivateConstructor(context.owner, LatticeKeys.Default)
      } else {
        val injectedClass =
          injectFactoryClassIdsToInjectedClass[context.owner.classId] ?: return emptyList()
        buildFactoryConstructor(context, null, null, injectedClass.allParameters)
      }
    return listOf(constructor.symbol)
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val context = context ?: return emptyList()
    val factoryClass =
      if (context.owner.isCompanion) {
        context.owner.getContainingClassSymbol() ?: return emptyList()
      } else {
        context.owner
      }
    val factoryClassId = factoryClass.classId
    val injectedClass = injectFactoryClassIdsToInjectedClass[factoryClassId] ?: return emptyList()

    // TODO what about type params
    val returnType = injectedClass.classSymbol.defaultType()
    val function =
      when (callableId.callableName) {
        LatticeSymbols.Names.invoke -> {
          createMemberFunction(
              context.owner,
              LatticeKeys.Default,
              callableId.callableName,
              returnType,
            ) {
              if (!injectedClass.isAssisted) {
                status { isOverride = true }
              }
              injectedClass.assistedParameters.forEach { assistedParameter ->
                valueParameter(
                  assistedParameter.name,
                  assistedParameter.symbol.resolvedReturnType,
                  key = LatticeKeys.ValueParameter,
                )
              }
            }
            .symbol
        }
        LatticeSymbols.Names.create -> {
          // TODO what about type params
          val factoryClassType = factoryClass.constructType()
          val factoryClassReturnType = if (injectedClass.isAssisted) {
            factoryClassType
          } else {
            LatticeSymbols.ClassIds.latticeFactory.constructClassLikeType(arrayOf(factoryClassType))
          }
          buildFactoryCreateFunction(
            context,
            factoryClassReturnType,
            null,
            null,
            injectedClass.allParameters,
          )
        }
        LatticeSymbols.Names.newInstanceFunction -> {
          buildNewInstanceFunction(
            context,
            LatticeSymbols.Names.newInstanceFunction,
            returnType,
            null,
            null,
            injectedClass.constructorParameters,
          )
        }
        else -> {
          println("Unrecognized function $callableId")
          return emptyList()
        }
      }
    return listOf(function)
  }
}
