// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.api

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension
import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.builder.buildRegularClass
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirAnnotationResolvePhase
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationCall
import org.jetbrains.kotlin.fir.expressions.builder.buildArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildGetClassCall
import org.jetbrains.kotlin.fir.expressions.builder.buildResolvedQualifier
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.references.builder.buildResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.kotlinScopeProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.toEffectiveVisibility
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

private val CONTRIBUTES_TO_CLASS_ID =
  ClassId(FqName("dev.zacsweers.metro"), Name.identifier("ContributesTo"))
private val BINDS_CLASS_ID = ClassId(FqName("dev.zacsweers.metro"), Name.identifier("Binds"))

/**
 * Test extension that generates a `@ContributesTo` interface with a `@Binds` function for classes
 * annotated with `@GenerateBindsContribution`.
 *
 * For a class like:
 * ```
 * interface MyType
 * @Inject
 * @GenerateBindsContribution(AppScope::class)
 * class MyImpl : MyType
 * ```
 *
 * This generates:
 * ```
 * @ContributesTo(AppScope::class)
 * interface BindsContribution {
 *   @Binds fun bindMyImpl(impl: MyImpl): MyType
 * }
 * ```
 *
 * This exercises the scenario where a `@Binds` function exists inside a *generated* interface.
 */
internal class GenerateBindsContributionExtension(session: FirSession) :
  MetroFirDeclarationGenerationExtension(session) {

  companion object {
    val ANNOTATION_FQ_NAME = FqName("test.GenerateBindsContribution")
    val NESTED_INTERFACE_NAME = Name.identifier("BindsContribution")
  }

  object Key : GeneratedDeclarationKey()

  private val predicate = LookupPredicate.BuilderContext.annotated(ANNOTATION_FQ_NAME)

  private val annotatedClasses by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(predicate)
      .filterIsInstance<FirRegularClassSymbol>()
      .toList()
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(predicate)
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    // Check by classId name as a fallback, since predicate-based provider may not match
    // when multiple MetroFirDeclarationGenerationExtension instances share a composite.
    val matchesPredicate = classSymbol in annotatedClasses
    val matchesByAnnotation =
      classSymbol.resolvedCompilerAnnotationsWithClassIds.any {
        it.toAnnotationClassIdSafe(session)?.asSingleFqName() == ANNOTATION_FQ_NAME
      }
    if (matchesPredicate || matchesByAnnotation) return setOf(NESTED_INTERFACE_NAME)
    return emptySet()
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    if (name != NESTED_INTERFACE_NAME) return null
    val matchesByAnnotation =
      owner.resolvedCompilerAnnotationsWithClassIds.any {
        it.toAnnotationClassIdSafe(session)?.asSingleFqName() == ANNOTATION_FQ_NAME
      }
    if (owner !in annotatedClasses && !matchesByAnnotation) return null

    val nestedClassId = owner.classId.createNestedClassId(name)
    val classSymbol = FirRegularClassSymbol(nestedClassId)

    // Extract the bound type from the class's supertypes.
    val boundType = extractBoundType(owner) ?: return null

    // Build the class first so classSymbol.fir is initialized (createMemberFunction
    // accesses owner.fir for defaultType/resolvedStatus/classKind).
    val klass = buildRegularClass {
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
    }

    // Now build the @Binds function using the higher-level plugin API (avoids version-specific
    // FirSimpleFunction/FirNamedFunction types that changed in Kotlin 2.3.20).
    val implType = owner.defaultType()
    val paramName =
      Name.identifier(owner.classId.shortClassName.identifier.replaceFirstChar { it.lowercase() })
    val functionName = Name.identifier("bind${owner.classId.shortClassName.identifier}")

    val bindsFunction =
      createMemberFunction(classSymbol, Key, functionName, boundType) {
        valueParameter(paramName, implType, key = Key)
      }
    bindsFunction.replaceAnnotations(listOf(buildBindsAnnotationCall(bindsFunction.symbol)))

    // Add the function to the already-built class's declarations
    @OptIn(DirectDeclarationsAccess::class) @Suppress("UNCHECKED_CAST")
    (klass.declarations as MutableList<FirDeclaration>).add(bindsFunction)

    return klass.symbol
  }

  @OptIn(SymbolInternals::class)
  private fun extractBoundType(classSymbol: FirClassSymbol<*>): ConeKotlinType? {
    // Extract the bound type from the class's supertypes. During the SUPERTYPES phase,
    // resolved annotations aren't available yet, so we use the raw FIR supertypes instead.
    // Find the first non-trivial supertype by looking at FirUserTypeRef names.
    for (superTypeRef in classSymbol.fir.superTypeRefs) {
      val userTypeRef = superTypeRef as? FirUserTypeRef ?: continue
      val qualifierName = userTypeRef.qualifier.lastOrNull()?.name ?: continue
      // Skip kotlin.Any
      if (qualifierName.asString() == "Any") continue
      // Look up the class by simple name in the same package
      val packageFqName = classSymbol.classId.packageFqName
      val candidateClassId = ClassId(packageFqName, qualifierName)
      val candidateSymbol =
        session.symbolProvider.getClassLikeSymbolByClassId(candidateClassId)
          as? FirRegularClassSymbol ?: continue
      return candidateSymbol.defaultType()
    }
    return null
  }

  @OptIn(DirectDeclarationsAccess::class)
  private fun buildBindsAnnotationCall(containingSymbol: FirBasedSymbol<*>): FirAnnotationCall {
    val annotationClassSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(BINDS_CLASS_ID) as FirRegularClassSymbol
    return buildAnnotationCall {
      annotationTypeRef = annotationClassSymbol.defaultType().toFirResolvedTypeRef()
      argumentMapping = buildAnnotationArgumentMapping()
      calleeReference = buildResolvedNamedReference {
        name = BINDS_CLASS_ID.shortClassName
        resolvedSymbol =
          (annotationClassSymbol as FirClassSymbol<*>)
            .declarationSymbols
            .filterIsInstance<FirConstructorSymbol>()
            .first()
      }
      containingDeclarationSymbol = containingSymbol
      annotationResolvePhase = FirAnnotationResolvePhase.Types
    }
  }

  private fun buildContributesToAnnotation(scopeClassId: ClassId): FirAnnotation {
    val annotationClassSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(CONTRIBUTES_TO_CLASS_ID)
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

  private fun ConeKotlinType.toFirResolvedTypeRef(): FirResolvedTypeRef {
    return buildResolvedTypeRef { coneType = this@toFirResolvedTypeRef }
  }

  override fun getContributionHints(): List<ContributionHint> {
    return annotatedClasses.map { classSymbol ->
      ContributionHint(
        contributingClassId = classSymbol.classId.createNestedClassId(NESTED_INTERFACE_NAME),
        scope = APP_SCOPE_CLASS_ID,
      )
    }
  }

  class Factory : MetroFirDeclarationGenerationExtension.Factory {
    override fun create(
      session: FirSession,
      options: MetroOptions,
    ): MetroFirDeclarationGenerationExtension = GenerateBindsContributionExtension(session)
  }
}

/** Provides contribution metadata for the generated `BindsContribution` interfaces. */
internal class GenerateBindsContributionMetroExtension(private val session: FirSession) :
  MetroContributionExtension {

  private val predicate =
    LookupPredicate.BuilderContext.annotated(GenerateBindsContributionExtension.ANNOTATION_FQ_NAME)

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
    scopeClassId: ClassId
  ): List<MetroContributionExtension.Contribution> {
    if (scopeClassId != APP_SCOPE_CLASS_ID) return emptyList()

    return annotatedClasses.mapNotNull { parentSymbol ->
      val contributionClassId =
        parentSymbol.classId.createNestedClassId(
          GenerateBindsContributionExtension.NESTED_INTERFACE_NAME
        )
      val metroContributionClassId =
        MetroContributions.metroContributionClassId(contributionClassId, scopeClassId)
      val metroContributionSymbol =
        session.symbolProvider.getClassLikeSymbolByClassId(metroContributionClassId)
          as? FirRegularClassSymbol ?: return@mapNotNull null

      MetroContributionExtension.Contribution(
        supertype = metroContributionSymbol.defaultType(),
        replaces = emptyList(),
        originClassId = parentSymbol.classId,
      )
    }
  }

  class Factory : MetroContributionExtension.Factory {
    override fun create(session: FirSession, options: MetroOptions): MetroContributionExtension {
      return GenerateBindsContributionMetroExtension(session)
    }
  }
}
