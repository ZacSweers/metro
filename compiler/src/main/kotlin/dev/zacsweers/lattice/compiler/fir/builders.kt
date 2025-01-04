package dev.zacsweers.lattice.compiler.fir

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fakeElement
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.builder.FirSimpleFunctionBuilder
import org.jetbrains.kotlin.fir.declarations.builder.FirValueParameterBuilder
import org.jetbrains.kotlin.fir.declarations.builder.buildSimpleFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameter
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameterCopy
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.declarations.origin
import org.jetbrains.kotlin.fir.expressions.builder.buildExpressionStub
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.toEffectiveVisibility
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

@OptIn(ExperimentalContracts::class)
internal fun FirExtension.generateMemberFunction(
  targetClass: FirClassLikeSymbol<*>,
  returnTypeRef: FirTypeRef,
  callableId: CallableId,
  origin: FirDeclarationOrigin = LatticeKeys.Default.origin,
  visibility: Visibility = Visibilities.Public,
  modality: Modality = Modality.FINAL,
  body: FirSimpleFunctionBuilder.() -> Unit = {},
): FirSimpleFunction {
  contract { callsInPlace(body, InvocationKind.EXACTLY_ONCE) }
  return buildSimpleFunction {
    resolvePhase = FirResolvePhase.BODY_RESOLVE
    moduleData = session.moduleData
    this.origin = origin

    source = targetClass.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated)

    val functionSymbol = FirNamedFunctionSymbol(callableId)
    symbol = functionSymbol
    name = callableId.callableName

    // TODO is there a non-impl API for this?
    status =
      FirResolvedDeclarationStatusImpl(
        visibility,
        modality,
        Visibilities.Public.toEffectiveVisibility(targetClass, forClass = true),
      )

    dispatchReceiverType = targetClass.constructType()

    // TODO type params?

    this.returnTypeRef = returnTypeRef

    body()
  }
}

@OptIn(SymbolInternals::class)
internal fun FirSimpleFunctionBuilder.copyParametersWithDefaults(
  parametersToCopy: List<FirValueParameterSymbol>,
  parameterInit: FirValueParameterBuilder.(original: FirValueParameterSymbol) -> Unit = {},
) {
  for (original in parametersToCopy) {
    valueParameters +=
      buildValueParameterCopy(original.fir) {
        origin = LatticeKeys.ValueParameter.origin
        symbol = FirValueParameterSymbol(original.name)
        containingFunctionSymbol = this@copyParametersWithDefaults.symbol
        parameterInit(original)
        // TODO default values are copied over in this case, is that enough or do they need
        //  references transformed? We should also check they're not referencing non-assisted
        //  params
      }
  }
}

internal fun FirExtension.buildSimpleValueParameter(
  name: Name,
  type: FirTypeRef,
  containingFunctionSymbol: FirFunctionSymbol<*>,
  origin: FirDeclarationOrigin = LatticeKeys.ValueParameter.origin,
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
    symbol = FirValueParameterSymbol(name)
    if (hasDefaultValue) {
      // TODO: check how it will actually work in fir2ir
      defaultValue = buildExpressionStub {
        coneTypeOrNull = session.builtinTypes.nothingType.coneType
      }
    }
    this.containingFunctionSymbol = containingFunctionSymbol
    this.isCrossinline = isCrossinline
    this.isNoinline = isNoinline
    this.isVararg = isVararg
    body()
  }
}
