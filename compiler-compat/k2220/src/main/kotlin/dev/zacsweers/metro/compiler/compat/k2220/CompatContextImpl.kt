package dev.zacsweers.metro.compiler.compat.k2220

import dev.zacsweers.metro.compiler.compat.CompatContext
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol as getContainingClassSymbolNative
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingSymbol as getContainingSymbolNative
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.plugin.SimpleFunctionBuildingContext
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fakeElement as fakeElementNative
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.ir.util.addFakeOverrides as addFakeOverridesNative
import org.jetbrains.kotlin.fir.plugin.createTopLevelFunction as createTopLevelFunctionNative

public class CompatContextImpl : CompatContext {
  override fun FirBasedSymbol<*>.getContainingClassSymbol(): FirClassLikeSymbol<*>? =
    getContainingClassSymbolNative()

  override fun FirCallableSymbol<*>.getContainingSymbol(session: FirSession): FirBasedSymbol<*>? =
    getContainingSymbolNative(session)

  override fun FirDeclaration.getContainingClassSymbol(): FirClassLikeSymbol<*>? =
    getContainingClassSymbolNative()

  override fun KtSourceElement.fakeElement(
    newKind: KtFakeSourceElementKind,
    startOffset: Int,
    endOffset: Int
  ): KtSourceElement = fakeElementNative(newKind, startOffset, endOffset)

  override fun IrClass.addFakeOverrides(
    typeSystem: IrTypeSystemContext,
  ) {
    return addFakeOverridesNative(typeSystem)
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun FirExtension.createTopLevelFunction(
    key: GeneratedDeclarationKey,
    callableId: CallableId,
    returnType: ConeKotlinType,
    containingFileName: String?, // Ignored on 2.2.20
    config: SimpleFunctionBuildingContext.() -> Unit
  ): FirSimpleFunction {
    return createTopLevelFunctionNative(key, callableId, returnType, config)
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun FirExtension.createTopLevelFunction(
    key: GeneratedDeclarationKey,
    callableId: CallableId,
    returnTypeProvider: (List<FirTypeParameter>) -> ConeKotlinType,
    containingFileName: String?, // Ignored on 2.2.20
    config: SimpleFunctionBuildingContext.() -> Unit
  ): FirSimpleFunction {
    return createTopLevelFunctionNative(key, callableId, returnTypeProvider, config)
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.2.20"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
