package dev.zacsweers.metro.compiler.compat.k2220

import dev.zacsweers.metro.compiler.compat.FirCompatContext
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol as getContainingClassSymbolNative
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingSymbol as getContainingSymbolNative
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fakeElement as fakeElementNative
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrOverridableMember
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.util.addFakeOverrides as addFakeOverridesNative

public class FirCompatContextImpl : FirCompatContext {
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

  public class Factory : FirCompatContext.Factory {
    override val minVersion: String = "2.2.20"

    override fun create(): FirCompatContext = FirCompatContextImpl()
  }
}
