// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.findInjectLikeConstructors
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.memoizedAllSessionsSequence
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.fir.resolveClassId
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildGetClassCall
import org.jetbrains.kotlin.fir.expressions.builder.buildResolvedQualifier
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
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.types.toLookupTag
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
public class CircuitFirExtension(session: FirSession) :
  MetroFirDeclarationGenerationExtension(session), CompatContext by CompatContext.getInstance() {

  private val symbols by lazy { CircuitSymbols.Fir(session) }

  // Caches for discovered @CircuitInject-annotated elements
  private val annotatedSymbols by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(CircuitSymbols.circuitInjectPredicate)
      .toList()
  }

  private val annotatedClasses by lazy {
    annotatedSymbols.filterIsInstance<FirRegularClassSymbol>().toSet()
  }

  private val annotatedFunctions by lazy {
    annotatedSymbols
      .filterIsInstance<FirNamedFunctionSymbol>()
      .filter { it.callableId.classId == null } // Only top-level functions
      .toList()
  }

  // Map from factory ClassId -> annotated function (for top-level function factories)
  private val functionFactoryClassIds = mutableMapOf<ClassId, FirNamedFunctionSymbol>()

  // Track generated factory ClassIds for callable generation
  private val generatedFactoryClassIds = mutableSetOf<ClassId>()

  // Cache computed targets during class generation
  private val computedTargets = mutableMapOf<ClassId, CircuitFactoryTarget>()

  private val typeResolverFactory =
    MetroFirTypeResolver.Factory(session, session.memoizedAllSessionsSequence)

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(CircuitSymbols.circuitInjectPredicate)
  }

  // ===================
  // Top-level class generation (for functions)
  // ===================

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> {
    return annotatedFunctions.mapNotNullToSet { function ->
      // Just compute the class ID here, defer full target computation to generation
      val functionName = function.name.asString()
      val factoryClassId =
        ClassId(
          function.callableId.packageName,
          Name.identifier("${functionName.capitalizeUS()}Factory"),
        )
      functionFactoryClassIds[factoryClassId] = function
      factoryClassId
    }
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    val function = functionFactoryClassIds[classId] ?: return null
    val typeResolver = typeResolverFactory.create(function) ?: return null
    val target = computeFactoryTarget(function, classId, typeResolver) ?: return null
    computedTargets[classId] = target
    val factoryType =
      if (!function.resolvedReturnType.isUnit) FactoryType.PRESENTER else FactoryType.UI
    val key = CircuitOrigins.FactoryClass(factoryType)
    return generateFactoryClass(target, null, key)
  }

  // ===================
  // Nested class generation (for classes)
  // ===================

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    // Just check if annotated, defer full computation to generation
    if (classSymbol !in annotatedClasses) return emptySet()
    return setOf(CircuitNames.Factory)
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    if (name != CircuitNames.Factory) return null
    if (owner !in annotatedClasses) return null

    val typeResolver = typeResolverFactory.create(owner) ?: return null
    val target = computeFactoryTarget(owner, typeResolver) ?: return null
    val factoryClassId = owner.classId.createNestedClassId(CircuitNames.Factory)
    computedTargets[factoryClassId] = target
    val key =
      CircuitOrigins.FactoryClass(
        // Ignored and separately introspected in supertype gen
        null
      )
    return generateFactoryClass(target, owner, key)
  }

  // ===================
  // Callable generation (constructor and create function)
  // ===================

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    if (classSymbol.classId !in generatedFactoryClassIds) return emptySet()
    return setOf(SpecialNames.INIT, CircuitNames.create)
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val target = findTargetForFactory(context.owner.classId) ?: return emptyList()

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
          target.useProvider -> {
            // Inject Provider<TargetClass>
            val providerType =
              Symbols.ClassIds.metroProvider.constructClassLikeType(arrayOf(target.targetType))
            valueParameter(CircuitNames.provider, providerType)
          }
          target.isAssisted -> {
            // Inject the assisted factory
            // For now, inject the assisted factory type directly
            // This will be the @AssistedFactory-annotated interface
            target.assistedFactoryType?.let { factoryType ->
              valueParameter(CircuitNames.factoryField, factoryType)
            }
          }
        // For simple function factories, no constructor parameters needed
        }
      }

    return listOf(constructor.symbol)
  }

//  override fun generateFunctions(
//    callableId: CallableId,
//    context: MemberGenerationContext?,
//  ): List<FirNamedFunctionSymbol> {
//    if (context == null) return emptyList()
//    if (callableId.callableName != CircuitNames.create) return emptyList()
//
//    val target = findTargetForFactory(context.owner.classId) ?: return emptyList()
//
//    val returnType =
//      when (target.factoryType) {
//        FactoryType.UI ->
//          CircuitClassIds.Ui.constructClassLikeType(
//            arrayOf(ConeStarProjection),
//            isMarkedNullable = true,
//          )
//        FactoryType.PRESENTER ->
//          CircuitClassIds.Presenter.constructClassLikeType(
//            arrayOf(ConeStarProjection),
//            isMarkedNullable = true,
//          )
//      }
//
//    val function =
//      createMemberFunction(
//        context.owner,
//        CircuitOrigins.FactoryCreateFunction,
//        CircuitNames.create,
//        returnType,
//      ) {
//        // Parameters: screen, [navigator for presenter], context
//        valueParameter(
//          CircuitNames.screen,
//          CircuitClassIds.Screen.constructClassLikeType(emptyArray()),
//        )
//        if (target.factoryType == FactoryType.PRESENTER) {
//          valueParameter(
//            CircuitNames.navigator,
//            CircuitClassIds.Navigator.constructClassLikeType(emptyArray()),
//          )
//        }
//        valueParameter(
//          CircuitNames.context,
//          CircuitClassIds.CircuitContext.constructClassLikeType(emptyArray()),
//        )
//      }
//
//    // TODO ???
//    return listOf(function.symbol) as List<FirNamedFunctionSymbol>
//  }

  private fun generateFactoryClass(
    target: CircuitFactoryTarget,
    owner: FirClassSymbol<*>?,
    key: CircuitOrigins.FactoryClass,
  ): FirClassLikeSymbol<*> {
    // Note: Supertypes (Ui.Factory/Presenter.Factory) are contributed by
    // CircuitFactorySupertypeGenerator
    val factoryClass =
      if (owner != null) {
        createNestedClass(owner, CircuitNames.Factory, key)
      } else {
        createTopLevelClass(target.factoryClassId, key)
      }

    // Add annotations
    val annotations = buildList {
      // @Inject
      add(buildInjectAnnotation())

      // @ContributesIntoSet(scope)
      add(buildContributesIntoSetAnnotation(target.scopeClassId))

      // @Origin(originClass)
      add(buildOriginAnnotation(target.originClassId))
    }

    context(session.compatContext) { factoryClass.replaceAnnotationsSafe(annotations) }
    generatedFactoryClassIds.add(factoryClass.symbol.classId)

    return factoryClass.symbol
  }

  private fun computeFactoryTarget(
    function: FirNamedFunctionSymbol,
    factoryClassId: ClassId,
    typeResolver: MetroFirTypeResolver,
  ): CircuitFactoryTarget? {
    val annotation =
      function.annotationsIn(session, setOf(CircuitClassIds.CircuitInject)).firstOrNull()
        ?: return null

    val (screenType, scopeType) = extractCircuitInjectArgs(annotation, typeResolver) ?: return null

    return CircuitFactoryTarget(
      originClassId = factoryClassId, // For functions, origin is the factory itself
      factoryClassId = factoryClassId,
      screenType = screenType,
      scopeClassId = scopeType,
      targetType = session.builtinTypes.unitType.coneType, // Not used for functions
      instantiationType = InstantiationType.FUNCTION,
      useProvider = false,
      isAssisted = false,
      assistedFactoryType = null,
    )
  }

  private fun computeFactoryTarget(
    classSymbol: FirClassSymbol<*>,
    typeResolver: MetroFirTypeResolver,
  ): CircuitFactoryTarget? {
    val annotation =
      classSymbol.annotationsIn(session, setOf(CircuitClassIds.CircuitInject)).firstOrNull()
        ?: return null

    val (screenType, scopeType) = extractCircuitInjectArgs(annotation, typeResolver) ?: return null

    val factoryClassId = classSymbol.classId.createNestedClassId(CircuitNames.Factory)

    // Check if it uses provider injection (has @Inject constructor without assisted params)
    val injectConstructor = classSymbol.findInjectLikeConstructors(session, true).firstOrNull()

    val hasAssistedParams =
      injectConstructor?.constructor?.valueParameterSymbols?.any {
        it.isAnnotatedWithAny(session, session.classIds.assistedAnnotations)
      } == true

    val useProvider = injectConstructor != null && !hasAssistedParams

    // Check if this is an assisted factory interface
    val isAssistedFactory =
      classSymbol.isAnnotatedWithAny(session, session.classIds.assistedFactoryAnnotations)

    return CircuitFactoryTarget(
      originClassId = classSymbol.classId,
      factoryClassId = factoryClassId,
      screenType = screenType,
      scopeClassId = scopeType,
      targetType = classSymbol.defaultType(),
      instantiationType = InstantiationType.CLASS,
      useProvider = useProvider,
      isAssisted = hasAssistedParams || isAssistedFactory,
      assistedFactoryType = if (isAssistedFactory) classSymbol.defaultType() else null,
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
      annotation.argumentAsOrNull<FirGetClassCall>("screen".asName(), 0) ?: return null
    val scopeArg = annotation.argumentAsOrNull<FirGetClassCall>("scope".asName(), 1) ?: return null

    val screenType = screenArg.resolveClassId(typeResolver) ?: return null
    val scopeType = scopeArg.resolveClassId(typeResolver) ?: return null

    return screenType to scopeType
  }

  internal companion object {
    // ClassId for ContributesIntoSet annotation
    private val contributesIntoSetClassId =
      ClassId(Symbols.FqNames.metroRuntimePackage, Name.identifier("ContributesIntoSet"))
  }

  private fun findTargetForFactory(factoryClassId: ClassId): CircuitFactoryTarget? {
    return computedTargets[factoryClassId]
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
            arguments += buildResolvedQualifier {
              packageFqName = scopeClassId.packageFqName
              relativeClassFqName = scopeClassId.relativeClassName
              symbol = scopeSymbol
              resolvedToCompanionObject = false
              isFullyQualified = true
              coneTypeOrNull = scopeType
            }
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

  private fun buildOriginAnnotation(originClassId: ClassId): FirAnnotation {
    val originSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroOrigin)
        ?: error("Could not find Origin annotation")

    val targetSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(originClassId)
        ?: error("Could not find origin class: $originClassId")

    val targetType = (targetSymbol as FirRegularClassSymbol).defaultType()

    return buildAnnotation {
      annotationTypeRef = originSymbol.defaultType().toFirResolvedTypeRef()
      argumentMapping = buildAnnotationArgumentMapping {
        mapping[Name.identifier("value")] = buildGetClassCall {
          argumentList = buildArgumentList {
            arguments += buildResolvedQualifier {
              packageFqName = originClassId.packageFqName
              relativeClassFqName = originClassId.relativeClassName
              symbol = targetSymbol
              resolvedToCompanionObject = false
              isFullyQualified = true
              coneTypeOrNull = targetType
            }
          }
          coneTypeOrNull =
            ConeClassLikeTypeImpl(
              StandardClassIds.KClass.toLookupTag(),
              arrayOf(targetType),
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
    ): MetroFirDeclarationGenerationExtension? {
      if (!options.enableCircuitCodegen) return null
      return CircuitFirExtension(session)
    }
  }
}

/** Type of Circuit factory to generate. */
internal enum class FactoryType(val classId: ClassId, val factoryClassId: ClassId) {
  UI(CircuitClassIds.Ui, CircuitClassIds.UiFactory),
  PRESENTER(CircuitClassIds.Presenter, CircuitClassIds.PresenterFactory),
}

/** How the target is instantiated. */
internal enum class InstantiationType {
  /** Target is a top-level composable function. */
  FUNCTION,

  /** Target is a class implementing Ui or Presenter. */
  CLASS,
}

/** Data class holding all information needed to generate a Circuit factory. */
internal data class CircuitFactoryTarget(
  /** The original class that the factory is for (used for @Origin annotation). */
  val originClassId: ClassId,
  /** The ClassId of the factory to generate. */
  val factoryClassId: ClassId,
  /** The screen type from @CircuitInject. */
  val screenType: ClassId,
  /** The scope ClassId from @CircuitInject. */
  val scopeClassId: ClassId,
  /** The target type (class type for CLASS instantiation, Unit for FUNCTION). */
  val targetType: ConeKotlinType,
  /** How the target is instantiated. */
  val instantiationType: InstantiationType,
  /** Whether to inject a Provider<TargetClass>. */
  val useProvider: Boolean,
  /** Whether this uses assisted injection. */
  val isAssisted: Boolean,
  /** The assisted factory type if isAssisted is true. */
  val assistedFactoryType: ConeKotlinType?,
)
