package dev.zacsweers.metro.compiler.compat.k230_dev_9673

import dev.zacsweers.metro.compiler.compat.CompatContext
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceElementOffsetStrategy
import org.jetbrains.kotlin.fakeElement as fakeElementNative
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.util.addFakeOverrides as addFakeOverridesNative

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

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.3.0-dev-7984"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
