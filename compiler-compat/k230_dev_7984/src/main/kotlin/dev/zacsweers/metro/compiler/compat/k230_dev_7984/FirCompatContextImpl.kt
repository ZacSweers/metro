package dev.zacsweers.metro.compiler.compat.k230_dev_7984

import dev.zacsweers.metro.compiler.compat.FirCompatContext
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol

// Ships in 2025.3-EAP
public class FirCompatContextImpl : FirCompatContext {
  override fun FirBasedSymbol<*>.getContainingClassSymbol(): FirClassLikeSymbol<*>? {
    return moduleData.session.firProvider.getContainingClass(this)
  }

  override fun FirCallableSymbol<*>.getContainingSymbol(session: FirSession): FirBasedSymbol<*>? {
    return getContainingClassSymbol()
      ?: session.firProvider.getFirCallableContainerFile(this)?.symbol
  }

  override fun FirDeclaration.getContainingClassSymbol(): FirClassLikeSymbol<*>? = symbol.getContainingClassSymbol()

  public class Factory : FirCompatContext.Factory {
    override val minVersion: String = "2.3.0-dev-7984"

    override fun create(): FirCompatContext = FirCompatContextImpl()
  }
}
