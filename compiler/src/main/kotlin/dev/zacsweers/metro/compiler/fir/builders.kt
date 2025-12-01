// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.builder.FirSimpleFunctionBuilder
import org.jetbrains.kotlin.fir.declarations.builder.FirValueParameterBuilder
import org.jetbrains.kotlin.fir.declarations.builder.buildSimpleFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameter
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameterCopy
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.buildResolvedArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildExpressionStub
import org.jetbrains.kotlin.fir.expressions.builder.buildFunctionCall
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.java.declarations.FirJavaField
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.plugin.DeclarationBuildingContext
import org.jetbrains.kotlin.fir.references.builder.buildResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.toEffectiveVisibility
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.ConstantValueKind

internal fun FirExtension.generateMemberFunction(
  owner: FirClassLikeSymbol<*>,
  returnTypeRef: FirTypeRef,
  callableId: CallableId,
  origin: FirDeclarationOrigin = Keys.Default.origin,
  visibility: Visibility = Visibilities.Public,
  modality: Modality = Modality.FINAL,
  body: FirSimpleFunctionBuilder.() -> Unit = {},
): FirSimpleFunction {
  contract { callsInPlace(body, InvocationKind.EXACTLY_ONCE) }
  return generateMemberFunction(
    owner,
    { returnTypeRef.coneType },
    callableId,
    origin,
    visibility,
    modality,
    body,
  )
}

internal fun FirExtension.generateMemberFunction(
  owner: FirClassLikeSymbol<*>,
  returnTypeProvider: (List<FirTypeParameterRef>) -> ConeKotlinType,
  callableId: CallableId,
  origin: FirDeclarationOrigin = Keys.Default.origin,
  visibility: Visibility = Visibilities.Public,
  modality: Modality = Modality.FINAL,
  body: FirSimpleFunctionBuilder.() -> Unit = {},
): FirSimpleFunction {
  contract { callsInPlace(body, InvocationKind.EXACTLY_ONCE) }
  return buildSimpleFunction {
    resolvePhase = FirResolvePhase.BODY_RESOLVE
    moduleData = session.moduleData
    this.origin = origin

    source =
      with(session.compatContext) {
        owner.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated)
      }

    val functionSymbol = FirNamedFunctionSymbol(callableId)
    symbol = functionSymbol
    name = callableId.callableName

    // TODO is there a non-impl API for this?
    status =
      FirResolvedDeclarationStatusImpl(
        visibility,
        modality,
        Visibilities.Public.toEffectiveVisibility(owner, forClass = true),
      )

    dispatchReceiverType = owner.constructType()

    body()

    // Must go after body() because type parameters are added there
    this.returnTypeRef = returnTypeProvider(typeParameters).toFirResolvedTypeRef()
  }
}

@OptIn(SymbolInternals::class)
internal fun FirExtension.copyParameters(
  functionBuilder: FirSimpleFunctionBuilder,
  sourceParameters: List<MetroFirValueParameter>,
  // TODO it would be neat to transform default value expressions in FIR? Right now only
  //  simple ones are supported
  copyParameterDefaults: Boolean,
  parameterInit: FirValueParameterBuilder.(original: MetroFirValueParameter) -> Unit = {},
) {
  for (original in sourceParameters) {
    val newParam =
      when (val originalFir = original.symbol.fir) {
        // Java fields don't have parameters we can just copy,
        // so we build a real one here based on it
        is FirJavaField -> {
          buildValueParameter {
              this.moduleData = originalFir.moduleData
              name = original.name
              origin = Keys.RegularParameter.origin
              symbol = FirValueParameterSymbol()
              containingDeclarationSymbol = functionBuilder.symbol
              returnTypeRef = originalFir.returnTypeRef
              symbol = FirValueParameterSymbol()
              parameterInit(original)
              if (originalFir.symbol.hasInitializer) {
                if (originalFir.symbol.hasMetroDefault(session)) {
                  if (!copyParameterDefaults) {
                    defaultValue = buildSafeDefaultValueStub(session)
                  }
                } else {
                  defaultValue = null
                }
              }
            }
            .apply {
              context(session.compatContext) { replaceAnnotationsSafe(original.symbol.annotations) }
            }
        }
        else -> {
          buildValueParameterCopy(originalFir as FirValueParameter) {
              name = original.name
              origin = Keys.RegularParameter.origin
              symbol = FirValueParameterSymbol()
              containingDeclarationSymbol = functionBuilder.symbol
              parameterInit(original)
              if (originalFir.symbol.hasDefaultValue) {
                if (originalFir.symbol.hasMetroDefault(session)) {
                  if (!copyParameterDefaults) {
                    defaultValue = buildSafeDefaultValueStub(session)
                  }
                } else {
                  defaultValue = null
                }
              }
            }
            .apply {
              context(session.compatContext) { replaceAnnotationsSafe(original.symbol.annotations) }
            }
        }
      }
    functionBuilder.valueParameters += newParam
  }
}

// Workaround for https://youtrack.jetbrains.com/issue/KT-81808
internal fun buildSafeDefaultValueStub(
  session: FirSession,
  message: String = "Stub!",
): FirFunctionCall {
  return buildFunctionCall {
    this.coneTypeOrNull = session.builtinTypes.nothingType.coneType
    this.calleeReference = buildResolvedNamedReference {
      this.resolvedSymbol = session.metroFirBuiltIns.errorFunctionSymbol
      this.name = session.metroFirBuiltIns.errorFunctionSymbol.name
    }
    argumentList =
      buildResolvedArgumentList(
        buildArgumentList {
          this.arguments +=
            buildLiteralExpression(
              source = null,
              kind = ConstantValueKind.String,
              value = message,
              setType = true,
            )
        },
        LinkedHashMap(),
      )
  }
}

internal fun FirExtension.buildSimpleValueParameter(
  name: Name,
  type: FirTypeRef,
  containingFunctionSymbol: FirFunctionSymbol<*>,
  origin: FirDeclarationOrigin = Keys.RegularParameter.origin,
  hasDefaultValue: Boolean = false,
  isCrossinline: Boolean = false,
  isNoinline: Boolean = false,
  isVararg: Boolean = false,
  body: FirValueParameterBuilder.() -> Unit = {},
): FirValueParameter {
  return buildValueParameter {
    resolvePhase = FirResolvePhase.BODY_RESOLVE
    moduleData = session.moduleData
    this.origin = origin
    returnTypeRef = type
    this.name = name
    symbol = FirValueParameterSymbol()
    if (hasDefaultValue) {
      // TODO: check how it will actually work in fir2ir
      defaultValue = buildExpressionStub {
        coneTypeOrNull = session.builtinTypes.nothingType.coneType
      }
    }
    this.containingDeclarationSymbol = containingFunctionSymbol
    this.isCrossinline = isCrossinline
    this.isNoinline = isNoinline
    this.isVararg = isVararg
    body()
  }
}

internal fun DeclarationBuildingContext<*>.copyTypeParametersFrom(
  classSymbol: FirClassSymbol<*>,
  session: FirSession,
  // This is disabled by default because in all our type parameter lookups, we are generating
  // declarations where we don't actually need bounds to be present/visible to the user and they are
  // usually not resolved at the time we look. We can revisit in the future if there's a way added
  // to call resolvedBounds without it throwing (such as an "isResolved" or something first)
  includeBounds: Boolean = false,
) {
  for (parameter in classSymbol.typeParameterSymbols) {
    typeParameter(name = parameter.name, variance = parameter.variance) {
      if (includeBounds) {
        for (bound in parameter.resolvedBounds) {
          bound { typeParameters ->
            val arguments = typeParameters.map { it.toConeType() }
            val substitutor = substitutor(classSymbol, arguments, session)
            substitutor.substituteOrSelf(bound.coneType)
          }
        }
      }
    }
  }
}

internal fun substitutor(
  classSymbol: FirClassLikeSymbol<*>,
  builderArguments: List<ConeKotlinType>,
  session: FirSession,
): ConeSubstitutor {
  val typeParameters = classSymbol.typeParameterSymbols
  return substitutorByMap(typeParameters.zip(builderArguments).toMap(), session)
}
