// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.computeOutrankedBindings
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.rankValue
import dev.zacsweers.metro.compiler.fir.resolveClassId
import dev.zacsweers.metro.compiler.fir.resolvedBindingArgument
import dev.zacsweers.metro.compiler.fir.scopeArgument
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.Fir2IrComponents
import org.jetbrains.kotlin.fir.backend.toIrType
import org.jetbrains.kotlin.fir.declarations.getBooleanArgument
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId

/** @see [repeatableAnnotationsIn] for docs on why this necessary. */
internal class IrRankedBindingProcessing(private val boundTypeResolver: IrBoundTypeResolver) {

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
    bindingContainers: Map<ClassId, IrClass>,
  ): Set<ClassId> {
    // Get the parent classes of each MetroContribution hint.
    // Use parentAsClass to navigate the IR tree directly, which preserves the Fir2IrLazyClass
    // type for external classes (needed for the FIR annotation path).
    // Also include binding containers which may have @Origin pointing to contributing classes.
    val contributionParents = contributions.values.flatten().map { it.rawType().parentAsClass }

    // For binding containers, resolve @Origin to find the contributing class
    val containerOrigins =
      bindingContainers.values.mapNotNull { container ->
        val originClassId = container.originClassId() ?: return@mapNotNull null
        context.referenceClass(originClassId)?.owner
      }

    val irContributions = (contributionParents + containerOrigins).distinctBy { it.classIdOrFail }

    val rankedBindings = irContributions.flatMap { contributingType ->
      contributingType.repeatableAnnotationsIn(
        context.metroSymbols.classIds.contributesBindingAnnotationsWithContainers,
        irBody = { irAnnotations ->
          irAnnotations.mapNotNull { annotation ->
            processIrAnnotation(annotation, contributingType, allScopes, boundTypeResolver)
          }
        },
        firBody = firBody@{ session, firAnnotations ->
            // Fir2IrLazyClass and friends implement Fir2IrComponents, which we need to resolve
            // binding types to IrTypes
            val components = contributingType as? Fir2IrComponents ?: return@firBody emptySequence()
            firAnnotations.mapNotNull { annotation ->
              processFirAnnotation(
                session,
                components,
                annotation,
                contributingType,
                allScopes,
                boundTypeResolver,
              )
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
    boundTypeResolver: IrBoundTypeResolver,
  ): ContributedBinding<IrClass, IrTypeKey>? {
    val scope = annotation.scopeOrNull() ?: return null
    if (scope !in allScopes) return null

    val result = boundTypeResolver.resolveBoundType(contributingType, annotation) ?: return null

    return ContributedBinding(
      contributingType = contributingType,
      typeKey = result.typeKey,
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
    boundTypeResolver: IrBoundTypeResolver,
  ): ContributedBinding<IrClass, IrTypeKey>? {
    // Use the FIR-specific scope resolution approach that handles external annotations correctly
    val scope =
      annotation.scopeArgument(session)?.resolveClassId(MetroFirTypeResolver.forIrUse())
        ?: return null
    if (scope !in allScopes) return null

    val ignoreQualifier =
      annotation.getBooleanArgument(Symbols.Names.ignoreQualifier, session) ?: false

    val explicitBindingType =
      annotation.resolvedBindingArgument(session)?.coneTypeOrNull?.let {
        with(fir2IrComponents) {
          val irType = it.toIrType()
          val qualifier =
            if (!ignoreQualifier) {
              irType.qualifierAnnotation()
            } else {
              null
            } ?: contributingType.qualifierAnnotation()
          IrTypeKey(irType, qualifier)
        }
      }

    val boundType =
      boundTypeResolver.resolveBoundType(contributingType, explicitBindingType, ignoreQualifier)
        ?: return null

    return ContributedBinding(
      contributingType = contributingType,
      typeKey = boundType,
      rank = annotation.rankValue(session),
    )
  }

  data class ContributedBinding<ClassType, TypeKeyType>(
    val contributingType: ClassType,
    val typeKey: TypeKeyType,
    val rank: Long,
  )
}
