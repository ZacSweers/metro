// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.computeOutrankedBindings
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId

internal object IrRankedBindingProcessing {

  /**
   * Provides `ContributesBinding.rank` interop for Dagger-Anvil.
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
        contributingType
          .annotationsIn(context.metroSymbols.classIds.contributesBindingAnnotationsWithContainers)
          .mapNotNull { annotation -> processIrAnnotation(annotation, contributingType, allScopes) }
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

  data class ContributedBinding<ClassType, TypeKeyType>(
    val contributingType: ClassType,
    val typeKey: TypeKeyType,
    val rank: Long,
  )
}
