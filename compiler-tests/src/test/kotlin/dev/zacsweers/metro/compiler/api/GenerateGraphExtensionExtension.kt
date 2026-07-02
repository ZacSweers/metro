// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.api

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension
import dev.zacsweers.metro.compiler.api.fir.MetroContributionHintExtension
import dev.zacsweers.metro.compiler.api.fir.MetroContributionHintExtension.ContributionHint
import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.builder.buildRegularClass
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildGetClassCall
import org.jetbrains.kotlin.fir.expressions.builder.buildResolvedQualifier
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.kotlinScopeProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.toEffectiveVisibility
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

private val GRAPH_EXTENSION_CLASS_ID =
  ClassId(FqName("dev.zacsweers.metro"), Name.identifier("GraphExtension"))
private val GRAPH_EXTENSION_FACTORY_CLASS_ID =
  GRAPH_EXTENSION_CLASS_ID.createNestedClassId(Name.identifier("Factory"))
private val PROVIDES_CLASS_ID = ClassId(FqName("dev.zacsweers.metro"), Name.identifier("Provides"))
private val CONTRIBUTES_TO_CLASS_ID =
  ClassId(FqName("dev.zacsweers.metro"), Name.identifier("ContributesTo"))

private val LOGIN_GRAPH_NAME = Name.identifier("LoginGraph")
private val FACTORY_NAME = Name.identifier("Factory")
private val CREATE_NAME = Name.identifier("create")

/**
 * Test extension that generates a `@GraphExtension(Unit::class)` interface with a
 * `@GraphExtension.Factory` for classes annotated with `@GenerateGraphExtension`.
 *
 * For a class like:
 * ```
 * @GenerateGraphExtension
 * class LoginScreen
 * ```
 *
 * This generates a nested interface:
 * ```
 * class LoginScreen {
 *   @GraphExtension(Unit::class)
 *   interface LoginGraph {
 *     @ContributesTo(AppScope::class)
 *     @GraphExtension.Factory
 *     fun interface Factory {
 *       fun create(@Provides text: String): LoginGraph
 *     }
 *   }
 * }
 * ```
 */
internal class GenerateGraphExtensionExtension(session: FirSession) :
  MetroFirDeclarationGenerationExtension(session), MetroContributionHintExtension {

  companion object {
    val ANNOTATION_FQ_NAME = FqName("test.GenerateGraphExtension")
  }

  object Key : GeneratedDeclarationKey()

  private val predicate = LookupPredicate.BuilderContext.annotated(ANNOTATION_FQ_NAME)

  private val annotatedClasses by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(predicate)
      .filterIsInstance<FirRegularClassSymbol>()
      .toList()
  }

  /** ClassIds of LoginGraph interfaces we generate (nested inside annotated classes). */
  private val generatedGraphClassIds by lazy {
    annotatedClasses.map { it.classId.createNestedClassId(LOGIN_GRAPH_NAME) }.toSet()
  }

  /** ClassIds of Factory interfaces nested inside generated graphs. */
  private val generatedFactoryClassIds by lazy {
    generatedGraphClassIds.map { it.createNestedClassId(FACTORY_NAME) }.toSet()
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(predicate)
  }

  // -- LoginGraph generation (nested inside annotated class) --

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    // Generate LoginGraph inside annotated classes
    if (classSymbol in annotatedClasses) return setOf(LOGIN_GRAPH_NAME)
    // Generate Factory inside generated LoginGraph
    if (classSymbol.classId in generatedGraphClassIds) return setOf(FACTORY_NAME)
    return emptySet()
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    return when (name) {
      LOGIN_GRAPH_NAME -> generateLoginGraph(owner, name)
      FACTORY_NAME -> generateFactory(owner, name)
      else -> null
    }
  }

  private fun generateLoginGraph(owner: FirClassSymbol<*>, name: Name): FirClassLikeSymbol<*>? {
    if (owner !in annotatedClasses) return null

    val nestedClassId = owner.classId.createNestedClassId(name)
    val classSymbol = FirRegularClassSymbol(nestedClassId)

    buildRegularClass {
      resolvePhase = FirResolvePhase.BODY_RESOLVE
      moduleData = session.moduleData
      origin = Key.origin
      source = owner.source
      classKind = ClassKind.INTERFACE
      scopeProvider = session.kotlinScopeProvider
      this.name = nestedClassId.shortClassName
      symbol = classSymbol
      status =
        FirResolvedDeclarationStatusImpl(
          Visibilities.Public,
          Modality.ABSTRACT,
          Visibilities.Public.toEffectiveVisibility(owner, forClass = true),
        )
      superTypeRefs += session.builtinTypes.anyType
      annotations += buildGraphExtensionAnnotation(StandardClassIds.Unit)
    }

    return classSymbol
  }

  private fun generateFactory(owner: FirClassSymbol<*>, name: Name): FirClassLikeSymbol<*>? {
    if (owner.classId !in generatedGraphClassIds) return null

    val nestedClassId = owner.classId.createNestedClassId(name)
    val classSymbol = FirRegularClassSymbol(nestedClassId)

    buildRegularClass {
      resolvePhase = FirResolvePhase.BODY_RESOLVE
      moduleData = session.moduleData
      origin = Key.origin
      source = owner.source
      classKind = ClassKind.INTERFACE
      scopeProvider = session.kotlinScopeProvider
      this.name = nestedClassId.shortClassName
      symbol = classSymbol
      status =
        FirResolvedDeclarationStatusImpl(
          Visibilities.Public,
          Modality.ABSTRACT,
          Visibilities.Public.toEffectiveVisibility(owner, forClass = true),
        )
      superTypeRefs += session.builtinTypes.anyType
      annotations += buildContributesToAnnotation(APP_SCOPE_CLASS_ID)
      annotations += buildGraphExtensionFactoryAnnotation()
    }

    return classSymbol
  }

  // -- Factory SAM function generation --

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    if (classSymbol.classId in generatedFactoryClassIds) return setOf(CREATE_NAME)
    return emptySet()
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    if (context == null) return emptyList()
    val owner = context.owner
    if (owner.classId !in generatedFactoryClassIds) return emptyList()
    if (callableId.callableName != CREATE_NAME) return emptyList()

    // The graph class is the parent of this Factory
    val graphClassId = owner.classId.parentClassId ?: return emptyList()
    val graphSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(graphClassId) as? FirRegularClassSymbol
        ?: return emptyList()
    val graphType = graphSymbol.defaultType()

    // String type for the @Provides parameter
    val stringSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(StandardClassIds.String)
        as FirRegularClassSymbol
    val stringType = stringSymbol.defaultType()

    // Create: fun create(@Provides text: String): GraphType
    val createFunction =
      createMemberFunction(owner, Key, CREATE_NAME, graphType) {
        valueParameter(Name.identifier("text"), stringType, key = Key)
      }

    // Set ABSTRACT modality (createMemberFunction may default to a different modality)
    createFunction.replaceStatus(
      FirResolvedDeclarationStatusImpl(
        Visibilities.Public,
        Modality.ABSTRACT,
        Visibilities.Public.toEffectiveVisibility(owner, forClass = true),
      )
    )

    // Add @Provides annotation to the text parameter
    createFunction.valueParameters[0].replaceAnnotations(listOf(buildProvidesAnnotation()))

    return listOf(createFunction.symbol)
  }

  // -- Annotation builders --

  private fun buildGraphExtensionAnnotation(scopeClassId: ClassId): FirAnnotation {
    val annotationClassSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(GRAPH_EXTENSION_CLASS_ID)
        as FirRegularClassSymbol
    val scopeSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(scopeClassId) as FirRegularClassSymbol
    val scopeType = scopeSymbol.defaultType()

    return buildAnnotation {
      annotationTypeRef = annotationClassSymbol.defaultType().toFirResolvedTypeRef()
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

  private fun buildContributesToAnnotation(scopeClassId: ClassId): FirAnnotation {
    val annotationClassSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(CONTRIBUTES_TO_CLASS_ID)
        as FirRegularClassSymbol
    val scopeClassSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(scopeClassId) as FirRegularClassSymbol
    val scopeType = scopeClassSymbol.defaultType()

    return buildAnnotation {
      annotationTypeRef = annotationClassSymbol.defaultType().toFirResolvedTypeRef()
      argumentMapping = buildAnnotationArgumentMapping {
        mapping[Name.identifier("scope")] = buildGetClassCall {
          argumentList = buildArgumentList {
            arguments += buildResolvedQualifier {
              packageFqName = scopeClassId.packageFqName
              relativeClassFqName = scopeClassId.relativeClassName
              symbol = scopeClassSymbol
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

  private fun buildGraphExtensionFactoryAnnotation(): FirAnnotation {
    val annotationClassSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(GRAPH_EXTENSION_FACTORY_CLASS_ID)
        as FirRegularClassSymbol
    return buildAnnotation {
      annotationTypeRef = annotationClassSymbol.defaultType().toFirResolvedTypeRef()
      argumentMapping = buildAnnotationArgumentMapping()
    }
  }

  private fun buildProvidesAnnotation(): FirAnnotation {
    val annotationClassSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(PROVIDES_CLASS_ID) as FirRegularClassSymbol
    return buildAnnotation {
      annotationTypeRef = annotationClassSymbol.defaultType().toFirResolvedTypeRef()
      argumentMapping = buildAnnotationArgumentMapping()
    }
  }

  private fun ConeKotlinType.toFirResolvedTypeRef(): FirResolvedTypeRef {
    return buildResolvedTypeRef { coneType = this@toFirResolvedTypeRef }
  }

  override fun getContributionHints(): List<ContributionHint> {
    return annotatedClasses.map { classSymbol ->
      ContributionHint(
        contributingClassId =
          classSymbol.classId
            .createNestedClassId(LOGIN_GRAPH_NAME)
            .createNestedClassId(FACTORY_NAME),
        scope = APP_SCOPE_CLASS_ID,
      )
    }
  }

  class Factory : MetroFirDeclarationGenerationExtension.Factory {
    override fun create(
      session: FirSession,
      options: MetroOptions,
      compatContext: CompatContext,
    ): MetroFirDeclarationGenerationExtension = GenerateGraphExtensionExtension(session)
  }
}

/** Provides contribution metadata for the generated `Factory` interfaces. */
internal class GenerateGraphExtensionContributionExtension(private val session: FirSession) :
  MetroContributionExtension {

  private val predicate =
    LookupPredicate.BuilderContext.annotated(GenerateGraphExtensionExtension.ANNOTATION_FQ_NAME)

  private val annotatedClasses by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(predicate)
      .filterIsInstance<FirRegularClassSymbol>()
      .toList()
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(predicate)
  }

  override fun getContributions(
    scopeClassId: ClassId,
    typeResolverFactory: MetroFirTypeResolver.Factory,
  ): List<MetroContributionExtension.Contribution> {
    if (scopeClassId != APP_SCOPE_CLASS_ID) return emptyList()

    return annotatedClasses.mapNotNull { parentSymbol ->
      val factoryClassId =
        parentSymbol.classId.createNestedClassId(LOGIN_GRAPH_NAME).createNestedClassId(FACTORY_NAME)
      val metroContributionClassId =
        MetroContributions.metroContributionClassId(factoryClassId, scopeClassId)
      val metroContributionSymbol =
        session.symbolProvider.getClassLikeSymbolByClassId(metroContributionClassId)
          as? FirRegularClassSymbol ?: return@mapNotNull null

      MetroContributionExtension.Contribution(
        supertype = metroContributionSymbol.defaultType(),
        replaces = emptyList(),
        originClassId = factoryClassId,
      )
    }
  }

  class Factory : MetroContributionExtension.Factory {
    override fun create(
      session: FirSession,
      options: MetroOptions,
      compatContext: CompatContext,
    ): MetroContributionExtension {
      return GenerateGraphExtensionContributionExtension(session)
    }
  }
}
