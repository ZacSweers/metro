/*
 * Copyright (C) 2025 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import org.jetbrains.kotlin.fir.expressions.buildResolvedArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildExpressionStub
import org.jetbrains.kotlin.fir.expressions.builder.buildFunctionCall
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.references.builder.buildResolvedNamedReference
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
import org.jetbrains.kotlin.types.ConstantValueKind

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
internal fun FirExtension.copyParameters(
  functionBuilder: FirSimpleFunctionBuilder,
  sourceParameters: List<LatticeFirValueParameter>,
  // TODO it would be neat to transform default value expressions in FIR? Right now only
  //  simple ones are supported
  copyParameterDefaults: Boolean,
  parameterInit: FirValueParameterBuilder.(original: LatticeFirValueParameter) -> Unit = {},
) {
  for (original in sourceParameters) {
    val originalFir = original.symbol.fir
    functionBuilder.valueParameters +=
      buildValueParameterCopy(originalFir) {
        name = original.name
        origin = LatticeKeys.ValueParameter.origin
        symbol = FirValueParameterSymbol(original.symbol.name)
        containingFunctionSymbol = functionBuilder.symbol
        parameterInit(original)
        if (!copyParameterDefaults) {
          if (original.symbol.hasDefaultValue) {
            defaultValue = buildFunctionCall {
              this.coneTypeOrNull = session.builtinTypes.nothingType.coneType
              this.calleeReference = buildResolvedNamedReference {
                this.resolvedSymbol = session.latticeFirBuiltIns.errorFunctionSymbol
                this.name = session.latticeFirBuiltIns.errorFunctionSymbol.name
              }
              argumentList =
                buildResolvedArgumentList(
                  buildArgumentList {
                    this.arguments +=
                      buildLiteralExpression(
                        source = null,
                        kind = ConstantValueKind.String,
                        value = "Replaced in IR",
                        setType = false,
                      )
                  },
                  LinkedHashMap(),
                )
            }
          }
        }
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
