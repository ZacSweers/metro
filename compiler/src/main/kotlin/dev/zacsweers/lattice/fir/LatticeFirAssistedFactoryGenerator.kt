package dev.zacsweers.lattice.fir

import dev.zacsweers.lattice.LatticeClassIds
import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.fqName
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.EffectiveVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fakeElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildSimpleFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameter
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
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
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.ConstantValueKind

/**
 * For assisted injection, we can generate the assisted factory _for_ the assisted type as a nested
 * interface of the annotated class. This saves the user some boilerplate.
 */
internal class LatticeFirAssistedFactoryGenerator(
  session: FirSession,
  private val latticeClassIds: LatticeClassIds,
) : FirDeclarationGenerationExtension(session) {
  companion object {
    fun getFactory(latticeClassIds: LatticeClassIds) = Factory { session ->
      LatticeFirAssistedFactoryGenerator(session, latticeClassIds)
    }
  }

  private val assistedInjectAnnotationPredicate by lazy {
    annotated(latticeClassIds.assistedInjectAnnotations.map { it.fqName })
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
                val assistedAnnotation =
                  param.annotationsIn(session, latticeClassIds.assistedAnnotations).singleOrNull()
                    ?: return@mapNotNull null
                param to assistedAnnotation
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

  private fun FirExtension.generateCreateFunction2(
    assistedParams: List<Pair<FirValueParameterSymbol, FirAnnotation>>,
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
          EffectiveVisibility.Public,
        )

      this.name = callableId.callableName
      this.origin = LatticeKey.origin
      this.moduleData = session.moduleData

      val functionSymbol = FirNamedFunctionSymbol(callableId)
      this.symbol = functionSymbol
      this.returnTypeRef = targetClass.constructType().toFirResolvedTypeRef()
      for ((param, assistedAnnotation) in assistedParams) {
        val identifier = assistedAnnotation.getStringArgument(LatticeSymbols.Names.Value, session)
        // TODO can this work?
        //  buildValueParameterCopy(param.fir)
        valueParameters += buildValueParameter {
          this.name = param.name
          this.returnTypeRef = param.resolvedReturnTypeRef
          this.source = factoryClass.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated)

          moduleData = session.moduleData
          containingFunctionSymbol = functionSymbol
          origin = LatticeKey.origin

          this.symbol = FirValueParameterSymbol(LatticeSymbols.Names.Value)
          isCrossinline = false
          isNoinline = false
          isVararg = false
          // resolvePhase = FirResolvePhase.BODY_RESOLVE

          if (identifier != null) {
            buildAnnotation {
              val assistedAnnotationClass =
                session.symbolProvider.getClassLikeSymbolByClassId(latticeClassIds.latticeAssisted)
                  as FirRegularClassSymbol
              annotationTypeRef = assistedAnnotationClass.defaultType().toFirResolvedTypeRef()
              argumentMapping = buildAnnotationArgumentMapping {
                mapping[LatticeSymbols.Names.Value] =
                  buildLiteralExpression(
                    source = null,
                    kind = ConstantValueKind.String,
                    value = identifier,
                    setType = false,
                  )
              }
            }
          }
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
      if (classSymbol.isAnnotatedWithAny(session, latticeClassIds.assistedInjectAnnotations)) {
        classSymbol.declarationSymbols.filterIsInstance<FirConstructorSymbol>().singleOrNull {
          it.isPrimary
        }
      } else {
        classSymbol.declarationSymbols.filterIsInstance<FirConstructorSymbol>().firstOrNull {
          it.isAnnotatedWithAny(session, latticeClassIds.assistedInjectAnnotations)
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
                  latticeClassIds.latticeAssistedFactory
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
