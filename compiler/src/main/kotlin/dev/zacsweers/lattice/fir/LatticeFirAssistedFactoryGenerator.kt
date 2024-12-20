package dev.zacsweers.lattice.fir

import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.fqName
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildSimpleFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameterCopy
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.toEffectiveVisibility
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

/**
 * For assisted injection, we can generate the assisted factory _for_ the assisted type as a nested
 * interface of the annotated class. This saves the user some boilerplate.
 */
internal class LatticeFirAssistedFactoryGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {
  private val assistedInjectAnnotationPredicate by lazy {
    annotated(session.latticeClassIds.assistedInjectAnnotations.map { it.fqName })
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(assistedInjectAnnotationPredicate)
  }

  private val assistedInjectClasses = mutableMapOf<FirClassLikeSymbol<*>, FirConstructorSymbol>()
  private val assistedFactoriesToClasses =
    mutableMapOf<FirClassLikeSymbol<*>, FirClassLikeSymbol<*>>()
  private val createIdsToFactories = mutableMapOf<CallableId, FirClassSymbol<*>>()

  // Called for generating callables
  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    assistedFactoriesToClasses[classSymbol]?.let { targetClass ->
      assistedInjectClasses[targetClass]?.let { constructor ->
        // Need to generate a SAM create() for this
        val id = CallableId(classSymbol.classId, LatticeSymbols.Names.CreateFunction)
        createIdsToFactories[id] = classSymbol
        return setOf(id.callableName)
      }
    }

    return super.getCallableNamesForClass(classSymbol, context)
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    createIdsToFactories[callableId]?.let { factoryClass ->
      assistedFactoriesToClasses[factoryClass]?.let { targetClass ->
        assistedInjectClasses[targetClass]?.let { constructor ->
          // Generate a create() function

          // Collect assisted params, we need to potentially port their assisted annotations if they
          // have custom identifiers
          val assistedParams =
            constructor.valueParameterSymbols
              // TODO need a predicate?
              .mapNotNull { param ->
                param
                  .annotationsIn(session, session.latticeClassIds.assistedAnnotations)
                  .singleOrNull() ?: return@mapNotNull null
                param
              }
          val createFunction =
            generateCreateFunction2(assistedParams, targetClass, factoryClass, callableId)
          return listOf(createFunction.symbol)
        }
      }
    }
    return super.generateFunctions(callableId, context)
  }

  // TODO try a new approach with buildSimpleFunction in order
  private fun FirExtension.generateCreateFunction(
    assistedParams: List<Pair<FirValueParameterSymbol, FirAnnotation>>,
    targetClass: FirClassLikeSymbol<*>,
    factoryClass: FirClassSymbol<*>,
    callableId: CallableId,
  ): FirSimpleFunction {
    return createMemberFunction(
      factoryClass,
      LatticeKey,
      callableId.callableName,
      returnType = targetClass.constructType(),
    ) {
      this.modality = Modality.ABSTRACT

      for ((param, assistedAnnotation) in assistedParams) {
        val identifier = assistedAnnotation.getStringArgument(LatticeSymbols.Names.Value, session)
        // Simple value parameters don't support arguments
        valueParameter(param.name, param.resolvedReturnTypeRef.coneType, key = LatticeKey)
      }
    }
  }

  @OptIn(SymbolInternals::class)
  private fun FirExtension.generateCreateFunction2(
    assistedParams: List<FirValueParameterSymbol>,
    targetClass: FirClassLikeSymbol<*>,
    factoryClass: FirClassSymbol<*>,
    callableId: CallableId,
  ): FirSimpleFunction {
    return buildSimpleFunction {
      // TODO is there a non-impl API for this?
      status =
        FirResolvedDeclarationStatusImpl(
          Visibilities.Public,
          Modality.ABSTRACT,
          Visibilities.Public.toEffectiveVisibility(targetClass, forClass = true),
        )

      this.name = callableId.callableName
      this.origin = LatticeKey.origin
      this.moduleData = session.moduleData

      this.symbol = FirNamedFunctionSymbol(callableId)
      this.returnTypeRef = targetClass.constructType().toFirResolvedTypeRef()

      for (original in assistedParams) {
        valueParameters +=
          buildValueParameterCopy(original.fir) {
            origin = LatticeKey.origin
            symbol = FirValueParameterSymbol(original.name)
            containingFunctionSymbol = this@buildSimpleFunction.symbol
            // TODO default values are copied over in this case, is that enough or do they need
            //  references transformed?
          }
      }
    }
  }

  // Called for generating nested names
  // TODO avoid if there's already a nested factory
  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    val constructor =
      if (
        classSymbol.isAnnotatedWithAny(session, session.latticeClassIds.assistedInjectAnnotations)
      ) {
        classSymbol.declarationSymbols.filterIsInstance<FirConstructorSymbol>().singleOrNull {
          it.isPrimary
        }
      } else {
        classSymbol.declarationSymbols.filterIsInstance<FirConstructorSymbol>().firstOrNull {
          it.isAnnotatedWithAny(session, session.latticeClassIds.assistedInjectAnnotations)
        }
      }

    if (constructor != null) {
      // Check if there is already a nested factory. If there is, do nothing.
      val existingFactory =
        classSymbol.declarationSymbols.filterIsInstance<FirClassSymbol<*>>().singleOrNull {
          // TODO also check for factory annotation? Not sure what else we'd do anyway though
          it.name == LatticeSymbols.Names.Factory
        }
      if (existingFactory != null) {
        // TODO test this case
        return emptySet()
      }

      assistedInjectClasses[classSymbol] = constructor
      // We want to generate an assisted factory
      return setOf(LatticeSymbols.Names.Factory)
    }
    return super.getNestedClassifiersNames(classSymbol, context)
  }

  // Called for generating nested class declarations
  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    if (owner !is FirRegularClassSymbol) return null
    // This assumes that all callbacks are for assisted. If we ever make this broader in scope then
    // need to track their combos somewhere to check here
    return createNestedClass(owner, name, LatticeKey, classKind = ClassKind.INTERFACE) {
        status { isFun = true }
      }
      .apply {
        replaceAnnotations(
          annotations +
            buildAnnotation {
              val assistedFactoryClass =
                session.symbolProvider.getClassLikeSymbolByClassId(
                  session.latticeClassIds.latticeAssistedFactory
                ) as FirRegularClassSymbol
              annotationTypeRef = assistedFactoryClass.defaultType().toFirResolvedTypeRef()
              argumentMapping = buildAnnotationArgumentMapping()
            }
        )
      }
      .symbol
      .also { assistedFactoriesToClasses[it] = owner }
  }
}
