package dev.zacsweers.lattice.compiler.fir.generators

import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import dev.zacsweers.lattice.compiler.fir.hintClassId
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.latticeFirBuiltIns
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.buildUnaryArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildClassReferenceExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildGetClassCall
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate.BuilderContext.annotated
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.plugin.createTopLevelClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

internal class ContributionsFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  // Symbols for classes which have contributing annotations.
  private val contributingClasses by lazy {
    session.predicateBasedProvider
      .getSymbolsByPredicate(contributesAnnotationPredicate)
      .filterIsInstance<FirRegularClassSymbol>()
      .toSet()
  }

  private val contributesAnnotationPredicate by unsafeLazy {
    annotated(session.latticeClassIds.allContributesAnnotations.map { it.asSingleFqName() })
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(contributesAnnotationPredicate)
  }

  private val classIdsToContributions = mutableMapOf<ClassId, Contribution>()

  sealed interface Contribution {
    val origin: ClassId

    data class ContributesTo(override val origin: ClassId) : Contribution
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun getTopLevelClassIds(): Set<ClassId> {
    val contributesToAnnotations = session.latticeClassIds.contributesToAnnotations

    // TODO the others!
    val contributesBindingAnnotations = session.latticeClassIds.contributesBindingAnnotations
    val contributesIntoSetAnnotations = session.latticeClassIds.contributesIntoSetAnnotations
    val contributesIntoMapAnnotations = session.latticeClassIds.contributesIntoMapAnnotations
    val ids = mutableSetOf<ClassId>()

    for (contributingClass in contributingClasses) {
      for (annotation in contributingClass.resolvedAnnotationsWithClassIds) {
        val annotationClassId = annotation.toAnnotationClassIdSafe(session) ?: continue
        if (annotationClassId in contributesToAnnotations) {
          val newId = contributingClass.classId.hintClassId
          classIdsToContributions[newId] = Contribution.ContributesTo(contributingClass.classId)
          ids += newId
        }
      }
    }

    return ids
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
    val contribution = classIdsToContributions[classId] ?: return null
    return createTopLevelClass(
        classId,
        key = LatticeKeys.Default,
        classKind = ClassKind.INTERFACE,
      ) {
        if (contribution is Contribution.ContributesTo) {
          superType(contribution.origin.defaultType(emptyList()))
        }
      }
      .apply { replaceAnnotations(listOf(buildOriginAnnotation(contribution.origin))) }
      .symbol
  }

  private fun buildOriginAnnotation(origin: ClassId): FirAnnotation {
    return buildAnnotation {
      val originAnno = session.latticeFirBuiltIns.originClassSymbol

      annotationTypeRef = originAnno.defaultType().toFirResolvedTypeRef()

      argumentMapping = buildAnnotationArgumentMapping {
        mapping[Name.identifier("value")] = buildGetClassCall {
          val lookupTag = origin.toLookupTag()
          val referencedType = lookupTag.constructType()
          val resolvedType =
            StandardClassIds.KClass.constructClassLikeType(arrayOf(referencedType), false)
          argumentList =
            buildUnaryArgumentList(
              buildClassReferenceExpression {
                classTypeRef = buildResolvedTypeRef { coneType = referencedType }
                coneTypeOrNull = resolvedType
              }
            )
          coneTypeOrNull = resolvedType
        }
      }
    }
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext
  ): Set<Name> {
    // TODO contributed bindings go into here
    return super.getCallableNamesForClass(classSymbol, context)
  }
}
