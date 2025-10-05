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
  ): KtSourceElement {
    return fakeElementNative(newKind, startOffset, endOffset)
  }

  public class Factory : FirCompatContext.Factory {
    override val minVersion: String = "2.2.20"

    override fun create(): FirCompatContext = FirCompatContextImpl()
  }
}
