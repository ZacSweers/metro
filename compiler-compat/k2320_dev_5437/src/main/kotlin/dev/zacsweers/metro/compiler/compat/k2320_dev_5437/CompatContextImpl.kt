// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k2320_dev_5437

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k230_beta1.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.builder.FirNamedFunctionBuilder
import org.jetbrains.kotlin.fir.declarations.builder.buildNamedFunction
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.plugin.SimpleFunctionBuildingContext
import org.jetbrains.kotlin.fir.plugin.createMemberFunction as createMemberFunctionNative
import org.jetbrains.kotlin.fir.plugin.createTopLevelFunction as createTopLevelFunctionNative
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.toEffectiveVisibility
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.ir.builders.declarations.IrFieldBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

public class CompatContextImpl : CompatContext by DelegateType() {

  override fun FirFunction.isNamedFunction(): Boolean {
    return this is FirNamedFunction
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun FirExtension.createTopLevelFunction(
    key: GeneratedDeclarationKey,
    callableId: CallableId,
    returnType: ConeKotlinType,
    containingFileName: String?,
    config: SimpleFunctionBuildingContext.() -> Unit,
  ): FirFunction {
    return createTopLevelFunctionNative(key, callableId, returnType, containingFileName, config)
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun FirExtension.createTopLevelFunction(
    key: GeneratedDeclarationKey,
    callableId: CallableId,
    returnTypeProvider: (List<FirTypeParameter>) -> ConeKotlinType,
    containingFileName: String?,
    config: SimpleFunctionBuildingContext.() -> Unit,
  ): FirFunction {
    return createTopLevelFunctionNative(
      key,
      callableId,
      returnTypeProvider,
      containingFileName,
      config,
    )
  }

  override fun FirExtension.createMemberFunction(
    owner: FirClassSymbol<*>,
    key: GeneratedDeclarationKey,
    name: Name,
    returnType: ConeKotlinType,
    config: SimpleFunctionBuildingContext.() -> Unit,
  ): FirFunction {
    return createMemberFunctionNative(owner, key, name, returnType, config)
  }

  override fun FirExtension.createMemberFunction(
    owner: FirClassSymbol<*>,
    key: GeneratedDeclarationKey,
    name: Name,
    returnTypeProvider: (List<FirTypeParameter>) -> ConeKotlinType,
    config: SimpleFunctionBuildingContext.() -> Unit,
  ): FirFunction {
    return createMemberFunctionNative(owner, key, name, returnTypeProvider, config)
  }

  override fun FirDeclarationGenerationExtension.buildMemberFunction(
    owner: FirClassLikeSymbol<*>,
    returnTypeProvider: (List<FirTypeParameterRef>) -> ConeKotlinType,
    callableId: CallableId,
    origin: FirDeclarationOrigin,
    visibility: Visibility,
    modality: Modality,
    body: CompatContext.FunctionBuilderScope.() -> Unit,
  ): FirFunction {
    return buildNamedFunction {
      resolvePhase = FirResolvePhase.BODY_RESOLVE
      moduleData = session.moduleData
      this.origin = origin

      // New in 2.3.20
      this.isLocal = false

      // We don't assign a source here. Even using fakeElement() still sometimes results in
      // using mismatched offsets, regardless of the kind
      source = null

      val functionSymbol = FirNamedFunctionSymbol(callableId)
      symbol = functionSymbol
      name = callableId.callableName

      status =
        FirResolvedDeclarationStatusImpl(
          visibility,
          modality,
          Visibilities.Public.toEffectiveVisibility(owner, forClass = true),
        )

      dispatchReceiverType = owner.constructType()

      FunctionBuilderScopeImpl(this).body()

      // Must go after body() because type parameters are added there
      this.returnTypeRef = returnTypeProvider(typeParameters).toFirResolvedTypeRef()
    }
  }

  private class FunctionBuilderScopeImpl(private val builder: FirNamedFunctionBuilder) :
    CompatContext.FunctionBuilderScope {
    override val symbol: FirNamedFunctionSymbol
      get() = builder.symbol

    override val typeParameters: MutableList<FirTypeParameter>
      get() = builder.typeParameters

    override val valueParameters: MutableList<FirValueParameter>
      get() = builder.valueParameters
  }

  override fun IrProperty.addBackingFieldCompat(builder: IrFieldBuilder.() -> Unit): IrField {
    return addBackingField(builder)
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.3.20-dev-5437"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
