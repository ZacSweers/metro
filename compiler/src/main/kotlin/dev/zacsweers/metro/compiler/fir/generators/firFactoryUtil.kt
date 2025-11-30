// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.MetroFirValueParameter
import dev.zacsweers.metro.compiler.fir.buildFullSubstitutionMap
import dev.zacsweers.metro.compiler.fir.buildSimpleValueParameter
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.copyParameters
import dev.zacsweers.metro.compiler.fir.generateMemberFunction
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.wrapInProviderIfNecessary
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.containingClassForStaticMemberAttr
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.builder.buildTypeParameterCopy
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.scopes.collectAllFunctions
import org.jetbrains.kotlin.fir.scopes.collectAllProperties
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.constructType
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
        buildFullSubstitutionMap(
          targetClass,
          targetClass.typeParameterSymbols.associateWith { it.toConeType() },
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
          val originalType = valueParameter.contextKey.typeKey.type
          val substitutedType = substitutor.substituteOrNull(originalType) ?: originalType

          valueParameter(
            valueParameter.name,
            substitutedType.wrapInProviderIfNecessary(session, Symbols.ClassIds.metroProvider),
            key = Keys.RegularParameter,
          )
        }
      }
      .also { it.containingClassForStaticMemberAttr = owner.toLookupTag() }
  }

internal fun FirDeclarationGenerationExtension.buildFactoryCreateFunction(
  context: MemberGenerationContext,
  returnType: ConeKotlinType,
  instanceReceiver: ConeClassLikeType?,
  extensionReceiver: ConeClassLikeType?,
  valueParameters: List<MetroFirValueParameter>,
): FirNamedFunctionSymbol {
  return buildFactoryCreateFunction(
    context,
    { returnType },
    instanceReceiver,
    extensionReceiver,
    valueParameters,
  )
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
          val type = original.contextKey.typeKey.type
          val substitutor = substitutorByMap(fullSubstitutionMap, session)
          val copiedType = substitutor.substituteOrNull(type) ?: type
          this.returnTypeRef =
            copiedType
              .wrapInProviderIfNecessary(session, Symbols.ClassIds.metroProvider)
              .toFirResolvedTypeRef()
        }
      }
      .symbol as FirNamedFunctionSymbol
  }

@OptIn(SymbolInternals::class)
internal fun FirDeclarationGenerationExtension.buildNewInstanceFunction(
  context: MemberGenerationContext,
  name: Name,
  returnType: ConeKotlinType,
  instanceReceiver: ConeClassLikeType?,
  extensionReceiver: ConeClassLikeType?,
  valueParameters: List<MetroFirValueParameter>,
): FirNamedFunctionSymbol =
  with(session.compatContext) {
    return generateMemberFunction(
        context.owner,
        returnType.toFirResolvedTypeRef(),
        CallableId(context.owner.classId, name),
        origin = Keys.FactoryNewInstanceFunction.origin,
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
              type = it.toFirResolvedTypeRef(),
              containingFunctionSymbol = thisFunctionSymbol,
              origin = Keys.ReceiverParameter.origin,
            )
        }

        copyParameters(
          functionBuilder = this,
          sourceParameters = valueParameters,
          // Will be copied in IR
          copyParameterDefaults = false,
        ) { original ->
          val type = original.contextKey.originalType(session)
          val substitutor = substitutorByMap(classTypeArgsToReplace, session)
          val copiedType = substitutor.substituteOrNull(type) ?: type
          this.returnTypeRef = copiedType.toFirResolvedTypeRef()
        }
      }
      .symbol as FirNamedFunctionSymbol
  }

internal fun FirClassSymbol<*>.findSamFunction(session: FirSession): FirFunctionSymbol<*>? {
  return collectAbstractFunctions(session, exitOnAbstractProperties = true)?.singleOrNull()
}

internal fun FirClassSymbol<*>.collectAbstractFunctions(
  session: FirSession,
  exitOnAbstractProperties: Boolean = false,
): Collection<FirNamedFunctionSymbol>? {
  val scope =
    unsubstitutedScope(
      session,
      ScopeSession(),
      withForcedTypeCalculator = false,
      memberRequiredPhase = null,
    )
  if (exitOnAbstractProperties) {
    if (scope.collectAllProperties().any { it.isAbstract }) return null
  }
  return scope.collectAllFunctions().filter { it.isAbstract }
}
