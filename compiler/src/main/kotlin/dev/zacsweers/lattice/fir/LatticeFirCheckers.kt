package dev.zacsweers.lattice.fir

import dev.zacsweers.lattice.LatticeClassIds
import dev.zacsweers.lattice.fir.checkers.AssistedInjectChecker
import dev.zacsweers.lattice.fir.checkers.ComponentCreatorChecker
import dev.zacsweers.lattice.fir.checkers.InjectConstructorChecker
import dev.zacsweers.lattice.fir.checkers.ProvidesChecker
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

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
        get() =
          setOf(
            InjectConstructorChecker(session, latticeClassIds),
            AssistedInjectChecker(session, latticeClassIds),
            ComponentCreatorChecker(session, latticeClassIds),
          )

      override val callableDeclarationCheckers: Set<FirCallableDeclarationChecker>
        get() = setOf(ProvidesChecker(session, latticeClassIds))
    }
}
