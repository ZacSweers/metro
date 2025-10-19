// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.compat.CompatContext
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirAnnotationResolvePhase
import org.jetbrains.kotlin.fir.expressions.FirArgumentList
import org.jetbrains.kotlin.fir.expressions.UnresolvedExpressionTypeAccess
import org.jetbrains.kotlin.fir.references.FirReference
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeProjection
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.visitors.FirTransformer
import org.jetbrains.kotlin.fir.visitors.FirVisitor

context(compatContext: CompatContext)
internal fun FirDeclaration.replaceAnnotationsSafe(newAnnotations: List<FirAnnotation>) {
  return replaceAnnotations(newAnnotations.copy(symbol))
}

context(compatContext: CompatContext)
private fun List<FirAnnotation>.copy(newParent: FirBasedSymbol<*>): List<FirAnnotation> {
  return map { it.copy(newParent) }
}

context(compatContext: CompatContext)
private fun FirAnnotation.copy(newParent: FirBasedSymbol<*>): FirAnnotation {
  if (this !is FirAnnotationCall) return this
  return NonAcceptingFirAnnotationCall(compatContext, this, newParent)
}

/**
 * An [FirAnnotationCall] that no-ops [acceptChildren] because `FirGeneratedElementsValidator`
 * validates incorrectly when we copy annotations.
 *
 * https://kotlinlang.slack.com/archives/C7L3JB43G/p1737173850965089
 */
private class NonAcceptingFirAnnotationCall(
  private val compatContext: CompatContext,
  private val delegate: FirAnnotationCall,
  override val containingDeclarationSymbol: FirBasedSymbol<*>,
) : FirAnnotationCall() {
  override val source: KtSourceElement?
    get() =
      with(compatContext) { delegate.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated) }

  @UnresolvedExpressionTypeAccess
  override val coneTypeOrNull: ConeKotlinType?
    get() = delegate.coneTypeOrNull

  override val annotations: List<FirAnnotation>
    get() = delegate.annotations

  override val useSiteTarget: AnnotationUseSiteTarget?
    get() = delegate.useSiteTarget

  override val annotationTypeRef: FirTypeRef
    get() = delegate.annotationTypeRef

  override val typeArguments: List<FirTypeProjection>
    get() = delegate.typeArguments

  override val argumentList: FirArgumentList
    get() = delegate.argumentList

  override val calleeReference: FirReference
    get() = delegate.calleeReference

  override val argumentMapping: FirAnnotationArgumentMapping
    get() = delegate.argumentMapping

  override val annotationResolvePhase: FirAnnotationResolvePhase
    get() = delegate.annotationResolvePhase

  override fun replaceConeTypeOrNull(newConeTypeOrNull: ConeKotlinType?) {
    delegate.replaceConeTypeOrNull(newConeTypeOrNull)
  }

  override fun replaceAnnotations(newAnnotations: List<FirAnnotation>) {
    delegate.replaceAnnotations(newAnnotations)
  }

  override fun replaceUseSiteTarget(newUseSiteTarget: AnnotationUseSiteTarget?) {
    delegate.replaceUseSiteTarget(newUseSiteTarget)
  }

  override fun replaceAnnotationTypeRef(newAnnotationTypeRef: FirTypeRef) {
    delegate.replaceAnnotationTypeRef(newAnnotationTypeRef)
  }

  override fun replaceTypeArguments(newTypeArguments: List<FirTypeProjection>) {
    delegate.replaceTypeArguments(newTypeArguments)
  }

  override fun replaceArgumentList(newArgumentList: FirArgumentList) {
    delegate.replaceArgumentList(newArgumentList)
  }

  override fun replaceCalleeReference(newCalleeReference: FirReference) {
    delegate.replaceCalleeReference(newCalleeReference)
  }

  override fun replaceArgumentMapping(newArgumentMapping: FirAnnotationArgumentMapping) {
    delegate.replaceArgumentMapping(newArgumentMapping)
  }

  override fun replaceAnnotationResolvePhase(newAnnotationResolvePhase: FirAnnotationResolvePhase) {
    delegate.replaceAnnotationResolvePhase(newAnnotationResolvePhase)
  }

  override fun <D> transformAnnotations(transformer: FirTransformer<D>, data: D) =
    delegate.transformAnnotations(transformer, data)

  override fun <D> transformAnnotationTypeRef(transformer: FirTransformer<D>, data: D) =
    delegate.transformAnnotationTypeRef(transformer, data)

  override fun <D> transformTypeArguments(transformer: FirTransformer<D>, data: D) =
    delegate.transformTypeArguments(transformer, data)

  override fun <D> transformCalleeReference(transformer: FirTransformer<D>, data: D) =
    delegate.transformCalleeReference(transformer, data)

  override fun <R, D> acceptChildren(visitor: FirVisitor<R, D>, data: D) {
    // See class doc for why this is unimplemented
  }

  override fun <D> transformChildren(transformer: FirTransformer<D>, data: D) =
    delegate.transformChildren(transformer, data)
}
