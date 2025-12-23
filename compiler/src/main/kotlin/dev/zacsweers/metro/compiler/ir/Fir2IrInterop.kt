package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.rankValue
import dev.zacsweers.metro.compiler.fir.resolvedBindingArgument
import dev.zacsweers.metro.compiler.fir.scopeArgument
import dev.zacsweers.metro.compiler.singleOrError
import dev.zacsweers.metro.compiler.symbols.Symbols
import kotlin.collections.map
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.ResolveStateAccess
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirClassReferenceExpression
import org.jetbrains.kotlin.fir.lazy.Fir2IrLazyClass
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.utils.addToStdlib.UnsafeCastFunction
import org.jetbrains.kotlin.utils.addToStdlib.castAll

/**
 * IrClass#annotations does not expose generated .Container annotations which house repeated
 * annotations. So e.g. if you have one @ContributesBinding, it will show up in IrClass#annotations,
 * but if you have two @ContributesBinding, then neither will be included, nor will their associated
 * .Container annotation.
 *
 * TODO: This can all be deleted and go back to pure IR handling once
 *   https://youtrack.jetbrains.com/issue/KT-83185 is resolved.
 */
internal object Fir2IrInterop {

  /**
   * Provides `ContributesBinding.rank` interop for Dagger-Anvil. This closely matches our existing
   * rank processing logic from [ContributedInterfaceSupertypeGenerator] with modifications where
   * necessary.
   *
   * This works around KT-83185 to ensure we do not miss any bindings. It's trickier than the
   * work-around for `replaces` handling because we have to maintain all binding information for
   * each contribution and compare them to one-another at the end, rather than just collecting
   * ClassIds along the way. This also means that we need to continue utilizing the FIR APIs all the
   * way through, instead of briefly switching to them for the annotations and then switching back
   * to IR APIs.
   *
   * @return The bindings which have been outranked and should not be included in the merged graph.
   */
  context(context: IrMetroContext)
  internal fun processRankBasedReplacements(
    allScopes: Set<ClassId>,
    contributions: Map<ClassId, List<IrType>>,
  ): Set<ClassId>? {
    val useFir = !context.supportsExternalRepeatableAnnotations && context.platform.isJvm()
    if (!useFir) return null

    val pendingRankReplacements = mutableSetOf<ClassId>()

    @OptIn(UnsafeCastFunction::class)
    val firContributions =
      contributions.values
        .flatten()
        .map { it.rawType().parentAsClass }
        .distinctBy { it.classIdOrFail }
        .filter { it.isExternalParent && it is Fir2IrLazyClass }
        .castAll<Fir2IrLazyClass>()

    val rankedBindings =
      firContributions.flatMap { contributingType ->
        contributingType.fir.symbol
          .annotationsIn(
            contributingType.session,
            contributingType.session.classIds.contributesBindingAnnotationsWithContainers,
          )
          .mapNotNull { annotation ->
            val scope = annotation.fir2IrResolvedScopeClassId() ?: return@mapNotNull null
            if (scope !in allScopes) return@mapNotNull null

            val explicitBindingMissingMetadata =
              annotation.argumentAsOrNull<FirAnnotation>(Symbols.Names.binding, index = 1)

            if (explicitBindingMissingMetadata != null) {
              // This is a case where an explicit binding is specified but we receive the argument
              // as FirAnnotationImpl without the metadata containing the type arguments so we
              // short-circuit since we lack the info to compare it against other bindings.
              null
            } else {
              val boundType =
                annotation.resolvedBindingArgument(contributingType.session)?.coneType
                  ?: contributingType.fir.symbol.implicitBoundType()

              ContributedBinding(
                contributingType = contributingType.fir.symbol,
                typeKey =
                  FirTypeKey(
                    boundType,
                    contributingType.fir.symbol.qualifierAnnotation(contributingType.session),
                  ),
                rank = annotation.rankValue(),
              )
            }
          }
      }

    val bindingGroups =
      rankedBindings
        .groupBy { binding -> binding.typeKey }
        .filter { bindingGroup -> bindingGroup.value.size > 1 }

    for (bindingGroup in bindingGroups.values) {
      val topBindings =
        bindingGroup
          .groupBy { binding -> binding.rank }
          .toSortedMap()
          .let { it.getValue(it.lastKey()) }

      // These are the bindings that were outranked and should not be processed further
      bindingGroup.minus(topBindings).forEach {
        pendingRankReplacements += it.contributingType.classId
      }
    }

    return pendingRankReplacements
  }

  @OptIn(ResolveStateAccess::class, SymbolInternals::class)
  private fun FirClassLikeSymbol<*>.implicitBoundType(): ConeKotlinType {
    return if (fir.resolveState.resolvePhase == FirResolvePhase.RAW_FIR) {
        // When processing bindings in the same module or compilation, we need to handle supertypes
        // that have not been resolved yet
        (this as FirClassSymbol<*>).fir.superTypeRefs.map { superTypeRef -> superTypeRef.coneType }
      } else {
        (this as FirClassSymbol<*>).resolvedSuperTypes
      }
      .singleOrError {
        val superTypeFqNames = map { it.classId?.asSingleFqName() }.joinToString()
        "${classId.asSingleFqName()} has a ranked binding with no explicit bound type and $size supertypes ($superTypeFqNames). There must be exactly one supertype or an explicit bound type."
      }
  }

  private fun FirAnnotation.fir2IrResolvedScopeClassId() =
    (scopeArgument()?.argument as FirClassReferenceExpression).classTypeRef.coneTypeOrNull?.classId

  private data class ContributedBinding(
    val contributingType: FirClassLikeSymbol<*>,
    val typeKey: FirTypeKey,
    val rank: Long,
  )
}
