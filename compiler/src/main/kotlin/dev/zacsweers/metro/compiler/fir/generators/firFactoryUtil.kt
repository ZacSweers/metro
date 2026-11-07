// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.MetroFirValueParameter
import dev.zacsweers.metro.compiler.fir.buildFullSubstitutionMap
import dev.zacsweers.metro.compiler.fir.buildHiddenFromObjCAnnotation
import dev.zacsweers.metro.compiler.fir.buildSimpleValueParameter
import dev.zacsweers.metro.compiler.fir.buildStaticAnnotations
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.copyParameters
import dev.zacsweers.metro.compiler.fir.generateMemberFunction
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.fir.wrapInProviderIfNecessary
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.containingClassForStaticMemberAttr
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirResolvedDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.builder.buildTypeParameterCopy
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.getContainingClassSymbol
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.scopes.ScopeFunctionRequiresPrewarm
import org.jetbrains.kotlin.fir.scopes.collectAllFunctions
import org.jetbrains.kotlin.fir.scopes.collectAllProperties
import org.jetbrains.kotlin.fir.scopes.processOverriddenFunctionsAndSelf
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.unwrapSubstitutionOverrides
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

internal fun FirDeclarationGenerationExtension.buildFactoryConstructor(
  context: MemberGenerationContext,
  instanceReceiver: ConeClassLikeType?,
  extensionReceiver: ConeClassLikeType?,
  valueParameters: List<MetroFirValueParameter>,
): FirConstructor =
  with(session.compatContext) {
    val owner = context.owner

    // Get the target class to build substitution map
    val targetClass = owner.getContainingClassSymbol() as? FirClassSymbol<*>
    val substitutionMap =
      if (targetClass != null) {
        val directMappings =
          targetClass.typeParameterSymbols.zip(owner.typeParameterSymbols).associate {
            (source, generated) ->
            source to generated.constructType()
          }
        buildFullSubstitutionMap(
          targetClass,
          directMappings,
          session,
        )
      } else {
        emptyMap()
      }
    val substitutor = substitutorByMap(substitutionMap, session)

    return createConstructor(
        owner,
        Keys.Default,
        isPrimary = true,
        generateDelegatedNoArgConstructorCall = true,
      ) {
        visibility = Visibilities.Private
        instanceReceiver?.let {
          valueParameter(Symbols.Names.instance, it, key = Keys.InstanceParameter)
        }
        extensionReceiver?.let {
          valueParameter(
            Symbols.Names.receiver,
            it.wrapInProviderIfNecessary(session, Symbols.ClassIds.metroProvider),
            key = Keys.ReceiverParameter,
          )
        }
        for (i in valueParameters.indices) {
          val valueParameter = valueParameters[i]
          if (
            valueParameter.symbol.isAnnotatedWithAny(session, session.classIds.assistedAnnotations)
          ) {
            continue
          }

          // Apply substitution to the type
          val originalType = valueParameter.factoryProviderType(session)
          val substitutedType = substitutor.substituteOrNull(originalType) ?: originalType

          valueParameter(
            valueParameter.name,
            substitutedType,
            key = Keys.RegularParameter,
          )
        }
      }
      .also { it.containingClassForStaticMemberAttr = owner.toLookupTag() }
  }

@OptIn(SymbolInternals::class)
internal fun FirDeclarationGenerationExtension.buildFactoryCreateFunction(
  context: MemberGenerationContext,
  returnTypeProvider: (List<FirTypeParameterRef>) -> ConeKotlinType,
  instanceReceiver: ConeClassLikeType?,
  extensionReceiver: ConeClassLikeType?,
  valueParameters: List<MetroFirValueParameter>,
): FirNamedFunctionSymbol =
  with(session.compatContext) {
    return generateMemberFunction(
        owner = context.owner,
        returnTypeProvider = returnTypeProvider,
        callableId = CallableId(context.owner.classId, Symbols.Names.create),
        origin = Keys.FactoryCreateFunction.origin,
      ) {
        val thisFunctionSymbol = symbol

        val containingClassSymbol = context.owner.getContainingClassSymbol()!!
        val ownerToCopyTypeParametersFrom: FirClassSymbol<*> =
          if (context.owner.isCompanion) {
            // companion -> class factory -> original class
            containingClassSymbol.getContainingClassSymbol()!!
          } else {
            // object factory -> original class
            containingClassSymbol
          }
            as FirClassSymbol<*>

        // Copy type parameters from the target class
        val classTypeArgsToReplace = mutableMapOf<FirTypeParameterSymbol, ConeKotlinType>()
        for (typeParameter in ownerToCopyTypeParametersFrom.typeParameterSymbols) {
          typeParameters +=
            buildTypeParameterCopy(typeParameter.fir) {
                origin = Keys.Default.origin
                this.symbol = FirTypeParameterSymbol()
                containingDeclarationSymbol = thisFunctionSymbol
              }
              .also { classTypeArgsToReplace[typeParameter] = it.symbol.constructType() }
        }

        // Build substitution map for parameters from ancestor classes
        val fullSubstitutionMap =
          buildFullSubstitutionMap(ownerToCopyTypeParametersFrom, classTypeArgsToReplace, session)

        instanceReceiver?.let {
          this.valueParameters +=
            buildSimpleValueParameter(
              name = Symbols.Names.instance,
              type = it.toFirResolvedTypeRef(),
              containingFunctionSymbol = thisFunctionSymbol,
              origin = Keys.InstanceParameter.origin,
            )
        }
        extensionReceiver?.let {
          this.valueParameters +=
            buildSimpleValueParameter(
              name = Symbols.Names.receiver,
              type =
                it
                  .wrapInProviderIfNecessary(session, Symbols.ClassIds.metroProvider)
                  .toFirResolvedTypeRef(),
              containingFunctionSymbol = thisFunctionSymbol,
              origin = Keys.ReceiverParameter.origin,
            )
        }

        copyParameters(
          functionBuilder = this,
          sourceParameters =
            valueParameters.filterNot {
              it.symbol.isAnnotatedWithAny(session, session.classIds.assistedAnnotations)
            },
          // Will be copied in IR
          copyParameterDefaults = false,
        ) { original ->
          val type = original.factoryProviderType(session)
          val substitutor = substitutorByMap(fullSubstitutionMap, session)
          val copiedType = substitutor.substituteOrNull(type) ?: type
          this.returnTypeRef = copiedType.toFirResolvedTypeRef()
        }
      }
      .also { func ->
        val extraAnnotations = buildList {
          buildHiddenFromObjCAnnotation(session)?.let(::add)
          addAll(buildStaticAnnotations(session))
        }
        if (extraAnnotations.isNotEmpty()) {
          func.replaceAnnotationsSafe(func.annotations + extraAnnotations)
        }
      }
      .symbol as FirNamedFunctionSymbol
  }

internal fun MetroFirValueParameter.factoryProviderType(
  session: FirSession,
  defaultUsesSuspendProvider: Boolean = false,
): ConeKotlinType {
  val wrappedType = contextKey.wrappedType
  if (!contextKey.hasDefault && wrappedType.keepsDeclaredFactoryProviderType()) {
    return contextKey.originalType(session)
  }
  val providerClassId =
    if (wrappedType.usesSuspendProvider(defaultUsesSuspendProvider)) {
      Symbols.ClassIds.metroSuspendProvider
    } else {
      Symbols.ClassIds.metroProvider
    }
  return contextKey.typeKey.type.wrapInProviderIfNecessary(session, providerClassId)
}

/**
 * Returns the name of a graph's generated creator. A graph without a factory gets `invoke()`. A
 * creator that implements the factory's [sam] uses its name. Other factories get `factory()`.
 */
internal fun graphCreatorName(factory: FirClassSymbol<*>?, sam: FirNamedFunctionSymbol?): Name =
  when {
    factory == null -> Symbols.Names.invoke
    sam != null -> sam.name
    else -> Symbols.Names.factory
  }

internal fun FirClassSymbol<*>.findSamFunction(session: FirSession): FirNamedFunctionSymbol? {
  return collectAbstractFunctions(session, exitOnAbstractProperties = true)?.singleOrNull()
}

internal fun FirClassSymbol<*>.collectAbstractFunctions(
  session: FirSession,
  exitOnAbstractProperties: Boolean = false,
): Collection<FirNamedFunctionSymbol>? {
  val scope = factoryScope(session)
  if (exitOnAbstractProperties) {
    if (scope.collectAllProperties().any { it.isAbstractBeforeStatus() }) return null
  }
  return scope.collectAllFunctions().filter { it.isAbstractBeforeStatus() }
}

/**
 * Returns whether this member is abstract, even before FIR resolves its status. A companion-block
 * function with a creator's name makes FIR request the creator during status resolution. Until
 * then, an interface member without a body is abstract unless it declares another modality.
 */
@OptIn(SymbolInternals::class)
private fun FirCallableSymbol<*>.isAbstractBeforeStatus(): Boolean {
  val status = rawStatus
  if (status is FirResolvedDeclarationStatus) {
    return status.modality == Modality.ABSTRACT
  }
  val declaredModality = status.modality
  if (declaredModality != null) {
    return declaredModality == Modality.ABSTRACT
  }
  val owner = getContainingClassSymbol() as? FirClassSymbol<*>
  val isInterfaceMember = owner?.classKind == ClassKind.INTERFACE
  val hasBody =
    when (val declaration = fir) {
      is FirFunction -> declaration.body != null
      is FirProperty -> declaration.initializer != null || declaration.getter?.body != null
      else -> true
    }
  return isInterfaceMember && !hasBody
}

/**
 * Returns the parameter indexes whose defaults a static creator copies. Only defaults declared in
 * the factory's own file count. An incremental build can read other files from their previous
 * output, which has no default expressions to copy.
 */
@OptIn(ScopeFunctionRequiresPrewarm::class)
internal fun FirClassSymbol<*>.sourceFactoryDefaults(
  sam: FirNamedFunctionSymbol,
  session: FirSession,
): Set<Int> = buildSet {
  val factoryFile = sourceFileOrNull() ?: return@buildSet
  val scope = factoryScope(session)
  // Populate this scope's override links before following the SAM to its source declaration.
  scope.processFunctionsByName(sam.name) {}
  scope.processOverriddenFunctionsAndSelf(sam) { symbol ->
    val declaration = symbol.unwrapSubstitutionOverrides()
    val declarationFile = declaration.getContainingClassSymbol()?.sourceFileOrNull()
    if (declarationFile == factoryFile) {
      declaration.valueParameterSymbols.forEachIndexed { index, parameter ->
        if (parameter.hasDefaultValue) {
          add(index)
        }
      }
    }
    ProcessorAction.NEXT
  }
}

/** Returns the file that declares this class when it's compiled from source. */
private fun FirClassLikeSymbol<*>.sourceFileOrNull(): FirFile? {
  if (origin !is FirDeclarationOrigin.Source) {
    return null
  }
  return moduleData.session.firProvider.getFirClassifierContainerFileIfAny(this)
}

private fun FirClassSymbol<*>.factoryScope(session: FirSession) =
  unsubstitutedScope(
    session,
    ScopeSession(),
    withForcedTypeCalculator = false,
    memberRequiredPhase = null,
  )
