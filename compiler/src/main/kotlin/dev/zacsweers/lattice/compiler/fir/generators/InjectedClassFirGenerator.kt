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
import dev.zacsweers.lattice.compiler.capitalizeUS
import dev.zacsweers.lattice.compiler.fir.LatticeFirValueParameter
import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.allCallableMembers
import dev.zacsweers.lattice.compiler.fir.constructType
import dev.zacsweers.lattice.compiler.fir.hasOrigin
import dev.zacsweers.lattice.compiler.fir.isAnnotatedInject
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.lattice.compiler.mapToArray
import dev.zacsweers.lattice.compiler.newName
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
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
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
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

/** Generates factory and membersinjector declarations for `@Inject`-annotated classes. */
internal class InjectedClassFirGenerator(session: FirSession) :
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
  private val membersInjectorClassIdsToInjectedClass = mutableMapOf<ClassId, InjectedClass>()
  private val membersInjectorClassIdsToSymbols = mutableMapOf<ClassId, FirClassLikeSymbol<*>>()

  class InjectedClass(
    val classSymbol: FirClassSymbol<*>,
    val constructor: FirConstructorSymbol?,
    val constructorParameters: List<LatticeFirValueParameter>,
  ) {
    private val parameterNameAllocator = NameAllocator()
    private val memberNameAllocator = NameAllocator()
    private var injectedMembersPopulated = false

    init {
      // preallocate constructor param names
      constructorParameters.forEach { parameterNameAllocator.newName(it.name.asString()) }
    }

    val assistedParameters by unsafeLazy { constructorParameters.filter { it.isAssisted } }

    val isAssisted
      get() = assistedParameters.isNotEmpty()

    val injectedMembersParamsByMemberKey = LinkedHashMap<Name, List<LatticeFirValueParameter>>()
    val injectedMembersParameters by unsafeLazy {
      injectedMembersParamsByMemberKey.values.flatten()
    }

    @OptIn(SymbolInternals::class)
    fun populateMemberInjections(session: FirSession) {
      if (injectedMembersPopulated) return
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
        .forEach {
          when (it) {
            is FirPropertySymbol -> {
              val propertyName = it.name
              val setterSymbol = it.setterSymbol
              val fieldSymbol = it.backingFieldSymbol
              val param =
                if (setterSymbol != null) {
                  val setterParam = setterSymbol.valueParameterSymbols.single()
                  LatticeFirValueParameter(
                    session = session,
                    symbol = setterParam,
                    name = parameterNameAllocator.newName(propertyName),
                    memberKey = memberNameAllocator.newName(propertyName),
                  )
                } else if (fieldSymbol != null) {
                  LatticeFirValueParameter(
                    session = session,
                    symbol = fieldSymbol,
                    name = parameterNameAllocator.newName(propertyName),
                    memberKey = memberNameAllocator.newName(propertyName),
                  )
                } else {
                  return@forEach
                }
              injectedMembersParamsByMemberKey[param.memberInjectorFunctionName] = listOf(param)
            }
            is FirNamedFunctionSymbol -> {
              val functionName = it.name
              val memberKey = memberNameAllocator.newName(functionName)
              val params =
                it.valueParameterSymbols.map {
                  LatticeFirValueParameter(
                    session = session,
                    symbol = it,
                    name = parameterNameAllocator.newName(it.name),
                    memberKey = memberKey,
                  )
                }
              // Guaranteed at least one param if we're generating here
              injectedMembersParamsByMemberKey[params[0].memberInjectorFunctionName] = params
            }
          }
        }
      injectedMembersPopulated = true
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
      classSymbol.hasOrigin(
        LatticeKeys.InjectConstructorFactoryCompanionDeclaration,
        LatticeKeys.MembersInjectorCompanionDeclaration,
      )
    ) {
      // It's a factory's companion object
      emptySet()
    } else if (
      classSymbol.classId.let {
        it in injectFactoryClassIdsToSymbols || it in membersInjectorClassIdsToSymbols
      }
    ) {
      // It's a generated factory/injector, give it a companion object if it isn't going to be an
      // object
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
      val params =
        injectConstructor?.valueParameterSymbols.orEmpty().map {
          LatticeFirValueParameter(session, it)
        }
      val injectedClass = InjectedClass(classSymbol, injectConstructor, params)
      injectedClass.populateMemberInjections(session)

      val classesToGenerate = mutableSetOf<Name>()
      if (injectConstructor != null) {
        val classId = classSymbol.classId.createNestedClassId(LatticeSymbols.Names.latticeFactory)
        injectFactoryClassIdsToInjectedClass[classId] = injectedClass
        classesToGenerate += classId.shortClassName
      }
      if (injectedClass.injectedMembersParameters.isNotEmpty()) {
        val classId =
          classSymbol.classId.createNestedClassId(LatticeSymbols.Names.latticeMembersInjector)
        membersInjectorClassIdsToInjectedClass[classId] = injectedClass
        classesToGenerate += classId.shortClassName
      }
      return classesToGenerate
    }
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    return when (name) {
      SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT -> {
        val companionKey =
          if (owner.hasOrigin(LatticeKeys.InjectConstructorFactoryClassDeclaration)) {
            LatticeKeys.InjectConstructorFactoryCompanionDeclaration
          } else if (owner.hasOrigin(LatticeKeys.MembersInjectorClassDeclaration)) {
            LatticeKeys.MembersInjectorCompanionDeclaration
          } else {
            return null
          }
        // It's a factory's companion object, just generate the declaration
        createCompanionObject(owner, companionKey).symbol
      }
      LatticeSymbols.Names.latticeFactory -> {
        val classId = owner.classId.createNestedClassId(name)
        val injectedClass = injectFactoryClassIdsToInjectedClass[classId] ?: return null

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
              superType { typeParameterRefs ->
                LatticeSymbols.ClassIds.latticeFactory.constructClassLikeType(
                  arrayOf(owner.constructType(typeParameterRefs))
                )
              }
            }
          }
          .apply { markAsDeprecatedHidden(session) }
          .symbol
          .also { injectFactoryClassIdsToSymbols[it.classId] = it }
      }
      LatticeSymbols.Names.latticeMembersInjector -> {
        val classId = owner.classId.createNestedClassId(name)
        val injectedClass = membersInjectorClassIdsToInjectedClass[classId] ?: return null

        createNestedClass(
            owner,
            name.capitalizeUS(),
            LatticeKeys.MembersInjectorClassDeclaration,
          ) {
            // TODO what about backward-referencing type params?
            injectedClass.classSymbol.typeParameterSymbols.forEach { typeParameter ->
              typeParameter(typeParameter.name, typeParameter.variance, key = LatticeKeys.Default) {
                if (typeParameter.isBound) {
                  typeParameter.resolvedBounds.forEach { bound -> bound(bound.coneType) }
                }
              }
            }

            superType { typeParameterRefs ->
              LatticeSymbols.ClassIds.membersInjector.constructClassLikeType(
                arrayOf(owner.constructType(typeParameterRefs))
              )
            }
          }
          .apply { markAsDeprecatedHidden(session) }
          .symbol
          .also { membersInjectorClassIdsToSymbols[it.classId] = it }
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
    val names = mutableSetOf<Name>()
    names += SpecialNames.INIT

    val isCompanion = classSymbol.isCompanion

    // Factory class
    // Factory (companion) object
    val isFactoryClass = classSymbol.hasOrigin(LatticeKeys.InjectConstructorFactoryClassDeclaration)
    val isFactoryCreatorClass =
      (isFactoryClass && isCompanion) ||
        classSymbol.hasOrigin(LatticeKeys.InjectConstructorFactoryCompanionDeclaration)
    if (isFactoryClass) {
      names += LatticeSymbols.Names.invoke
    }
    if (isFactoryCreatorClass) {
      names += LatticeSymbols.Names.create
      names += LatticeSymbols.Names.newInstanceFunction
    }

    // MembersInjector class
    // MembersInjector companion object
    val isInjectorClass = classSymbol.hasOrigin(LatticeKeys.MembersInjectorClassDeclaration)
    val isInjectorCreatorClass =
      classSymbol.hasOrigin(LatticeKeys.MembersInjectorCompanionDeclaration)
    if (isInjectorClass) {
      names += LatticeSymbols.Names.injectMembers
    }
    if (isInjectorCreatorClass) {
      names += LatticeSymbols.Names.create
      val targetClass =
        classSymbol.getContainingClassSymbol()?.classId
          ?: return emptySet()
      val injectedClass = membersInjectorClassIdsToInjectedClass[targetClass] ?: return emptySet()
      for (member in injectedClass.injectedMembersParameters) {
        names += member.memberInjectorFunctionName
      }
    }

    return names
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val constructor =
      if (context.owner.classKind == ClassKind.OBJECT) {
        createDefaultPrivateConstructor(context.owner, LatticeKeys.Default)
      } else if (context.owner.hasOrigin(LatticeKeys.InjectConstructorFactoryClassDeclaration)) {
        val injectedClass =
          injectFactoryClassIdsToInjectedClass[context.owner.classId] ?: return emptyList()
        buildFactoryConstructor(context, null, null, injectedClass.allParameters)
      } else if (context.owner.hasOrigin(LatticeKeys.MembersInjectorClassDeclaration)) {
        val injectedClass =
          membersInjectorClassIdsToInjectedClass[context.owner.classId] ?: return emptyList()
        buildFactoryConstructor(context, null, null, injectedClass.injectedMembersParameters)
      } else {
        return emptyList()
      }
    return listOf(constructor.symbol)
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val context = context ?: return emptyList()

    val targetClass =
      if (context.owner.isCompanion) {
        context.owner.getContainingClassSymbol() ?: return emptyList()
      } else {
        context.owner
      }
    val targetClassId = targetClass.classId

    val functions = mutableListOf<FirNamedFunctionSymbol>()
    if (targetClass.hasOrigin(LatticeKeys.InjectConstructorFactoryClassDeclaration)) {
      val injectedClass = injectFactoryClassIdsToInjectedClass[targetClassId] ?: return emptyList()

      val returnType = injectedClass.classSymbol.defaultType()
      functions +=
        when (callableId.callableName) {
          LatticeSymbols.Names.invoke -> {
            createMemberFunction(
                owner = context.owner,
                key = LatticeKeys.Default,
                name = callableId.callableName,
                returnTypeProvider = { injectedClass.classSymbol.constructType(it) },
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
            buildFactoryCreateFunction(
              context,
              {
                if (injectedClass.isAssisted) {
                  targetClass.constructType(it.mapToArray { it.toConeType() })
                } else {
                  LatticeSymbols.ClassIds.latticeFactory.constructClassLikeType(
                    arrayOf(injectedClass.classSymbol.constructType(it.mapToArray { it.toConeType() }))
                  )
                }
              },
              null,
              null,
              injectedClass.allParameters,
            )
          }
          LatticeSymbols.Names.newInstanceFunction -> {
            // TODO type params
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
    } else if (targetClass.hasOrigin(LatticeKeys.MembersInjectorClassDeclaration)) {
      val injectedClass =
        membersInjectorClassIdsToInjectedClass[targetClassId] ?: return emptyList()

      functions +=
        when (callableId.callableName) {
          LatticeSymbols.Names.injectMembers -> {
            createMemberFunction(
                owner = context.owner,
                key = LatticeKeys.Default,
                name = callableId.callableName,
                returnType = session.builtinTypes.unitType.coneType,
              ) {
                status { isOverride = true }
                valueParameter(
                  LatticeSymbols.Names.instance,
                  typeProvider = { injectedClass.classSymbol.constructType(it) },
                  key = LatticeKeys.ValueParameter,
                )
              }
              .symbol
          }
          LatticeSymbols.Names.create -> {
            buildFactoryCreateFunction(
              context,
              {
                val targetClassType = targetClass.constructType(it.mapToArray { it.toConeType() })
                LatticeSymbols.ClassIds.membersInjector.constructClassLikeType(
                  arrayOf(targetClassType)
                )
              },
              null,
              null,
              injectedClass.allParameters,
            )
          }
          else -> {
            val parameters =
              injectedClass.injectedMembersParamsByMemberKey[callableId.callableName]
                ?: return emptyList()

            // It's a member injector name
            createMemberFunction(
                owner = context.owner,
                key = LatticeKeys.MembersInjectorStaticInjectFunction,
                name = callableId.callableName,
                returnType = session.builtinTypes.unitType.coneType,
              ) {
                // Add instance param
                valueParameter(
                  LatticeSymbols.Names.instance,
                  typeProvider = { injectedClass.classSymbol.constructType(it) },
                  key = LatticeKeys.ValueParameter, // Or should this be instance?
                )
                // Add its parameters
                for (param in parameters) {
                  valueParameter(
                    param.name,
                    param.symbol.resolvedReturnType,
                    key = LatticeKeys.ValueParameter,
                  )
                }
              }
              .symbol
          }
        }
    }
    return functions
  }
}
