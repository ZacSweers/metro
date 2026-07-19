// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.GeneratedInjectClassData
import dev.zacsweers.metro.compiler.api.fir.MetroContributionHintExtension
import dev.zacsweers.metro.compiler.api.fir.MetroContributionHintExtension.ContributionHint
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.api.fir.MetroOriginData
import dev.zacsweers.metro.compiler.api.fir.metroGeneratedInjectClassData
import dev.zacsweers.metro.compiler.api.fir.metroOriginData
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.FirContextualTypeKey
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.caching
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.findInjectLikeConstructors
import dev.zacsweers.metro.compiler.fir.implements
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.fir.resolveClassId
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataKey
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataRegistry
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildGetClassCall
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.plugin.createTopLevelClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirImplicitTypeRef
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * FIR extension that generates Circuit factory classes for `@CircuitInject`-annotated elements.
 *
 * For top-level functions, generates a top-level factory class. For classes, generates a nested
 * `Factory` class.
 *
 * Generated factories are annotated with:
 * - `@Inject` (for Metro to generate the factory's own factory)
 * - `@ContributesIntoSet(scope)` (for Metro to contribute it to the graph)
 * - `@Origin(originClass)` (for Metro to track the origin)
 */
@OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class)
public class CircuitFirExtension(session: FirSession, compatContext: CompatContext) :
  MetroFirDeclarationGenerationExtension(session),
  MetroContributionHintExtension,
  CompatContext by compatContext {

  private val symbols by lazy { session.circuitFirSymbols }

  // Caches for discovered Circuit-family annotated elements.
  private val annotatedSymbols: List<FirBasedSymbol<*>> by lazy {
    findCircuitInjectSymbols(session)
  }

  private val annotatedClasses: Set<AnnotatedCircuitClass> by lazy {
    annotatedSymbols
      .filterIsInstance<FirRegularClassSymbol>()
      // Only read actual declarations to avoid duplicate, plus that's what IR sees
      .filterNot { it.rawStatus.isExpect }
      .flatMapTo(mutableSetOf()) { symbol ->
        CircuitCodegenTarget.entries.mapNotNull { target ->
          val annotation =
            symbol.annotationsIn(session, setOf(target.injectAnnotation)).firstOrNull()
          if (annotation == null) null else AnnotatedCircuitClass(symbol, target)
        }
      }
  }

  private val annotatedFunctions: List<AnnotatedCircuitFunction> by lazy {
    annotatedSymbols
      .filterIsInstance<FirNamedFunctionSymbol>()
      .filter { it.callableId.classId == null } // Only top-level functions
      .filterNot { it.rawStatus.isExpect }
      .flatMap { symbol ->
        CircuitCodegenTarget.entries.mapNotNull { target ->
          val annotation =
            symbol.annotationsIn(session, setOf(target.injectAnnotation)).firstOrNull()
          if (annotation == null) null else AnnotatedCircuitFunction(symbol, target)
        }
      }
  }

  // Map from factory ClassId -> annotated function (for top-level function factories)
  // Purposefully not FirNamedFunctionSymbol for compat reasons. TODO move in 2.3.20
  private val functionFactoryClassIds = mutableMapOf<ClassId, AnnotatedCircuitFunction>()

  // Track generated factory ClassIds for callable generation
  private val generatedFactoryClassIds = mutableSetOf<ClassId>()

  // Cache computed targets during class generation. Nullable values cache failed lookups to avoid
  // recomputation.
  private val computedTargets = mutableMapOf<ClassId, CircuitFactoryTarget?>()

  private val typeResolverFactory by lazy { MetroFirTypeResolver.Factory(session).caching() }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(CircuitSymbols.circuitInjectPredicate)
    register(session.predicates.assistedAnnotationPredicate)
    register(session.predicates.assistedFactoryAnnotationPredicate)
    register(session.predicates.qualifiersPredicate)
  }

  // Top-level circuit functions
  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> {
    return annotatedFunctions.mapNotNullToSet { annotatedFunction ->
      // Just compute the class ID here, defer full target computation to class gen
      val function = annotatedFunction.symbol
      val factoryClassId =
        ClassId(
          function.callableId.packageName,
          annotatedFunction.target.functionFactoryName(function.name.asString()),
        )
      functionFactoryClassIds[factoryClassId] = annotatedFunction
      factoryClassId
    }
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    val target = getOrComputeFunctionTarget(classId) ?: return null
    return generateFactoryClass(
      target,
      null,
      CircuitOrigins.FactoryClass(target.codegenTarget, target.factoryType),
      target.factoryType,
      // We don't know hasConstructorParams yet, for now always generate as CLASS
      hasConstructorParams = true,
    )
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    // Just check if annotated, defer full computation to generation
    return annotatedClasses
      .asSequence()
      .filter { it.symbol == classSymbol }
      .mapTo(mutableSetOf()) { it.target.nestedFactoryName }
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    val annotatedClass =
      annotatedClasses.firstOrNull { it.symbol == owner && it.target.nestedFactoryName == name }
        ?: return null

    val target = getOrComputeClassTarget(annotatedClass) ?: return null
    // factoryType is null here — resolved by CircuitFactorySupertypeGenerator during
    // the supertypes phase, either from the origin key or via BFS
    // Class-based factories always have constructor params (provider or assisted factory)
    return generateFactoryClass(
      target,
      owner,
      CircuitOrigins.FactoryClass(target.codegenTarget, null),
      factoryType = null,
      hasConstructorParams = true,
    )
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    if (classSymbol.classId !in generatedFactoryClassIds) return emptySet()
    return setOf(SpecialNames.INIT) // Create will be handled by fakeOverride IR gen from supertype
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val target = findTargetForFactory(context.owner.classId) ?: return emptyList()
    target.populate(session, ::isClassProvidedType)

    val constructor =
      createConstructor(
        context.owner,
        CircuitOrigins.FactoryConstructor,
        isPrimary = true,
        generateDelegatedNoArgConstructorCall = true,
      ) {
        visibility = Visibilities.Public

        // Add constructor parameters based on the target type
        when {
          target.isAssisted -> {
            // Inject the assisted factory type directly
            target.assistedFactoryType?.let { factoryType ->
              valueParameter(CircuitNames.factoryField, factoryType)
            }
          }
          target.instantiationType == InstantiationType.FUNCTION -> {
            // For function-based factories, add constructor params for injected dependencies
            // (params _not_ circuit-provided at create()/lambda time).
            // Plain types are wrapped in Provider<T> to avoid recomputation in composition.
            // Types already wrapped in Provider/Lazy/Function are passed through as-is.
            val functionSymbol = target.originalFunctionSymbol ?: return@createConstructor
            val factoryType = target.factoryType ?: return@createConstructor
            for (param in functionSymbol.valueParameterSymbols) {
              if (!isFunctionProvidedParam(param, target.codegenTarget, factoryType)) {
                val type = resolveInjectableParamType(param, functionSymbol) ?: continue
                valueParameter(param.name, type)
              }
            }
          }
          target.instantiationType == InstantiationType.CLASS -> {
            // For class-based factories, add Provider<T> params only for injectable dependencies.
            // Circuit-provided params (Screen, Navigator, etc.) are wired from create() in IR.
            val (ctorParams, ctorOwner) = target.resolveConstructorParams(session)
            if (ctorOwner != null) {
              for (param in ctorParams) {
                if (param.name in target.injectableParamNames) {
                  val type = resolveInjectableParamType(param, ctorOwner) ?: continue
                  valueParameter(param.name, type)
                }
              }
            }
          }
        }
      }

    // Copy qualifier annotations from original params to generated constructor params.
    // This ensures Metro resolves the correct qualified bindings.
    val originalParamSource: List<FirValueParameterSymbol>? =
      when (target.instantiationType) {
        InstantiationType.FUNCTION -> target.originalFunctionSymbol?.valueParameterSymbols
        InstantiationType.CLASS -> target.resolveConstructorParams(session).first
      }
    if (originalParamSource != null) {
      val originalParamsByName = originalParamSource.associateBy { it.name }
      for (constructorParam in constructor.valueParameters) {
        val originalParam = originalParamsByName[constructorParam.name] ?: continue
        val qualifier = originalParam.qualifierAnnotation(session)
        if (qualifier != null) {
          context(session.compatContext) {
            constructorParam.replaceAnnotationsSafe(constructorParam.annotations + qualifier.fir)
          }
        }
      }
    }

    return listOf(constructor.symbol)
  }

  /**
   * Resolves the factory constructor parameter type for an injectable (non-circuit-provided) param.
   * Plain types are wrapped in `Provider<T>`. Types already wrapped in Provider/Lazy/Function are
   * returned as-is.
   */
  private fun resolveInjectableParamType(
    param: FirValueParameterSymbol,
    paramOwner: FirFunctionSymbol<*>,
  ): ConeKotlinType? {
    val paramType =
      typeResolverFactory.create(paramOwner)?.resolveType(param.resolvedReturnTypeRef)
        ?: return null

    val paramContextKey = FirContextualTypeKey.from(session, param, paramType)
    val isAlreadyWrapped = !paramContextKey.isCanonical
    return if (isAlreadyWrapped) {
      paramType
    } else {
      Symbols.ClassIds.metroProvider.constructClassLikeType(arrayOf(paramType))
    }
  }

  // Indicate where our generated contributing classes are. These are all the `@IntoSet` annotated
  // factories
  override fun getContributionHints(): List<ContributionHint> {
    val classHints = annotatedClasses.mapNotNull { annotatedClass ->
      val target = getOrComputeClassTarget(annotatedClass) ?: return@mapNotNull null
      ContributionHint(contributingClassId = target.factoryClassId, scope = target.scopeClassId)
    }
    val functionHints =
      functionFactoryClassIds.keys.mapNotNull { factoryClassId ->
        val target = getOrComputeFunctionTarget(factoryClassId) ?: return@mapNotNull null
        ContributionHint(contributingClassId = target.factoryClassId, scope = target.scopeClassId)
      }
    return classHints + functionHints
  }

  private fun generateFactoryClass(
    target: CircuitFactoryTarget,
    owner: FirClassSymbol<*>?,
    key: CircuitOrigins.FactoryClass,
    /**
     * Passed from top-level function generation. This is important because it seems those classes
     * do not pass through [CircuitFactorySupertypeGenerator] anyway.
     */
    factoryType: FactoryType?,
    // TODO not currently able to fully implement this
    hasConstructorParams: Boolean,
  ): FirClassLikeSymbol<*> {
    val classKind = if (hasConstructorParams) ClassKind.CLASS else ClassKind.OBJECT

    val factoryClass =
      if (owner != null) {
        createNestedClass(
          owner,
          target.codegenTarget.nestedFactoryName,
          key,
          classKind = classKind,
        ) {}
      } else {
        createTopLevelClass(target.factoryClassId, key, classKind = classKind) {
          factoryType?.let {
            superType(target.codegenTarget.factoryClassId(it).constructClassLikeType())
          }
        }
      }

    factoryClass.metroGeneratedInjectClassData =
      GeneratedInjectClassData(hasConstructorParams = hasConstructorParams)

    factoryClass.circuitFactoryTargetData = target
    target.originClassId?.let { originClassId ->
      factoryClass.metroOriginData = MetroOriginData(originClassId)
    }

    // Add annotations
    val annotations = buildList {
      // @Inject
      add(buildInjectAnnotation())

      // @ContributesIntoSet(scope)
      add(buildContributesIntoSetAnnotation(target.scopeClassId))

      // Propagate any qualifier annotation from the source declaration.
      val qualifierSource: FirBasedSymbol<*>? = target.classSymbol ?: target.originalFunctionSymbol
      qualifierSource?.qualifierAnnotation(session)?.fir?.let(::add)
    }

    context(session.compatContext) { factoryClass.replaceAnnotationsSafe(annotations) }

    factoryClass.markAsDeprecatedHidden(session)

    generatedFactoryClassIds.add(factoryClass.symbol.classId)

    return factoryClass.symbol
  }

  private fun computeFactoryTarget(
    function: FirFunctionSymbol<*>,
    factoryClassId: ClassId,
    typeResolver: MetroFirTypeResolver,
    codegenTarget: CircuitCodegenTarget,
    factoryType: FactoryType,
    returnType: ConeKotlinType,
  ): CircuitFactoryTarget? {
    val annotation =
      function.annotationsIn(session, setOf(codegenTarget.injectAnnotation)).firstOrNull()
        ?: return null

    val (screenType, scopeType) = extractCircuitInjectArgs(annotation, typeResolver) ?: return null

    return CircuitFactoryTarget(
        originClassId = null, // For functions, there is no origin to point at statically
        codegenTarget = codegenTarget,
        factoryClassId = factoryClassId,
        screenType = screenType,
        scopeClassId = scopeType,
        classSymbol = null,
        originalFunctionSymbol = function,
      )
      .apply { populateForFunction(returnType, factoryType) }
  }

  /**
   * Computes the early factory target for a class. Only extracts annotation args. Deferred fields
   * (useProvider, isAssisted, factoryType, etc.) are populated later via
   * [CircuitFactoryTarget.populate] during supertype resolution.
   */
  private fun computeFactoryTarget(
    classSymbol: FirClassSymbol<*>,
    typeResolver: MetroFirTypeResolver,
    codegenTarget: CircuitCodegenTarget,
  ): CircuitFactoryTarget? {
    val annotation =
      classSymbol.annotationsIn(session, setOf(codegenTarget.injectAnnotation)).firstOrNull()
        ?: return null

    val (screenType, scopeType) = extractCircuitInjectArgs(annotation, typeResolver) ?: return null

    val factoryClassId = classSymbol.classId.createNestedClassId(codegenTarget.nestedFactoryName)

    return CircuitFactoryTarget(
      originClassId = classSymbol.classId,
      codegenTarget = codegenTarget,
      factoryClassId = factoryClassId,
      screenType = screenType,
      scopeClassId = scopeType,
      classSymbol = classSymbol,
    )
  }

  private fun extractCircuitInjectArgs(
    annotation: FirAnnotation,
    typeResolver: MetroFirTypeResolver,
  ): Pair<ClassId, ClassId>? {
    if (annotation !is FirAnnotationCall) return null
    if (annotation.arguments.size < 2) return null

    // First arg is screen, second is scope
    val screenArg =
      annotation.argumentAsOrNull<FirGetClassCall>(session, CircuitNames.screen, 0) ?: return null
    val scopeArg =
      annotation.argumentAsOrNull<FirGetClassCall>(session, CircuitNames.scope, 1) ?: return null

    val screenType = screenArg.resolveClassId(typeResolver) ?: return null
    val scopeType = scopeArg.resolveClassId(typeResolver) ?: return null

    return screenType to scopeType
  }

  /**
   * Returns true if the parameter is provided by Circuit at runtime rather than needing constructor
   * injection.
   *
   * Both UI and Presenter: Screen subtypes Presenter only: Navigator UI only: CircuitUiState
   * subtypes, Modifier
   *
   * Note: CircuitContext is intentionally excluded — it's for factory-level use, not for
   * consumption in actual presenters/UIs.
   */
  private fun isFunctionProvidedParam(
    param: FirValueParameterSymbol,
    target: CircuitCodegenTarget,
    factoryType: FactoryType,
  ): Boolean {
    val classId = param.resolvedReturnTypeRef.coneType.classId ?: return false
    val type = classifyCircuitType(classId, target) ?: return false
    if (target == CircuitCodegenTarget.SUBCIRCUIT) {
      return type == CircuitProvidedType.MODIFIER || type == CircuitProvidedType.UI_STATE
    }
    return isProvidedType(type, factoryType)
  }

  private fun isClassProvidedType(
    classId: ClassId,
    target: CircuitCodegenTarget,
    factoryType: FactoryType,
  ): Boolean {
    val type = classifyCircuitType(classId, target) ?: return false
    if (target == CircuitCodegenTarget.SUBCIRCUIT) {
      return type == CircuitProvidedType.SCREEN
    }
    return isProvidedType(type, factoryType)
  }

  private fun isProvidedType(type: CircuitProvidedType, factoryType: FactoryType): Boolean {
    return when (type) {
      CircuitProvidedType.SCREEN -> true
      CircuitProvidedType.NAVIGATOR -> factoryType == FactoryType.PRESENTER
      CircuitProvidedType.MODIFIER -> factoryType == FactoryType.UI
      CircuitProvidedType.UI_STATE -> factoryType == FactoryType.UI
    }
  }

  /**
   * Classifies a ClassId as a circuit-provided type. Exact classId matches are checked first.
   * Screen and CircuitUiState require supertype walks since they're always subtyped in practice.
   * Navigator and Modifier are exact matches only.
   */
  private fun classifyCircuitType(
    classId: ClassId,
    target: CircuitCodegenTarget,
  ): CircuitProvidedType? {
    return when (classId) {
      target.screenClassId -> CircuitProvidedType.SCREEN
      CircuitClassIds.Navigator ->
        if (target == CircuitCodegenTarget.CIRCUIT) CircuitProvidedType.NAVIGATOR else null
      CircuitClassIds.Modifier -> CircuitProvidedType.MODIFIER
      target.uiStateClassId -> CircuitProvidedType.UI_STATE
      else -> {
        val s = symbols ?: return null
        when {
          s.isScreenType(classId, target) -> CircuitProvidedType.SCREEN
          s.isUiStateType(classId, target) -> CircuitProvidedType.UI_STATE
          else -> null
        }
      }
    }
  }

  internal companion object {
    // ClassId for ContributesIntoSet annotation
    private val contributesIntoSetClassId =
      ClassId(Symbols.FqNames.metroRuntimePackage, Name.identifier("ContributesIntoSet"))

    fun findCircuitInjectSymbols(session: FirSession): List<FirBasedSymbol<*>> {
      return session.predicateBasedProvider.getSymbolsByPredicate(
        CircuitSymbols.circuitInjectPredicate
      )
    }

    fun findCircuitInjectFunctions(
      annotatedSymbols: List<FirBasedSymbol<*>>,
      session: FirSession,
      target: CircuitCodegenTarget,
    ): List<FirNamedFunctionSymbol> {
      return annotatedSymbols
        .filterIsInstance<FirNamedFunctionSymbol>()
        .filter { it.callableId.classId == null } // Only top-level functions
        .filterNot { it.rawStatus.isExpect }
        .filter {
          it.annotationsIn(session, setOf(target.injectAnnotation)).firstOrNull() != null
        }
    }
  }

  private fun findTargetForFactory(factoryClassId: ClassId): CircuitFactoryTarget? {
    return computedTargets[factoryClassId]
  }

  /** Gets or lazily computes and caches the factory target for a class-based factory. */
  private fun getOrComputeClassTarget(
    annotatedClass: AnnotatedCircuitClass
  ): CircuitFactoryTarget? {
    val classSymbol = annotatedClass.symbol
    val codegenTarget = annotatedClass.target
    val factoryClassId = classSymbol.classId.createNestedClassId(codegenTarget.nestedFactoryName)
    return computedTargets.getOrPut(factoryClassId) {
      val typeResolver = typeResolverFactory.create(classSymbol) ?: return@getOrPut null
      computeFactoryTarget(classSymbol, typeResolver, codegenTarget)
    }
  }

  /** Gets or lazily computes and caches the factory target for a function-based factory. */
  private fun getOrComputeFunctionTarget(factoryClassId: ClassId): CircuitFactoryTarget? {
    return computedTargets.getOrPut(factoryClassId) {
      val annotatedFunction = functionFactoryClassIds[factoryClassId] ?: return@getOrPut null
      val function = annotatedFunction.symbol
      val typeResolver = typeResolverFactory.create(function) ?: return@getOrPut null
      @OptIn(SymbolInternals::class) val returnTypeRef = function.fir.returnTypeRef
      val returnType =
        if (returnTypeRef is FirImplicitTypeRef) {
          // Assume it's Unit/UI. Checker will validate otherwise later
          session.builtinTypes.unitType.coneType
        } else {
          typeResolver.resolveType(returnTypeRef)
        }
      val factoryType = annotatedFunction.target.functionFactoryType(returnType.isUnit)
      computeFactoryTarget(
        function,
        factoryClassId,
        typeResolver,
        annotatedFunction.target,
        factoryType,
        returnType,
      )
    }
  }

  private fun buildInjectAnnotation(): FirAnnotation {
    val injectClassSymbol = session.metroFirBuiltIns.injectClassSymbol
    return buildAnnotation {
      annotationTypeRef = injectClassSymbol.defaultType().toFirResolvedTypeRef()
      argumentMapping = buildAnnotationArgumentMapping()
    }
  }

  private fun buildContributesIntoSetAnnotation(scopeClassId: ClassId): FirAnnotation {
    val contributesIntoSetSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(contributesIntoSetClassId)
        as? FirRegularClassSymbol ?: error("Could not find ContributesIntoSet")

    val scopeSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(scopeClassId)
        ?: error("Could not find scope class: $scopeClassId")

    val scopeType = (scopeSymbol as FirRegularClassSymbol).defaultType()

    return buildAnnotation {
      annotationTypeRef = contributesIntoSetSymbol.defaultType().toFirResolvedTypeRef()
      argumentMapping = buildAnnotationArgumentMapping {
        mapping[Name.identifier("scope")] = buildGetClassCall {
          argumentList = buildArgumentList {
            arguments += buildResolvedQualifierCompat(scopeClassId, scopeSymbol, scopeType)
          }
          coneTypeOrNull =
            ConeClassLikeTypeImpl(
              StandardClassIds.KClass.toLookupTag(),
              arrayOf(scopeType),
              isMarkedNullable = false,
            )
        }
      }
    }
  }

  private fun ConeKotlinType.toFirResolvedTypeRef(): FirResolvedTypeRef {
    return buildResolvedTypeRef { coneType = this@toFirResolvedTypeRef }
  }

  public class Factory : MetroFirDeclarationGenerationExtension.Factory {
    override fun create(
      session: FirSession,
      options: MetroOptions,
      compatContext: CompatContext,
    ): MetroFirDeclarationGenerationExtension? {
      if (!options.enableCircuitCodegen || options.generateClassesInIr) return null
      return CircuitFirExtension(session, compatContext)
    }
  }
}

/** Classification of a circuit-provided parameter type. */
internal enum class CircuitProvidedType {
  SCREEN,
  NAVIGATOR,
  MODIFIER,
  UI_STATE,
}

/** How the target is instantiated. */
internal enum class InstantiationType {
  /** Target is a top-level composable function. */
  FUNCTION,

  /** Target is a class implementing Ui or Presenter. */
  CLASS,
}

private data class AnnotatedCircuitClass(
  val symbol: FirRegularClassSymbol,
  val target: CircuitCodegenTarget,
)

private data class AnnotatedCircuitFunction(
  val symbol: FirFunctionSymbol<*>,
  val target: CircuitCodegenTarget,
)

/**
 * Holds all information needed to generate a Circuit factory.
 *
 * Early fields ([originClassId], [factoryClassId], [screenType], [scopeClassId]) are set at
 * construction during class generation. Deferred fields ([targetType], [isAssisted],
 * [assistedFactoryType], [factoryType], [injectableParamNames]) are populated lazily via [populate]
 * during member generation (constructor/function generation), when type information (e.g., SAM
 * function resolution) is available.
 */
internal class CircuitFactoryTarget(
  /** The original class that the factory is for (used for @Origin annotation). */
  val originClassId: ClassId?,
  /** The Circuit runtime family this factory targets. */
  val codegenTarget: CircuitCodegenTarget,
  /** The ClassId of the factory to generate. */
  val factoryClassId: ClassId,
  /** The screen type from @CircuitInject. */
  val screenType: ClassId,
  /** The scope ClassId from @CircuitInject. */
  val scopeClassId: ClassId,
  /** Stored for deferred population. Null for function targets. */
  internal val classSymbol: FirClassSymbol<*>?,
  /** The original function symbol. Only set for function-based factories. */
  val originalFunctionSymbol: FirFunctionSymbol<*>? = null,
) {
  /** The target type (class type for CLASS instantiation, return type for FUNCTION). */
  var targetType: ConeKotlinType? = null
    private set

  /** How the target is instantiated. */
  var instantiationType: InstantiationType = InstantiationType.CLASS
    private set

  /** Whether this uses assisted injection. */
  var isAssisted: Boolean = false
    private set

  /** The assisted factory type if isAssisted is true. */
  var assistedFactoryType: ConeKotlinType? = null
    private set

  var factoryType: FactoryType? = null
    private set

  /**
   * Names of constructor params that need DI (not circuit-provided). For CLASS targets, the
   * generated factory constructor will have `Provider<T>` params for each of these.
   */
  var injectableParamNames: Set<Name> = emptySet()
    private set

  private var populated = false

  /** Eagerly populate all fields for function-based targets where everything is known upfront. */
  fun populateForFunction(targetType: ConeKotlinType, factoryType: FactoryType) {
    this.targetType = targetType
    this.instantiationType = InstantiationType.FUNCTION
    this.factoryType = factoryType
    this.populated = true
  }

  /**
   * Lazily populate deferred fields for class-based targets. Called during member generation
   * (constructor/function generation) when type information (e.g., SAM functions) is available.
   */
  fun populate(
    session: FirSession,
    isCircuitProvidedType: (ClassId, CircuitCodegenTarget, FactoryType) -> Boolean = { _, _, _ ->
      false
    },
  ) {
    if (populated) return
    populated = true
    val classSymbol = classSymbol ?: return

    val isAssistedFactory =
      classSymbol.isAnnotatedWithAny(session, session.classIds.assistedFactoryAnnotations)

    if (isAssistedFactory) {
      // For @AssistedFactory-annotated classes (e.g., FavoritesPresenter.Factory), resolving
      // the SAM function return type and its supertypes is not safe during member generation
      // due to FIR lifecycle constraints. Set the minimum fields needed for constructor
      // generation and defer factoryType resolution to CircuitFactorySupertypeGenerator (FIR
      // supertypes phase) and CircuitIrExtension (IR).
      isAssisted = true
      assistedFactoryType = classSymbol.defaultType()
      instantiationType = InstantiationType.CLASS
      // factoryType remains null — resolved by supertype generator and read from IR supertypes
      return
    }

    val targetTypeLocal: ConeKotlinType = classSymbol.defaultType()
    targetType = targetTypeLocal
    instantiationType = InstantiationType.CLASS

    factoryType =
      when {
        classSymbol.implements(codegenTarget.presenterClassId, session) -> FactoryType.PRESENTER
        classSymbol.implements(codegenTarget.uiClassId, session) -> FactoryType.UI
        else -> null
      }

    // Classify constructor params into circuit-provided vs injectable.
    // Circuit-provided params (Screen, Navigator, etc.) come from the factory's create() method.
    // Injectable params need Provider<T> wrappers in the factory constructor.
    val resolvedFactoryType = factoryType ?: return
    val (constructorParams, _) = resolveConstructorParams(session)

    isAssisted = constructorParams.any {
      it.isAnnotatedWithAny(session, session.classIds.assistedAnnotations)
    }

    injectableParamNames =
      constructorParams
        .filterNot { param ->
          val classId = param.resolvedReturnTypeRef.coneType.classId ?: return@filterNot false
          isCircuitProvidedType(classId, codegenTarget, resolvedFactoryType)
        }
        .mapToSet { it.name }
  }

  /**
   * Resolves the constructor params and their owning symbol for the target class. Requires an
   * `@Inject`-annotated constructor (or class-level `@Inject`).
   */
  fun resolveConstructorParams(
    session: FirSession
  ): Pair<List<FirValueParameterSymbol>, FirFunctionSymbol<*>?> {
    val classSymbol = classSymbol ?: return emptyList<FirValueParameterSymbol>() to null
    val injectConstructor = classSymbol.findInjectLikeConstructors(session, true).firstOrNull()
    val constructor =
      injectConstructor?.constructor ?: return emptyList<FirValueParameterSymbol>() to null
    val params = constructor.valueParameterSymbols
    return params to constructor
  }

  object Attribute : FirDeclarationDataKey()
}

internal var FirClass.circuitFactoryTargetData: CircuitFactoryTarget? by
  FirDeclarationDataRegistry.data(CircuitFactoryTarget.Attribute)

internal val IrClass.circuitFactoryTargetData: CircuitFactoryTarget?
  get() = (metadata as? FirMetadataSource.Class)?.fir?.circuitFactoryTargetData
