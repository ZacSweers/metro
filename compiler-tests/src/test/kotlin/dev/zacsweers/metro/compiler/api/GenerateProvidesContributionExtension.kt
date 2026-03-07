// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.api

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension
import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
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
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.toEffectiveVisibility
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

private val CONTRIBUTES_TO_CLASS_ID =
  ClassId(FqName("dev.zacsweers.metro"), Name.identifier("ContributesTo"))
private val PROVIDES_CLASS_ID = ClassId(FqName("dev.zacsweers.metro"), Name.identifier("Provides"))

/**
 * Test extension that generates a `@ContributesTo` interface with a `@Provides` function for
 * classes annotated with `@GenerateProvidesContribution`.
 *
 * This exercises the scenario where a `@Provides` function exists inside a *generated* interface,
 * which requires the fix in `ProvidesFactoryFirGenerator` to eagerly set the `Factory<T>` supertype
 * (since `FirSupertypeGenerationExtension` is not called for deeply nested generated classes).
 */
internal class GenerateProvidesContributionExtension(session: FirSession) :
  MetroFirDeclarationGenerationExtension(session) {

  companion object {
    val ANNOTATION_FQ_NAME = FqName("test.GenerateProvidesContribution")
    val NESTED_INTERFACE_NAME = Name.identifier("ProvidesContribution")
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
    if (classSymbol in annotatedClasses) return setOf(NESTED_INTERFACE_NAME)
    return emptySet()
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    if (name != NESTED_INTERFACE_NAME) return null
    if (owner !in annotatedClasses) return null

    val nestedClassId = owner.classId.createNestedClassId(name)
    val classSymbol = FirRegularClassSymbol(nestedClassId)

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

    // Now build the @Provides function using the higher-level plugin API (avoids version-specific
    // FirSimpleFunction/FirNamedFunction types that changed in Kotlin 2.3.20).
    val outerClassType = owner.defaultType()
    val functionName = Name.identifier("provide${owner.classId.shortClassName.identifier}")

    val providesFunction = createMemberFunction(classSymbol, Key, functionName, outerClassType)
    // Override modality to OPEN since this @Provides function needs a body (added in IR)
    providesFunction.replaceStatus(
      FirResolvedDeclarationStatusImpl(
        Visibilities.Public,
        Modality.OPEN,
        Visibilities.Public.toEffectiveVisibility(owner, forClass = true),
      )
    )
    providesFunction.replaceAnnotations(
      listOf(buildProvidesAnnotationCall(providesFunction.symbol))
    )

    // Add the function to the already-built class's declarations
    @OptIn(DirectDeclarationsAccess::class) @Suppress("UNCHECKED_CAST")
    (klass.declarations as MutableList<FirDeclaration>).add(providesFunction)

    return klass.symbol
  }

  @OptIn(DirectDeclarationsAccess::class)
  private fun buildProvidesAnnotationCall(containingSymbol: FirBasedSymbol<*>): FirAnnotationCall {
    val annotationClassSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(PROVIDES_CLASS_ID) as FirRegularClassSymbol
    return buildAnnotationCall {
      annotationTypeRef = annotationClassSymbol.defaultType().toFirResolvedTypeRef()
      argumentMapping = buildAnnotationArgumentMapping()
      calleeReference = buildResolvedNamedReference {
        name = PROVIDES_CLASS_ID.shortClassName
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
    ): MetroFirDeclarationGenerationExtension = GenerateProvidesContributionExtension(session)
  }
}

/** Provides contribution metadata for the generated `ProvidesContribution` interfaces. */
internal class GenerateProvidesContributionMetroExtension(private val session: FirSession) :
  MetroContributionExtension {

  private val predicate =
    LookupPredicate.BuilderContext.annotated(
      GenerateProvidesContributionExtension.ANNOTATION_FQ_NAME
    )

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
          GenerateProvidesContributionExtension.NESTED_INTERFACE_NAME
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
      return GenerateProvidesContributionMetroExtension(session)
    }
  }
}

/** IR extension that fills in the `@Provides` function body with `return ClassName()`. */
class GenerateProvidesContributionIrExtension : IrGenerationExtension {
  companion object {
    val ORIGIN = IrDeclarationOrigin.GeneratedByPlugin(GenerateProvidesContributionExtension.Key)
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    moduleFragment.transformChildrenVoid(
      object : IrElementTransformerVoid() {
        override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
          if (declaration.origin == ORIGIN && declaration.body == null) {
            val returnType = declaration.returnType
            val classSymbol =
              returnType.classOrNull ?: return super.visitSimpleFunction(declaration)
            val constructor =
              classSymbol.owner.primaryConstructor ?: return super.visitSimpleFunction(declaration)

            declaration.body =
              pluginContext.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
                statements +=
                  IrReturnImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    pluginContext.irBuiltIns.nothingType,
                    declaration.symbol,
                    IrConstructorCallImpl(
                      UNDEFINED_OFFSET,
                      UNDEFINED_OFFSET,
                      returnType,
                      constructor.symbol,
                      typeArgumentsCount = 0,
                      constructorTypeArgumentsCount = 0,
                    ),
                  )
              }
          }
          return super.visitSimpleFunction(declaration)
        }
      }
    )
  }
}
