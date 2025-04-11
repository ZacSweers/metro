// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.MetroFirValueParameter
import dev.zacsweers.metro.compiler.fir.buildSimpleAnnotation
import dev.zacsweers.metro.compiler.fir.callableDeclarations
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.constructType
import dev.zacsweers.metro.compiler.fir.findInjectConstructors
import dev.zacsweers.metro.compiler.fir.hasOrigin
import dev.zacsweers.metro.compiler.fir.isAnnotatedInject
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.fir.wrapInProviderIfNecessary
import dev.zacsweers.metro.compiler.mapToArray
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.newName
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isLateInit
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension.TypeResolveService
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createDefaultPrivateConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.plugin.createTopLevelClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.withArguments
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.types.ConstantValueKind

/** Generates factory and membersinjector declarations for `@Inject`-annotated classes. */
internal class InjectedClassFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(session.predicates.injectAndAssistedAnnotationPredicate)
  }

  private val symbols: FirCache<Unit, Map<ClassId, FirNamedFunctionSymbol>, TypeResolveService?> =
    session.firCachesFactory.createCache { _, _ ->
      session.predicateBasedProvider
        .getSymbolsByPredicate(session.predicates.injectAndAssistedAnnotationPredicate)
        .filterIsInstance<FirNamedFunctionSymbol>()
        .filter { it.callableId.classId == null }
        .associateBy {
          ClassId(
            it.callableId.packageName,
            "${it.callableId.callableName.capitalizeUS()}Class".asName(),
          )
        }
    }

  /**
   * For top-level `@Inject`-annotated functions we generate synthetic classes.
   *
   * ```
   * @Inject
   * fun App(message: String) {
   *   // ...
   * }
   * ```
   *
   * Will generate
   *
   * ```
   * class AppClass @Inject constructor(private val message: String) {
   *   operator fun invoke() {
   *     App(message)
   *   }
   * }
   * ```
   *
   * Annotations and `suspend` modifiers will be copied over as well.
   */
  // TODO
  //  private works
  //  visibility of params and return type
  //  no extension receivers
  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> {
    if (!session.metroFirBuiltIns.options.enableTopLevelFunctionInjection) return emptySet()
    return symbols.getValue(Unit, null).keys
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    if (!session.metroFirBuiltIns.options.enableTopLevelFunctionInjection) return null
    val function = symbols.getValue(Unit, null).getValue(classId)
    val annotations = function.metroAnnotations(session)
    return createTopLevelClass(classId, Keys.TopLevelInjectFunctionClass)
      .apply {
        replaceAnnotationsSafe(
          buildList {
            add(buildInjectAnnotation())
            add(buildInjectedFunctionClassAnnotation(function.callableId))
            annotations.qualifier?.fir?.let(::add)
            annotations.scope?.fir?.let(::add)
            if (annotations.isComposable) {
              add(buildStableAnnotation())
            }
          }
        )
      }
      .symbol
  }

  // TODO apparently writing these types of caches is bad and
  //  generate* functions should be side-effect-free, but honestly
  //  how is this practical without this? Or is it ok if it's just an
  //  internal cache? Unclear what "should not leak" means.
  //  Or use session.firCachesFactory.createCache?
  private val injectFactoryClassIdsToInjectedClass = mutableMapOf<ClassId, InjectedClass>()
  private val injectFactoryClassIdsToSymbols = mutableMapOf<ClassId, FirClassLikeSymbol<*>>()
  private val membersInjectorClassIdsToInjectedClass = mutableMapOf<ClassId, InjectedClass>()
  private val membersInjectorClassIdsToSymbols = mutableMapOf<ClassId, FirClassLikeSymbol<*>>()

  class InjectedClass(
    val classSymbol: FirClassSymbol<*>,
    var isConstructorInjected: Boolean,
    val constructorParameters: List<MetroFirValueParameter>,
  ) {
    private val parameterNameAllocator = dev.zacsweers.metro.compiler.NameAllocator()
    private val memberNameAllocator =
      dev.zacsweers.metro.compiler.NameAllocator(
        mode = dev.zacsweers.metro.compiler.NameAllocator.Mode.COUNT
      )
    private var declaredInjectedMembersPopulated = false
    private var ancestorInjectedMembersPopulated = false

    init {
      // preallocate constructor param names
      constructorParameters.forEach { parameterNameAllocator.newName(it.name.asString()) }
    }

    val assistedParameters: List<MetroFirValueParameter> by unsafeLazy {
      constructorParameters.filter { it.isAssisted }
    }

    val isAssisted
      get() = assistedParameters.isNotEmpty()

    val injectedMembersParamsByMemberKey = LinkedHashMap<Name, List<MetroFirValueParameter>>()
    val injectedMembersParameters: List<MetroFirValueParameter>
      get() = injectedMembersParamsByMemberKey.values.flatten()

    // TODO dedupe keys?
    val allParameters: List<MetroFirValueParameter>
      get() = buildList {
        addAll(constructorParameters)
        addAll(injectedMembersParameters)
      }

    override fun toString(): String {
      return buildString {
        append(classSymbol.classId)
        if (isConstructorInjected) {
          append(" (constructor)")
          if (constructorParameters.isNotEmpty()) {
            append(" constructorParams=$constructorParameters")
          }
        }
        if (injectedMembersParamsByMemberKey.isNotEmpty()) {
          append(" injectedMembers=${injectedMembersParamsByMemberKey.keys}")
        }
      }
    }

    fun populateDeclaredMemberInjections(
      session: FirSession
    ): Map<Name, List<MetroFirValueParameter>> {
      if (declaredInjectedMembersPopulated) return injectedMembersParamsByMemberKey
      val declared = memberInjections(session, includeSelf = true, includeAncestors = false)
      injectedMembersParamsByMemberKey.putAll(declared)
      declaredInjectedMembersPopulated = true
      return declared
    }

    fun populateAncestorMemberInjections(session: FirSession) {
      if (ancestorInjectedMembersPopulated) return
      val declared = injectedMembersParamsByMemberKey.toMap()
      injectedMembersParamsByMemberKey.clear()
      // Put ancestors first
      injectedMembersParamsByMemberKey.putAll(
        memberInjections(session, includeSelf = false, includeAncestors = true)
      )
      injectedMembersParamsByMemberKey.putAll(declared)
      ancestorInjectedMembersPopulated = true
    }

    private fun memberInjections(
      session: FirSession,
      includeSelf: Boolean,
      includeAncestors: Boolean,
    ): Map<Name, List<MetroFirValueParameter>> {
      val members = LinkedHashMap<Name, List<MetroFirValueParameter>>()
      classSymbol
        .callableDeclarations(
          session,
          includeSelf = includeSelf,
          includeAncestors = includeAncestors,
        )
        .filter { callable ->
          if (callable is FirPropertySymbol) {
            if (!(callable.isVar || callable.isLateInit)) {
              return@filter false
            }
          }
          if (callable.isAnnotatedInject(session)) {
            true
          } else if (callable is FirPropertySymbol) {
            callable.backingFieldSymbol?.isAnnotatedInject(session) == true ||
              callable.setterSymbol?.isAnnotatedInject(session) == true
          } else {
            false
          }
        }
        .forEach { injectedMember ->
          when (injectedMember) {
            is FirPropertySymbol -> {
              val propertyName = injectedMember.name
              val setterSymbol = injectedMember.setterSymbol
              val fieldSymbol = injectedMember.backingFieldSymbol
              val param =
                if (setterSymbol != null) {
                  val setterParam = setterSymbol.valueParameterSymbols.single()
                  MetroFirValueParameter(
                    session = session,
                    symbol = setterParam,
                    name = parameterNameAllocator.newName(propertyName),
                    memberKey = memberNameAllocator.newName(propertyName),
                  )
                } else if (fieldSymbol != null) {
                  MetroFirValueParameter(
                    session = session,
                    symbol = fieldSymbol,
                    name = parameterNameAllocator.newName(propertyName),
                    memberKey = memberNameAllocator.newName(propertyName),
                  )
                } else {
                  return@forEach
                }
              members[param.memberInjectorFunctionName] = listOf(param)
            }
            is FirNamedFunctionSymbol -> {
              val functionName = injectedMember.name
              val memberKey = memberNameAllocator.newName(functionName)
              val params =
                injectedMember.valueParameterSymbols.map {
                  MetroFirValueParameter(
                    session = session,
                    symbol = it,
                    name = parameterNameAllocator.newName(it.name),
                    memberKey = memberKey,
                  )
                }
              // Guaranteed at least one param if we're generating here
              members[params[0].memberInjectorFunctionName] = params
            }
          }
        }
      return members
    }
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    return if (
      classSymbol.hasOrigin(
        Keys.InjectConstructorFactoryCompanionDeclaration,
        Keys.MembersInjectorCompanionDeclaration,
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

      val injectedClass =
        if (classSymbol.hasOrigin(Keys.TopLevelInjectFunctionClass)) {
          val function = functionFor(classSymbol.classId)
          val params =
            function.valueParameterSymbols
              .filterNot { it.isAnnotatedWithAny(session, session.classIds.assistedAnnotations) }
              .map { MetroFirValueParameter(session, it, wrapInProvider = true) }
          InjectedClass(classSymbol, true, params)
        } else {
          // If the class is annotated with @Inject, look for its primary constructor
          val injectConstructor = classSymbol.findInjectConstructors(session).singleOrNull()
          val params =
            injectConstructor?.valueParameterSymbols.orEmpty().map {
              MetroFirValueParameter(session, it)
            }
          InjectedClass(classSymbol, injectConstructor != null, params)
        }

      // Ancestors not available at this phase, but we don't need them here anyway
      val declaredInjectedMembers = injectedClass.populateDeclaredMemberInjections(session)

      val classesToGenerate = mutableSetOf<Name>()
      if (injectedClass.isConstructorInjected) {
        val classId = classSymbol.classId.createNestedClassId(Symbols.Names.metroFactory)
        injectFactoryClassIdsToInjectedClass[classId] = injectedClass
        classesToGenerate += classId.shortClassName
      }
      if (declaredInjectedMembers.isNotEmpty()) {
        val classId = classSymbol.classId.createNestedClassId(Symbols.Names.metroMembersInjector)
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
          if (owner.hasOrigin(Keys.InjectConstructorFactoryClassDeclaration)) {
            Keys.InjectConstructorFactoryCompanionDeclaration
          } else if (owner.hasOrigin(Keys.MembersInjectorClassDeclaration)) {
            Keys.MembersInjectorCompanionDeclaration
          } else {
            return null
          }
        // It's a factory's companion object, just generate the declaration
        createCompanionObject(owner, companionKey).symbol
      }
      Symbols.Names.metroFactory -> {
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
            Keys.InjectConstructorFactoryClassDeclaration,
            classKind = classKind,
          ) {
            // TODO what about backward-referencing type params?
            injectedClass.classSymbol.typeParameterSymbols.forEach { typeParameter ->
              typeParameter(typeParameter.name, typeParameter.variance, key = Keys.Default) {
                if (typeParameter.isBound) {
                  typeParameter.resolvedBounds.forEach { bound -> bound(bound.coneType) }
                }
              }
            }

            if (!injectedClass.isAssisted) {
              superType { typeParameterRefs ->
                Symbols.ClassIds.metroFactory.constructClassLikeType(
                  arrayOf(owner.constructType(typeParameterRefs))
                )
              }
            }
          }
          .apply { markAsDeprecatedHidden(session) }
          .symbol
          .also { injectFactoryClassIdsToSymbols[it.classId] = it }
      }
      Symbols.Names.metroMembersInjector -> {
        val classId = owner.classId.createNestedClassId(name)
        val injectedClass = membersInjectorClassIdsToInjectedClass[classId] ?: return null

        createNestedClass(owner, name.capitalizeUS(), Keys.MembersInjectorClassDeclaration) {
            // TODO what about backward-referencing type params?
            injectedClass.classSymbol.typeParameterSymbols.forEach { typeParameter ->
              typeParameter(typeParameter.name, typeParameter.variance, key = Keys.Default) {
                if (typeParameter.isBound) {
                  typeParameter.resolvedBounds.forEach { bound -> bound(bound.coneType) }
                }
              }
            }

            superType { typeParameterRefs ->
              Symbols.ClassIds.membersInjector.constructClassLikeType(
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
    if (classSymbol.hasOrigin(Keys.TopLevelInjectFunctionClass)) {
      return setOf(SpecialNames.INIT, Symbols.Names.invoke)
    }

    val isFactoryClass = classSymbol.hasOrigin(Keys.InjectConstructorFactoryClassDeclaration)
    val isObject = classSymbol.classKind == ClassKind.OBJECT
    val isFactoryCreatorClass =
      (isFactoryClass && isObject) ||
        classSymbol.hasOrigin(Keys.InjectConstructorFactoryCompanionDeclaration)
    val isInjectorClass = classSymbol.hasOrigin(Keys.MembersInjectorClassDeclaration)
    val isInjectorCreatorClass = classSymbol.hasOrigin(Keys.MembersInjectorCompanionDeclaration)

    if (!isFactoryClass && !isFactoryCreatorClass && !isInjectorCreatorClass && !isInjectorClass) {
      return emptySet()
    }

    val names = mutableSetOf<Name>()
    names += SpecialNames.INIT

    // Factory class
    // Factory (companion) object
    if (isFactoryClass) {
      // Only generate an invoke() function if it has assisted parameters, as it won't be inherited
      // from Factory<T> in this case
      val target = injectFactoryClassIdsToInjectedClass[classSymbol.classId]?.classSymbol
      val injectConstructor = target?.findInjectConstructors(session).orEmpty().singleOrNull()
      if (
        injectConstructor != null &&
          injectConstructor.valueParameterSymbols.any {
            it.isAnnotatedWithAny(session, session.classIds.assistedAnnotations)
          }
      ) {
        names += Symbols.Names.invoke
      }
    }
    if (isFactoryCreatorClass) {
      names += Symbols.Names.create
      names += Symbols.Names.newInstanceFunction
    }

    // MembersInjector class
    // MembersInjector companion object
    if (isInjectorCreatorClass) {
      names += Symbols.Names.create
      val targetClass = classSymbol.getContainingClassSymbol()?.classId ?: return emptySet()
      val injectedClass = membersInjectorClassIdsToInjectedClass[targetClass] ?: return emptySet()
      // Only declared members matter here
      for (member in injectedClass.injectedMembersParameters) {
        names += member.memberInjectorFunctionName
      }
    }

    return names
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    if (context.owner.hasOrigin(Keys.TopLevelInjectFunctionClass)) {
      val function = functionFor(context.owner.classId)
      val nonAssistedParams =
        function.valueParameterSymbols
          .filterNot { it.isAnnotatedWithAny(session, session.classIds.assistedAnnotations) }
          .map { MetroFirValueParameter(session, it) }
      return createConstructor(
          context.owner,
          Keys.Default,
          isPrimary = true,
          generateDelegatedNoArgConstructorCall = true,
        ) {
          for (param in nonAssistedParams) {
            valueParameter(
              param.name,
              typeProvider = {
                param.contextKey.typeKey.type
                  // TODO need to remap these
                  //  .withArguments(it.mapToArray(FirTypeParameterRef::toConeType))
                  .wrapInProviderIfNecessary(session)
              },
              key = Keys.ValueParameter,
            )
          }
        }
        .apply {
          for ((i, param) in valueParameters.withIndex()) {
            val metroParam = nonAssistedParams[i]
            param.replaceAnnotationsSafe(
              buildList {
                addAll(param.annotations)
                metroParam.contextKey.typeKey.qualifier?.let { add(it.fir) }
              }
            )
          }
        }
        .symbol
        .let(::listOf)
    }

    val constructor =
      if (context.owner.classKind == ClassKind.OBJECT) {
        createDefaultPrivateConstructor(context.owner, Keys.Default)
      } else if (context.owner.hasOrigin(Keys.InjectConstructorFactoryClassDeclaration)) {
        val injectedClass =
          injectFactoryClassIdsToInjectedClass[context.owner.classId] ?: return emptyList()
        injectedClass.populateAncestorMemberInjections(session)
        buildFactoryConstructor(context, null, null, injectedClass.allParameters)
      } else if (context.owner.hasOrigin(Keys.MembersInjectorClassDeclaration)) {
        val injectedClass =
          membersInjectorClassIdsToInjectedClass[context.owner.classId] ?: return emptyList()
        injectedClass.populateAncestorMemberInjections(session)
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
    val nonNullContext = context ?: return emptyList()

    if (nonNullContext.owner.hasOrigin(Keys.TopLevelInjectFunctionClass)) {
      check(callableId.callableName == Symbols.Names.invoke)
      val function = symbols.getValue(Unit, null).getValue(context.owner.classId)
      // TODO default param values probably require generateMemberFunction
      return createMemberFunction(
          nonNullContext.owner,
          Keys.TopLevelInjectFunctionClassFunction,
          callableId.callableName,
          returnTypeProvider = {
            function.resolvedReturnType
            // TODO need to remap these
            //  .withArguments(it.mapToArray(FirTypeParameterRef::toConeType))
          },
        ) {
          status {
            isOperator = true
            isSuspend = function.isSuspend
            // TODO others?
          }

          for (param in function.valueParameterSymbols) {
            if (!param.isAnnotatedWithAny(session, session.classIds.assistedAnnotations)) {
              continue
            }
            valueParameter(
              param.name,
              typeProvider = {
                param.resolvedReturnType
                // TODO need to remap these
                //  .withArguments(it.mapToArray(FirTypeParameterRef::toConeType))
              },
              key = Keys.ValueParameter,
            )
          }
        }
        .apply {
          if (function.hasAnnotation(Symbols.ClassIds.composable, session)) {
            replaceAnnotationsSafe(listOf(buildComposableAnnotation(), buildNonRestartableAnnotation()))
          }
        }
        .symbol
        .let(::listOf)
    }

    val targetClass =
      if (nonNullContext.owner.isCompanion) {
        nonNullContext.owner.getContainingClassSymbol() ?: return emptyList()
      } else {
        nonNullContext.owner
      }
    val targetClassId = targetClass.classId

    val functions = mutableListOf<FirNamedFunctionSymbol>()
    if (targetClass.hasOrigin(Keys.InjectConstructorFactoryClassDeclaration)) {
      val injectedClass = injectFactoryClassIdsToInjectedClass[targetClassId] ?: return emptyList()

      val returnType = injectedClass.classSymbol.defaultType()
      functions +=
        when (callableId.callableName) {
          Symbols.Names.invoke -> {
            // Assisted types do not inherit from Factory<T>, so we need to generate invoke here
            createMemberFunction(
                owner = nonNullContext.owner,
                key = Keys.Default,
                name = callableId.callableName,
                returnTypeProvider = {
                  injectedClass.classSymbol.constructType(
                    nonNullContext.owner.typeParameterSymbols.mapToArray(
                      FirTypeParameterSymbol::toConeType
                    )
                  )
                },
              ) {
                injectedClass.assistedParameters.forEach { assistedParameter ->
                  valueParameter(
                    assistedParameter.name,
                    assistedParameter.symbol.resolvedReturnType,
                    key = Keys.ValueParameter,
                  )
                }
              }
              .symbol
          }
          Symbols.Names.create -> {
            buildFactoryCreateFunction(
              nonNullContext,
              {
                if (injectedClass.isAssisted) {
                  targetClass.constructType(it.mapToArray(FirTypeParameterRef::toConeType))
                } else {
                  Symbols.ClassIds.metroFactory.constructClassLikeType(
                    arrayOf(
                      injectedClass.classSymbol.constructType(
                        it.mapToArray(FirTypeParameterRef::toConeType)
                      )
                    )
                  )
                }
              },
              null,
              null,
              injectedClass.allParameters,
            )
          }
          Symbols.Names.newInstanceFunction -> {
            buildNewInstanceFunction(
              nonNullContext,
              Symbols.Names.newInstanceFunction,
              returnType,
              null,
              null,
              injectedClass.constructorParameters,
            )
          }
          else -> {
            error("Unrecognized function $callableId")
          }
        }
    } else if (targetClass.hasOrigin(Keys.MembersInjectorClassDeclaration)) {
      val injectedClass =
        membersInjectorClassIdsToInjectedClass[targetClassId] ?: return emptyList()
      injectedClass.populateAncestorMemberInjections(session)
      functions +=
        when (callableId.callableName) {
          Symbols.Names.create -> {
            buildFactoryCreateFunction(
              nonNullContext,
              {
                val targetClassType =
                  targetClass.constructType(it.mapToArray(FirTypeParameterRef::toConeType))
                Symbols.ClassIds.membersInjector.constructClassLikeType(arrayOf(targetClassType))
              },
              null,
              null,
              injectedClass.injectedMembersParameters,
            )
          }
          else -> {
            val parameters =
              injectedClass.injectedMembersParamsByMemberKey[callableId.callableName]
                ?: return emptyList()

            // It's a member injector name
            createMemberFunction(
                owner = nonNullContext.owner,
                key = Keys.MembersInjectorStaticInjectFunction,
                name = callableId.callableName,
                returnType = session.builtinTypes.unitType.coneType,
              ) {
                // Add any type args if necessary
                injectedClass.classSymbol.typeParameterSymbols.forEach { typeParameter ->
                  typeParameter(typeParameter.name, typeParameter.variance, key = Keys.Default) {
                    if (typeParameter.isBound) {
                      typeParameter.resolvedBounds.forEach { bound -> bound(bound.coneType) }
                    }
                  }
                }

                // Add instance param
                valueParameter(
                  Symbols.Names.instance,
                  typeProvider = injectedClass.classSymbol::constructType,
                  key = Keys.ValueParameter, // Or should this be instance?
                )
                // Add its parameters
                for (param in parameters) {
                  valueParameter(
                    param.name,
                    typeProvider = { typeParameters ->
                      // TODO this is hacky at best. Look into ConeSubstitutor
                      val resolvedType = param.symbol.resolvedReturnType
                      if (typeParameters.isEmpty()) {
                        resolvedType
                      } else if (
                        resolvedType.typeArguments.none { it.type is ConeTypeParameterType }
                      ) {
                        resolvedType
                      } else {
                        val availableTypes = typeParameters.associateBy { it.symbol.name }
                        val finalTypeParameters =
                          resolvedType.typeArguments.map { typeArg ->
                            val typeArgType = typeArg.type ?: return@map typeArg
                            if (typeArgType is ConeTypeParameterType) {
                              availableTypes[typeArgType.lookupTag.name]?.toConeType()
                                ?: typeArgType
                            } else {
                              typeArgType
                            }
                          }
                        resolvedType.withArguments(finalTypeParameters.toTypedArray())
                      }
                    },
                    key = Keys.ValueParameter,
                  )
                }
              }
              .symbol
          }
        }
    }
    return functions
  }

  private fun functionFor(classId: ClassId) =
    functionForOrNullable(classId) ?: error("No injected function for $classId")

  private fun functionForOrNullable(classId: ClassId) = symbols.getValue(Unit, null)[classId]

  private fun buildInjectAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.injectClassSymbol }
  }

  private fun buildComposableAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.composableClassSymbol }
  }

  private fun buildStableAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.stableClassSymbol }
  }

  private fun buildNonRestartableAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.nonRestartableComposable }
  }

  private fun buildInjectedFunctionClassAnnotation(callableId: CallableId): FirAnnotation {
    return buildAnnotation {
      val anno = session.metroFirBuiltIns.injectedFunctionClassClassSymbol

      annotationTypeRef = anno.defaultType().toFirResolvedTypeRef()

      argumentMapping = buildAnnotationArgumentMapping {
        mapping[Name.identifier("callableName")] =
          buildLiteralExpression(
            source = null,
            kind = ConstantValueKind.String,
            value = callableId.callableName.asString(),
            annotations = null,
            setType = true,
            prefix = null,
          )
      }
    }
  }
}
