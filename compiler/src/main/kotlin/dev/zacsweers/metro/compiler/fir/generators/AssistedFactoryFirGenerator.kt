// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.FirInjectConstructor
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.MetroFirValueParameter
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.copyParameters
import dev.zacsweers.metro.compiler.fir.findAssistedInjectConstructors
import dev.zacsweers.metro.compiler.fir.generateMemberFunction
import dev.zacsweers.metro.compiler.fir.hasOrigin
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.getContainingClassSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

/**
 * For assisted injection, we can generate the assisted factory _for_ the assisted type as a nested
 * interface of the annotated class. This saves the user some boilerplate.
 *
 * Note this is specifically for generating `@AssistedFactory`-annotated declarations, not for
 * generating assisted factory impls.
 */
internal class AssistedFactoryFirGenerator(session: FirSession, compatContext: CompatContext) :
  FirDeclarationGenerationExtension(session), CompatContext by compatContext {

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(
      session.predicates.assistedAnnotationPredicate,
      session.predicates.assistedAnnotationPredicate,
    )
  }

  /**
   * Maps a class to the assisted-inject constructor that its generated `Factory` uses. The value is
   * null when the class gets no generated factory.
   *
   * Only [getNestedClassifiersNames] computes values. Later callbacks use `getValueIfComputed`, so
   * they never resolve anything about the owner class themselves.
   */
  private val assistedInjectClasses: FirCache<FirClassSymbol<*>, FirInjectConstructor?, Unit> =
    session.firCachesFactory.createCache { classSymbol, _ ->
      computeAssistedInjectClass(classSymbol)
    }

  /**
   * Returns the owner class and its assisted-inject constructor if [classSymbol] is a `Factory`
   * that this generator made, or null otherwise.
   *
   * The name and origin checks come first, so the containing class is only looked up for our own
   * generated class. FIR asks for a generated class's members only after it called
   * [getNestedClassifiersNames] on the owner, so the cache value is already there.
   */
  private fun generatedFactoryTarget(
    classSymbol: FirClassSymbol<*>,
  ): Pair<FirClassSymbol<*>, FirInjectConstructor>? {
    if (classSymbol.name != Symbols.Names.FactoryClass) {
      return null
    }

    if (!classSymbol.hasOrigin(Keys.Default)) {
      return null
    }

    val targetClass = classSymbol.getContainingClassSymbol() as? FirClassSymbol<*>
    if (targetClass == null) {
      return null
    }

    val constructor = assistedInjectClasses.getValueIfComputed(targetClass)
    if (constructor == null) {
      return null
    }

    return targetClass to constructor
  }

  // Called for generating callables
  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    if (generatedFactoryTarget(classSymbol) != null) {
      // Need to generate a SAM create() for this
      return setOf(Symbols.Names.create)
    }

    return super.getCallableNamesForClass(classSymbol, context)
  }

  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    val owner = context?.owner ?: return emptyList()

    if (callableId.callableName != Symbols.Names.create) {
      return super.generateFunctions(callableId, context)
    }

    val target = generatedFactoryTarget(owner)
    if (target == null) {
      return super.generateFunctions(callableId, context)
    }

    val (targetClass, constructor) = target

    // Generate a create() function

    // Collect assisted params, we need to potentially port their assisted annotations if they
    // have custom identifiers
    val assistedParams =
      constructor.constructor?.valueParameterSymbols.orEmpty().mapNotNull { param ->
        if (!param.isAnnotatedWithAny(session, session.classIds.assistedAnnotations)) {
          return@mapNotNull null
        }
        MetroFirValueParameter(session, param)
      }
    val createFunction = generateCreateFunction(owner, assistedParams, targetClass, callableId)
    return listOf(createFunction.symbol as FirNamedFunctionSymbol)
  }

  private fun FirDeclarationGenerationExtension.generateCreateFunction(
    owner: FirClassLikeSymbol<*>,
    assistedParams: List<MetroFirValueParameter>,
    targetClass: FirClassLikeSymbol<*>,
    callableId: CallableId,
  ): FirFunction {
    return generateMemberFunction(
      owner = owner,
      returnTypeRef = targetClass.constructType().toFirResolvedTypeRef(),
      modality = Modality.ABSTRACT,
      callableId = callableId,
      origin = Keys.GeneratedAssistedFactoryCreateFunction.origin,
    ) {
      copyParameters(
        functionBuilder = this,
        sourceParameters = assistedParams,
        copyParameterDefaults = true,
      ) { original ->
        // Force a resolved type ref. The source constructor's value parameters can still be at
        // ANNOTATION_ARGUMENTS under LL FIR, so copying their returnTypeRef directly would leave a
        // FirUserTypeRef on the generated parameter and crash later argument resolution.
        this.returnTypeRef = original.symbol.resolvedReturnTypeRef
      }
    }
  }

  // Called for generating nested names
  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    if (assistedInjectClasses.getValue(classSymbol, Unit) != null) {
      // We want to generate an assisted factory
      return setOf(Symbols.Names.FactoryClass)
    }

    return super.getNestedClassifiersNames(classSymbol, context)
  }

  /**
   * Returns the first assisted-inject constructor of [classSymbol], or null if it has none or
   * already declares a nested `Factory`.
   */
  private fun computeAssistedInjectClass(classSymbol: FirClassSymbol<*>): FirInjectConstructor? {
    val constructor = classSymbol.findAssistedInjectConstructors(session).firstOrNull()
    if (constructor == null) {
      return null
    }

    // Check if there is already a nested factory. If there is, do nothing.
    val existingFactory =
      @OptIn(DirectDeclarationsAccess::class)
      classSymbol.declarationSymbols.filterIsInstance<FirClassSymbol<*>>().singleOrNull {
        // TODO also check for factory annotation? Not sure what else we'd do anyway though
        it.name == Symbols.Names.FactoryClass
      }
    if (existingFactory != null) {
      return null
    }

    return constructor
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
    return createNestedClass(owner, name, Keys.Default, classKind = ClassKind.INTERFACE) {
        // annoyingly not implicit from the class kind
        modality = Modality.ABSTRACT
        status { isFun = true }
      }
      .apply {
        replaceAnnotationsSafe(
          annotations +
            buildAnnotation {
              val assistedFactoryClass =
                session.symbolProvider.getClassLikeSymbolByClassId(
                  session.classIds.metroAssistedFactory,
                ) as FirRegularClassSymbol
              annotationTypeRef = assistedFactoryClass.defaultType().toFirResolvedTypeRef()
              argumentMapping = buildAnnotationArgumentMapping()
            },
        )
      }
      .symbol
  }
}
