// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k230_dev_7984

import dev.zacsweers.metro.compiler.compat.CompatContext
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceElementOffsetStrategy
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fakeElement as fakeElementNative
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.copy as copyDeclarationNative
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationStatus
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.plugin.SimpleFunctionBuildingContext
import org.jetbrains.kotlin.fir.plugin.createTopLevelFunction as createTopLevelFunctionNative
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.ir.builders.Scope
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.util.addFakeOverrides as addFakeOverridesNative
import org.jetbrains.kotlin.name.CallableId

// Ships in 2025.3-EAP
public class CompatContextImpl : CompatContext {
  override fun FirBasedSymbol<*>.getContainingClassSymbol(): FirClassLikeSymbol<*>? {
    return moduleData.session.firProvider.getContainingClass(this)
  }

  override fun FirCallableSymbol<*>.getContainingSymbol(session: FirSession): FirBasedSymbol<*>? {
    return getContainingClassSymbol()
      ?: session.firProvider.getFirCallableContainerFile(this)?.symbol
  }

  override fun FirDeclaration.getContainingClassSymbol(): FirClassLikeSymbol<*>? =
    symbol.getContainingClassSymbol()

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun FirExtension.createTopLevelFunction(
    key: GeneratedDeclarationKey,
    callableId: CallableId,
    returnType: ConeKotlinType,
    containingFileName: String?,
    config: SimpleFunctionBuildingContext.() -> Unit,
  ): FirSimpleFunction {
    return createTopLevelFunctionNative(key, callableId, returnType, containingFileName, config)
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun FirExtension.createTopLevelFunction(
    key: GeneratedDeclarationKey,
    callableId: CallableId,
    returnTypeProvider: (List<FirTypeParameter>) -> ConeKotlinType,
    containingFileName: String?,
    config: SimpleFunctionBuildingContext.() -> Unit,
  ): FirSimpleFunction {
    return createTopLevelFunctionNative(
      key,
      callableId,
      returnTypeProvider,
      containingFileName,
      config,
    )
  }

  override fun FirDeclarationStatus.copy(
    visibility: Visibility?,
    modality: Modality?,
    isExpect: Boolean,
    isActual: Boolean,
    isOverride: Boolean,
    isOperator: Boolean,
    isInfix: Boolean,
    isInline: Boolean,
    isValue: Boolean,
    isTailRec: Boolean,
    isExternal: Boolean,
    isConst: Boolean,
    isLateInit: Boolean,
    isInner: Boolean,
    isCompanion: Boolean,
    isData: Boolean,
    isSuspend: Boolean,
    isStatic: Boolean,
    isFromSealedClass: Boolean,
    isFromEnumClass: Boolean,
    isFun: Boolean,
    hasStableParameterNames: Boolean,
  ): FirDeclarationStatus =
    copyDeclarationNative(
      visibility = visibility,
      modality = modality,
      isExpect = isExpect,
      isActual = isActual,
      isOverride = isOverride,
      isOperator = isOperator,
      isInfix = isInfix,
      isInline = isInline,
      isValue = isValue,
      isTailRec = isTailRec,
      isExternal = isExternal,
      isConst = isConst,
      isLateInit = isLateInit,
      isInner = isInner,
      isCompanion = isCompanion,
      isData = isData,
      isSuspend = isSuspend,
      isStatic = isStatic,
      isFromSealedClass = isFromSealedClass,
      isFromEnumClass = isFromEnumClass,
      isFun = isFun,
      hasStableParameterNames = hasStableParameterNames,
    )

  override fun KtSourceElement.fakeElement(
    newKind: KtFakeSourceElementKind,
    startOffset: Int,
    endOffset: Int,
  ): KtSourceElement {
    return fakeElementNative(
      newKind,
      KtSourceElementOffsetStrategy.Custom.Initialized(startOffset, endOffset),
    )
  }

  override fun IrClass.addFakeOverrides(typeSystem: IrTypeSystemContext): Unit =
    addFakeOverridesNative(typeSystem)

  override fun Scope.createTemporaryVariableDeclarationCompat(
    irType: IrType,
    nameHint: String?,
    isMutable: Boolean,
    origin: IrDeclarationOrigin,
    startOffset: Int,
    endOffset: Int,
  ): IrVariable =
    createTemporaryVariableDeclaration(irType, nameHint, isMutable, origin, startOffset, endOffset)

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.3.0-dev-7984"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
