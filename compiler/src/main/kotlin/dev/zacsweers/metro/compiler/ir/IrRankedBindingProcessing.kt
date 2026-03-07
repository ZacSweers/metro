// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.computeOutrankedBindings
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.rankValue
import dev.zacsweers.metro.compiler.fir.resolveClassId
import dev.zacsweers.metro.compiler.fir.resolvedBindingArgument
import dev.zacsweers.metro.compiler.fir.scopeArgument
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.Fir2IrComponents
import org.jetbrains.kotlin.fir.backend.toIrType
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId

/** @see [repeatableAnnotationsIn] for docs on why this necessary. */
internal object IrRankedBindingProcessing {

  /**
   * Provides `ContributesBinding.rank` interop for Dagger-Anvil.
   *
   * This uses [repeatableAnnotationsIn] to handle the KT-83185 workaround transparently. Both the
   * FIR and IR paths produce bindings with [IrTypeKey] for consistent comparison.
   *
   * @return The bindings which have been outranked and should not be included in the merged graph.
   */
  context(context: IrMetroContext)
  internal fun processRankBasedReplacements(
    allScopes: Set<ClassId>,
    contributions: Map<ClassId, List<IrType>>,
  ): Set<ClassId> {
    // Get the parent classes of each MetroContribution hint.
    // Use parentAsClass to navigate the IR tree directly, which preserves the Fir2IrLazyClass
    // type for external classes (needed for the FIR annotation path).
    val irContributions =
      contributions.values
        .flatten()
        .map { it.rawType().parentAsClass }
        .distinctBy { it.classIdOrFail }

    val rankedBindings =
      irContributions.flatMap { contributingType ->
        contributingType.repeatableAnnotationsIn(
          context.metroSymbols.classIds.contributesBindingAnnotationsWithContainers,
          irBody = { irAnnotations ->
            irAnnotations.mapNotNull { annotation ->
              processIrAnnotation(annotation, contributingType, allScopes)
            }
          },
          firBody = firBody@{ session, firAnnotations ->
              // Fir2IrLazyClass and friends implement Fir2IrComponents, which we need to resolve
              // binding types to IrTypes
              val components =
                contributingType as? Fir2IrComponents ?: return@firBody emptySequence()
              firAnnotations.mapNotNull { annotation ->
                processFirAnnotation(session, components, annotation, contributingType, allScopes)
              }
            },
        )
      }

    return computeOutrankedBindings(
      rankedBindings,
      typeKeySelector = { it.typeKey },
      rankSelector = { it.rank },
      classId = { it.contributingType.classIdOrFail },
    )
  }

  context(context: IrMetroContext)
  private fun processIrAnnotation(
    annotation: IrConstructorCall,
    contributingType: IrClass,
    allScopes: Set<ClassId>,
  ): ContributedBinding<IrClass, IrTypeKey>? {
    val scope = annotation.scopeOrNull() ?: return null
    if (scope !in allScopes) return null

    val (explicitBindingType, ignoreQualifier) =
      with(context.pluginContext) { annotation.bindingTypeOrNull() }

    val boundType = explicitBindingType ?: contributingType.implicitBoundTypeOrNull() ?: return null

    return ContributedBinding(
      contributingType = contributingType,
      typeKey =
        IrTypeKey(boundType, if (ignoreQualifier) null else contributingType.qualifierAnnotation()),
      rank = annotation.rankValue(),
    )
  }

  context(context: IrMetroContext)
  private fun processFirAnnotation(
    session: FirSession,
    fir2IrComponents: Fir2IrComponents,
    annotation: FirAnnotation,
    contributingType: IrClass,
    allScopes: Set<ClassId>,
  ): ContributedBinding<IrClass, IrTypeKey>? {
    // Use the FIR-specific scope resolution approach that handles external annotations correctly
    val scope =
      annotation.scopeArgument()?.resolveClassId(MetroFirTypeResolver.forIrUse()) ?: return null
    if (scope !in allScopes) return null

    val bindingConeType = annotation.resolvedBindingArgument(session)?.coneTypeOrNull

    val boundType =
      if (bindingConeType != null) {
        // Look up the IR type from the ClassId
        with(fir2IrComponents) { bindingConeType.toIrType() }
      } else {
        // Fall back to implicit bound type
        contributingType.implicitBoundTypeOrNull()
      } ?: return null

    return ContributedBinding(
      contributingType = contributingType,
      typeKey = IrTypeKey(boundType, contributingType.qualifierAnnotation()),
      rank = annotation.rankValue(),
    )
  }

  data class ContributedBinding<ClassType, TypeKeyType>(
    val contributingType: ClassType,
    val typeKey: TypeKeyType,
    val rank: Long,
  )
}
