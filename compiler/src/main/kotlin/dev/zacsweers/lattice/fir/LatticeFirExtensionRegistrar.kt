package dev.zacsweers.lattice.fir

import dev.zacsweers.lattice.LatticeClassIds
import dev.zacsweers.lattice.fir.checkers.InjectConstructorChecker
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

internal class LatticeFirExtensionRegistrar(private val latticeClassIds: LatticeClassIds) :
  FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +LatticeFirCheckers.getFactory(latticeClassIds)
  }
}

internal class LatticeFirCheckers(
  session: FirSession,
  private val latticeClassIds: LatticeClassIds,
) : FirAdditionalCheckersExtension(session) {
  companion object {
    fun getFactory(latticeClassIds: LatticeClassIds) = Factory { session ->
      LatticeFirCheckers(session, latticeClassIds)
    }
  }

  override val declarationCheckers: DeclarationCheckers =
    object : DeclarationCheckers() {
      override val classCheckers: Set<FirClassChecker>
        get() = setOf(InjectConstructorChecker(session, latticeClassIds))
    }
}

