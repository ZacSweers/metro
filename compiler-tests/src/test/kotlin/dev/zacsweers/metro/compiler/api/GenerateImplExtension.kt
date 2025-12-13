package dev.zacsweers.metro.compiler.api

import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.api.fir.MetroContributionExtension
import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension
import dev.zacsweers.metro.compiler.ir.generateDefaultConstructorBody
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.fir.FirSession
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
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.name.StandardClassIds

val INJECT_CLASS_ID = ClassId(FqName("dev.zacsweers.metro"), Name.identifier("Inject"))
val APP_SCOPE_CLASS_ID = ClassId(FqName("dev.zacsweers.metro"), Name.identifier("AppScope"))
val CONTRIBUTES_BINDING_CLASS_ID =
  ClassId(FqName("dev.zacsweers.metro"), Name.identifier("ContributesBinding"))

/**
 * Test extension that generates an `Impl` nested class for interfaces annotated with
 * `@GenerateImpl`.
 *
 * The generated class has `@Inject` annotation, which Metro's `InjectedClassFirGenerator`
 * processes. The contribution to the graph is made via [GenerateImplContributionExtension].
 */
internal class GenerateImplExtension(session: FirSession) :
  MetroFirDeclarationGenerationExtension(session) {

  companion object {
    val GENERATE_IMPL_FQ_NAME = FqName("test.GenerateImpl")
    val IMPL_NAME = Name.identifier("Impl")
  }

  object GeneratedImplKey : GeneratedDeclarationKey()

  private val predicate = LookupPredicate.BuilderContext.annotated(GENERATE_IMPL_FQ_NAME)

  private val generateImplClasses by lazy {
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
    if (classSymbol !in generateImplClasses) return emptySet()
    return setOf(IMPL_NAME)
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    if (owner !in generateImplClasses) return null
    if (name != IMPL_NAME) return null

    return createNestedClass(owner, IMPL_NAME, GeneratedImplKey) {
        // Implement the parent interface
        superType(owner.defaultType())
      }
      .apply {
        // Add @Inject annotation - this triggers Metro's InjectedClassFirGenerator
        val injectAnnotation = buildSimpleAnnotation {
          session.symbolProvider.getClassLikeSymbolByClassId(INJECT_CLASS_ID)
            as FirRegularClassSymbol
        }

        // Add @ContributesBinding(scope = AppScope::class)
        val contributesBindingAnnotation =
          buildAnnotationWithScopeArg(CONTRIBUTES_BINDING_CLASS_ID, APP_SCOPE_CLASS_ID)

        replaceAnnotations(listOf(injectAnnotation, contributesBindingAnnotation))
      }
      .symbol
  }

  private fun buildAnnotationWithScopeArg(
    annotationClassId: ClassId,
    scopeClassId: ClassId,
  ): FirAnnotation {
    val annotationClassSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(annotationClassId)
        as? FirRegularClassSymbol ?: error("Could not find annotation class: $annotationClassId")

    val scopeSymbol =
      session.symbolProvider.getClassLikeSymbolByClassId(scopeClassId)
        ?: error("Could not find scope class: $scopeClassId")
    val scopeType = (scopeSymbol as FirRegularClassSymbol).defaultType()

    return buildAnnotation {
      annotationTypeRef = annotationClassSymbol.defaultType().toFirResolvedTypeRef()
      argumentMapping = buildAnnotationArgumentMapping {
        mapping[Name.identifier("scope")] = buildGetClassCall {
          // The argument should be a FirResolvedQualifier for resolvedClassId() to work
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

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    val parentClassId = classSymbol.classId.parentClassId ?: return emptySet()
    if (generateImplClasses.none { it.classId == parentClassId }) return emptySet()
    if (classSymbol.classId.shortClassName != IMPL_NAME) return emptySet()
    return setOf(SpecialNames.INIT)
  }

  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val parentClassId = context.owner.classId.parentClassId ?: return emptyList()
    if (generateImplClasses.none { it.classId == parentClassId }) return emptyList()
    if (context.owner.classId.shortClassName != IMPL_NAME) return emptyList()

    return listOf(createConstructor(context.owner, GeneratedImplKey, isPrimary = true).symbol)
  }

  private fun buildSimpleAnnotation(symbol: () -> FirRegularClassSymbol): FirAnnotation {
    return buildAnnotation {
      annotationTypeRef = symbol().defaultType().toFirResolvedTypeRef()
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
    ): MetroFirDeclarationGenerationExtension {
      return GenerateImplExtension(session)
    }
  }
}

/**
 * Contribution extension that provides metadata for generated `Impl` classes to Metro's
 * `ContributedInterfaceSupertypeGenerator`.
 *
 * This is necessary because the predicate-based provider in
 * `ContributedInterfaceSupertypeGenerator` only sees source declarations, not generated ones. By
 * implementing [MetroContributionExtension], we can provide contribution metadata directly for our
 * generated `Bar.Impl` class.
 */
internal class GenerateImplContributionExtension(private val session: FirSession) :
  MetroContributionExtension {

  private val predicate =
    LookupPredicate.BuilderContext.annotated(GenerateImplExtension.GENERATE_IMPL_FQ_NAME)

  private val generateImplClasses by lazy {
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
    // Only provide contributions for AppScope (the scope we generate for)
    if (scopeClassId != APP_SCOPE_CLASS_ID) return emptyList()

    return generateImplClasses.mapNotNull { parentSymbol ->
      // The generated Impl class is a nested class of the @GenerateImpl-annotated interface
      val implClassId = parentSymbol.classId.createNestedClassId(GenerateImplExtension.IMPL_NAME)

      // Use the public API to compute the expected MetroContribution ClassId
      val metroContributionClassId =
        MetroContributions.metroContributionClassId(implClassId, scopeClassId)

      val metroContributionSymbol =
        session.symbolProvider.getClassLikeSymbolByClassId(metroContributionClassId)
          as? FirRegularClassSymbol ?: return@mapNotNull null

      MetroContributionExtension.Contribution(
        supertype = metroContributionSymbol.defaultType(),
        replaces = emptyList(),
        originClassId = implClassId,
      )
    }
  }

  class Factory : MetroContributionExtension.Factory {
    override fun create(session: FirSession, options: MetroOptions): MetroContributionExtension {
      return GenerateImplContributionExtension(session)
    }
  }
}

/**
 * IR extension that implements constructor bodies for classes generated by [GenerateImplExtension].
 *
 * FIR extensions only generate declaration stubs - the actual constructor body must be implemented
 * in IR.
 */
class GenerateImplIrExtension : IrGenerationExtension {
  companion object {
    val IMPL_ORIGIN = IrDeclarationOrigin.GeneratedByPlugin(GenerateImplExtension.GeneratedImplKey)
  }

  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    moduleFragment.transformChildrenVoid(
      object : IrElementTransformerVoid() {
        override fun visitConstructor(declaration: IrConstructor): IrStatement {
          if (declaration.origin == IMPL_ORIGIN && declaration.body == null) {
            context(pluginContext) { declaration.apply { body = generateDefaultConstructorBody() } }
          }
          return super.visitConstructor(declaration)
        }
      }
    )
  }
}
