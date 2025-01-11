package dev.zacsweers.lattice.compiler.fir.generators

import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.fir.isDependencyGraph
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.name.ClassId

// Toe-hold for contributed types
internal class ContributedInterfaceSupertypeGenerator(session: FirSession) :
  FirSupertypeGenerationExtension(session) {

  private val predicate =
    LookupPredicate.create {
      annotated(session.latticeClassIds.allContributesAnnotations.map { it.asSingleFqName() })
    }

  // NOTE this is only in-compilation
  private val contributingClasses by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(predicate)
      .filterIsInstance<FirRegularClassSymbol>()
  }

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration) =
    declaration.symbol.isDependencyGraph(session)

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    val contributedTypes = mutableListOf<ConeKotlinType>()
    // TODO cache these by scopes?
    val generatedNames =
      session.symbolProvider.symbolNamesProvider
        .getTopLevelClassifierNamesInPackage(LatticeSymbols.FqNames.latticeHintsPackage)
        .orEmpty()
        .mapNotNull { name ->
          session.symbolProvider.getClassLikeSymbolByClassId(
            ClassId(LatticeSymbols.FqNames.latticeHintsPackage, name)
          )
        }

    // TODO identify contributed scope, source scope

    return contributedTypes
  }
}
