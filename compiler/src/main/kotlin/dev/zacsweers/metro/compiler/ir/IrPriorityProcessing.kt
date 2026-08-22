// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.isKiaIntoMultibinding
import dev.zacsweers.metro.compiler.fir.priority
import dev.zacsweers.metro.compiler.fir.resolvedBindingArgument
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.Fir2IrComponents
import org.jetbrains.kotlin.fir.backend.toIrType
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.ClassId

internal enum class PriorityKind {
  BINDING,
  MAP,
}

/**
 * Resolves priorities from original contribution annotations without removing their implementation.
 *
 * [repeatableAnnotationsIn] handles KT-83185 for repeated annotations on external JVM classes. Its
 * FIR and IR paths both resolve qualified bound types before matching the generated contribution.
 */
internal class IrPriorityProcessing(private val boundTypeResolver: IrBoundTypeResolver) {

  /** Returns null when the callable does not correspond to a prioritizable class contribution. */
  context(context: IrMetroContext)
  internal fun priorityFor(
    contributingType: IrClass,
    boundTypeKey: IrTypeKey,
    contributionScope: ClassId?,
    kind: PriorityKind,
    mapKey: IrAnnotation?,
  ): Int? {
    val isMapContribution = kind == PriorityKind.MAP
    if (isMapContribution && mapKey == null) return null

    val matchingContributions =
      contributingType
        .repeatableAnnotationsIn(
          context.metroSymbols.classIds.contributesBindingLikeAnnotationsWithContainers,
          irBody = { annotations ->
            annotations.mapNotNull { annotation ->
              processIrAnnotation(
                annotation = annotation,
                contributingType = contributingType,
                contributionScope = contributionScope,
                kind = kind,
              )
            }
          },
          firBody = firBody@{ session, annotations ->
              // Fir2IrLazyClass and friends expose the components needed to resolve FIR types.
              val components =
                contributingType as? Fir2IrComponents ?: return@firBody emptySequence()
              annotations.mapNotNull { annotation ->
                processFirAnnotation(
                  session = session,
                  fir2IrComponents = components,
                  annotation = annotation,
                  contributingType = contributingType,
                  contributionScope = contributionScope,
                  kind = kind,
                )
              }
            },
        )
        .toList()

    val exactMatches = matchingContributions.filter { contribution ->
      contribution.boundTypeKey == boundTypeKey &&
        (!isMapContribution || contribution.mapKey == mapKey)
    }
    if (exactMatches.isNotEmpty()) return exactMatches.maxOf { it.priority }

    // Compiled Kotlin metadata can erase binding<T>() type arguments. Repeated erased bindings are
    // unambiguous only when all applicable annotations have the same priority.
    val erasedContributions = matchingContributions.filter { contribution ->
      contribution.boundTypeKey == null &&
        (!isMapContribution || contribution.mapKey == null || contribution.mapKey == mapKey)
    }
    val priority = erasedContributions.firstOrNull()?.priority ?: return null
    return if (erasedContributions.all { it.priority == priority }) priority else null
  }

  context(context: IrMetroContext)
  private fun processIrAnnotation(
    annotation: IrConstructorCall,
    contributingType: IrClass,
    contributionScope: ClassId?,
    kind: PriorityKind,
  ): MatchingContribution? {
    val annotationClassId = annotation.annotationClass.classId ?: return null
    if (!matchesContributionKind(annotationClassId, kind, annotation.isKiaIntoMultibinding())) {
      return null
    }

    val scope = annotation.scopeOrNull() ?: return null
    if (contributionScope != null && scope != contributionScope) return null

    val boundType = boundTypeResolver.resolveBoundType(contributingType, annotation)
    val explicitBindingMissingMetadata =
      annotation.getAnnotationArgument(Symbols.Names.binding) is IrConstructorCall &&
        annotation.bindingTypeArgument() == null
    if (boundType == null && !explicitBindingMissingMetadata) return null

    val annotationMapKey =
      if (kind == PriorityKind.MAP) {
        val sourceMapKey =
          boundType?.explicitBindingType?.originalType?.mapKeyAnnotation()
            ?: contributingType.mapKeyAnnotation()
        effectiveMapKey(sourceMapKey, contributingType)
      } else {
        null
      }

    return MatchingContribution(boundType?.typeKey, annotationMapKey, annotation.priority())
  }

  context(context: IrMetroContext)
  private fun processFirAnnotation(
    session: FirSession,
    fir2IrComponents: Fir2IrComponents,
    annotation: FirAnnotation,
    contributingType: IrClass,
    contributionScope: ClassId?,
    kind: PriorityKind,
  ): MatchingContribution? {
    val annotationClassId = annotation.toAnnotationClassIdSafe(session) ?: return null
    if (
      !matchesContributionKind(annotationClassId, kind, annotation.isKiaIntoMultibinding(session))
    ) {
      return null
    }

    val scope =
      annotation.resolvedScopeClassId(session, MetroFirTypeResolver.forIrUse()) ?: return null
    if (contributionScope != null && scope != contributionScope) return null

    val ignoreQualifier =
      with(context) {
        annotation.getBooleanArgumentCompat(Symbols.Names.ignoreQualifier, session)
      } ?: false
    val bindingArgument = annotation.resolvedBindingArgument(session)
    val explicitBindingType =
      bindingArgument?.coneTypeOrNull?.let { boundType ->
        with(fir2IrComponents) {
          val irType = boundType.toIrType()
          val qualifier =
            if (ignoreQualifier) {
              null
            } else {
              irType.qualifierAnnotation() ?: contributingType.qualifierAnnotation()
            }
          IrTypeKey(irType, qualifier)
        }
      }
    val explicitBindingMissingMetadata =
      bindingArgument == null &&
        annotation.argumentAsOrNull<FirAnnotation>(
          session,
          Symbols.Names.binding,
          index = 1,
        ) != null
    val boundType =
      if (explicitBindingMissingMetadata) {
        null
      } else {
        boundTypeResolver.resolveBoundType(contributingType, explicitBindingType, ignoreQualifier)
          ?: return null
      }
    val annotationMapKey =
      if (kind == PriorityKind.MAP) {
        val sourceMapKey =
          explicitBindingType?.originalType?.mapKeyAnnotation()
            ?: contributingType.mapKeyAnnotation()
        effectiveMapKey(sourceMapKey, contributingType)
      } else {
        null
      }
    val priority =
      annotation.priority(
        session,
        enableAnvilInterop = context.options.enableDaggerAnvilInterop,
      )

    return MatchingContribution(boundType, annotationMapKey, priority)
  }

  context(context: IrMetroContext)
  private fun matchesContributionKind(
    annotationClassId: ClassId,
    kind: PriorityKind,
    isKiaIntoMultibinding: Boolean,
  ): Boolean {
    val classIds = context.metroSymbols.classIds
    return when (kind) {
      PriorityKind.BINDING ->
        annotationClassId in classIds.contributesBindingAnnotations && !isKiaIntoMultibinding
      PriorityKind.MAP ->
        annotationClassId in classIds.contributesIntoMapAnnotations ||
          annotationClassId in classIds.customContributesIntoSetAnnotations
    }
  }

  context(context: IrMetroContext)
  private fun effectiveMapKey(mapKey: IrAnnotation?, contributingType: IrClass): IrAnnotation? {
    val sourceMapKey = mapKey ?: return null
    if (!isImplicitClassKeySentinel(sourceMapKey.ir)) return sourceMapKey

    val populatedMapKey = sourceMapKey.ir.deepCopyWithSymbols()
    populateImplicitClassKey(populatedMapKey, contributingType.defaultType)
    return IrAnnotation(populatedMapKey)
  }

  private data class MatchingContribution(
    val boundTypeKey: IrTypeKey?,
    val mapKey: IrAnnotation?,
    val priority: Int,
  )
}
