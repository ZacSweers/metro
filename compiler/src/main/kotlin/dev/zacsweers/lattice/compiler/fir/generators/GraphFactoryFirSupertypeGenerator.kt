package dev.zacsweers.lattice.compiler.fir.generators

import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.extensions.ExperimentalSupertypesGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.getContainingDeclaration
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef

/**
 * Generates factory supertypes onto companion objects of `@DependencyGraph` types IFF the graph has
 * a factory creator that is an interface.
 *
 * Given this example:
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *   @DependencyGraph.Factory
 *   fun interface Factory {
 *     operator fun invoke(@BindsInstance int: Int, analyticsGraph: AnalyticsGraph): AppGraph
 *   }
 * }
 * ```
 *
 * This will generate the following:
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *
 *   @DependencyGraph.Factory
 *   fun interface Factory {
 *     // ...
 *   }
 *
 *   // ----------------vv
 *   companion object : AppGraph.Factory {
 *     // ...
 *   }
 * }
 * ```
 */
internal class GraphFactoryFirSupertypeGenerator(session: FirSession) :
  FirSupertypeGenerationExtension(session) {
  private val dependencyGraphAnnotationPredicate by unsafeLazy {
    LookupPredicate.BuilderContext.annotated(
      (session.latticeClassIds.dependencyGraphAnnotations +
        session.latticeClassIds.dependencyGraphFactoryAnnotations)
        .map { it.asSingleFqName() }
    )
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(dependencyGraphAnnotationPredicate)
  }

  override fun needTransformSupertypes(declaration: FirClassLikeDeclaration): Boolean {
    if (!declaration.symbol.isCompanion) return false
    val graphClass = declaration.getContainingDeclaration(session) ?: return false
    if (graphClass !is FirClass) return false
    val isGraph =
      graphClass.isAnnotatedWithAny(session, session.latticeClassIds.dependencyGraphAnnotations)
    if (!isGraph) return false
    val graphCreator =
      graphClass.declarations.filterIsInstance<FirClass>().firstOrNull {
        it.isAnnotatedWithAny(session, session.latticeClassIds.dependencyGraphFactoryAnnotations)
      }

    // TODO generics?
    if (graphCreator == null) return false
    if (!graphCreator.isInterface) return false

    // It's an interface so we can safely implement it
    return true
  }

  override fun computeAdditionalSupertypes(
    classLikeDeclaration: FirClassLikeDeclaration,
    resolvedSupertypes: List<FirResolvedTypeRef>,
    typeResolver: TypeResolveService,
  ): List<ConeKotlinType> {
    val graphClass = classLikeDeclaration.getContainingDeclaration(session) ?: return emptyList()
    if (graphClass !is FirClass) return emptyList()

    val graphCreator =
      graphClass.declarations.filterIsInstance<FirClass>().firstOrNull {
        it.isAnnotatedWithAny(session, session.latticeClassIds.dependencyGraphFactoryAnnotations)
      } ?: return emptyList()

    // TODO generics?
    val graphCreatorType = graphCreator.defaultType()
    return listOf(graphCreatorType)
  }

  @ExperimentalSupertypesGenerationApi
  override fun computeAdditionalSupertypesForGeneratedNestedClass(
    klass: FirRegularClass,
    typeResolver: TypeResolveService,
  ): List<FirResolvedTypeRef> {
    // TODO is this needed for when we generate a companion object? Think not since we generate it
    // ourselves directly
    println("computeAdditionalSupertypesForGeneratedNestedClass: $klass")
    return super.computeAdditionalSupertypesForGeneratedNestedClass(klass, typeResolver)
  }
}