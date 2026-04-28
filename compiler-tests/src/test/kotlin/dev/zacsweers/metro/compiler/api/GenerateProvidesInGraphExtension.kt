// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.api

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.compat.CompatContext
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
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
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

private val DEPENDENCY_GRAPH_CLASS_ID =
  ClassId(FqName("dev.zacsweers.metro"), Name.identifier("DependencyGraph"))
private val PROVIDES_CLASS_ID = ClassId(FqName("dev.zacsweers.metro"), Name.identifier("Provides"))
private val IR_ONLY_FACTORIES_CLASS_ID =
  ClassId(FqName("dev.zacsweers.metro.internal"), Name.identifier("IROnlyFactories"))

private val APP_GRAPH_NAME = Name.identifier("AppGraph")
private val PROVIDER_NAME = Name.identifier("provideString")

/**
 * Test extension that generates a `@DependencyGraph(AppScope::class)` interface with a string
 * provider for classes annotated with `@GenerateProvidesInGraph`.
 *
 * For a class like:
 * ```
 * @GenerateProvidesInGraph
 * class Application
 * ```
 *
 * This generates a nested interface:
 * ```
 * class Application {
 *   @IROnlyFactories
 *   @DependencyGraph(AppScope::class)
 *   interface AppGraph {
 *     @Provides
 *     fun provideString(): String {
 *       return "Hello, @GenerateProvidesInGraph!"
 *     }
 *   }
 * }
 * ```
 */
internal class GenerateProvidesInGraphExtension(session: FirSession) :
  MetroFirDeclarationGenerationExtension(session) {

  companion object {
    val ANNOTATION_FQ_NAME = FqName("test.GenerateProvidesInGraph")
  }

  object Key : GeneratedDeclarationKey()

  private val predicate = LookupPredicate.BuilderContext.annotated(ANNOTATION_FQ_NAME)

  private val annotatedClasses by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(predicate)
      .filterIsInstance<FirRegularClassSymbol>()
      .toList()
  }

  /** ClassIds of AppGraph interfaces we generate (nested inside annotated classes). */
  private val generatedGraphClassIds by lazy {
    annotatedClasses.map { it.classId.createNestedClassId(APP_GRAPH_NAME) }.toSet()
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(predicate)
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    return if (classSymbol in annotatedClasses) {
      setOf(APP_GRAPH_NAME)
    } else {
      emptySet()
    }
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    return when (name) {
      APP_GRAPH_NAME -> generateAppGraph(owner, name)
      else -> null
    }
  }

  private fun generateAppGraph(owner: FirClassSymbol<*>, name: Name): FirClassLikeSymbol<*>? {
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
      annotations += buildIROnlyFactoriesAnnotation()
      annotations += buildDependencyGraphAnnotation(APP_SCOPE_CLASS_ID)
    }

    return classSymbol
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    if (classSymbol.classId in generatedGraphClassIds) return setOf(PROVIDER_NAME)
    return emptySet()
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    if (context == null) return emptyList()
    val owner = context.owner
    if (owner.classId !in generatedGraphClassIds) return emptyList()
    if (callableId.callableName != PROVIDER_NAME) return emptyList()

    // String type for the @Provides return type
    val stringSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(StandardClassIds.String)
        as FirRegularClassSymbol
    val stringType = stringSymbol.defaultType()

    // @Provides fun provideString(): String
    val provideStringFunction = createMemberFunction(owner, Key, PROVIDER_NAME, stringType)
    provideStringFunction.replaceAnnotations(listOf(buildProvidesAnnotation()))

    return listOf(provideStringFunction.symbol)
  }

  // -- Annotation builders --

  private fun buildIROnlyFactoriesAnnotation(): FirAnnotation {
    val annotationClassSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(IR_ONLY_FACTORIES_CLASS_ID)
        as FirRegularClassSymbol
    return buildAnnotation {
      annotationTypeRef = annotationClassSymbol.defaultType().toFirResolvedTypeRef()
      argumentMapping = buildAnnotationArgumentMapping()
    }
  }

  private fun buildDependencyGraphAnnotation(scopeClassId: ClassId): FirAnnotation {
    val annotationClassSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(DEPENDENCY_GRAPH_CLASS_ID)
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

  class Factory : MetroFirDeclarationGenerationExtension.Factory {
    override fun create(
      session: FirSession,
      options: MetroOptions,
      compatContext: CompatContext,
    ): MetroFirDeclarationGenerationExtension = GenerateProvidesInGraphExtension(session)
  }
}

/**
 * IR extension that fills in the `@Provides` function body with `return
 * "Hello, @GenerateProvidesInGraph!"`.
 */
class GenerateProvidersInGraphIrExtension : IrGenerationExtension {
  companion object {
    val ORIGIN = IrDeclarationOrigin.GeneratedByPlugin(GenerateProvidesInGraphExtension.Key)
  }

  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    moduleFragment.transformChildrenVoid(
      object : IrElementTransformerVoid() {
        override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
          if (declaration.origin == ORIGIN) {
            declaration.body =
              DeclarationIrBuilder(pluginContext, declaration.symbol).irBlockBody {
                +irReturn(irString("Hello, @GenerateProvidesInGraph!"))
              }
          }
          return super.visitSimpleFunction(declaration)
        }
      }
    )
  }
}
