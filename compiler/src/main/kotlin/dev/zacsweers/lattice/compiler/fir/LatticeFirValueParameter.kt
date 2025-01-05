package dev.zacsweers.lattice.compiler.fir

import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol

internal interface LatticeFirValueParameter {
  val symbol: FirValueParameterSymbol
  val contextKey: FirContextualTypeKey

  companion object {
    operator fun invoke(
      session: FirSession,
      symbol: FirValueParameterSymbol,
    ): LatticeFirValueParameter =
      object : LatticeFirValueParameter {
        override val symbol = symbol

        /**
         * Must be lazy because we may create this sooner than the [FirResolvePhase.TYPES] resolve
         * phase.
         */
        override val contextKey by unsafeLazy { FirContextualTypeKey.from(session, symbol) }
      }
  }
}
