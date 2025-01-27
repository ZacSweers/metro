/*
 * Copyright (C) 2025 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.metro.compiler.fir

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fakeElement
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

internal fun FirDeclaration.replaceAnnotationsSafe(newAnnotations: List<FirAnnotation>) {
  return replaceAnnotations(newAnnotations.copy(symbol))
}

private fun List<FirAnnotation>.copy(newParent: FirBasedSymbol<*>): List<FirAnnotation> {
  return map { it.copy(newParent) }
}

private fun FirAnnotation.copy(newParent: FirBasedSymbol<*>): FirAnnotation {
  if (this !is FirAnnotationCall) return this
  return NonAcceptingFirAnnotationCall(this, newParent)
}

/**
 * An [FirAnnotationCall] that no-ops [acceptChildren] because `FirGeneratedElementsValidator`
 * validates incorrectly when we copy annotations.
 *
 * https://kotlinlang.slack.com/archives/C7L3JB43G/p1737173850965089
 */
private class NonAcceptingFirAnnotationCall(
  private val delegate: FirAnnotationCall,
  override val containingDeclarationSymbol: FirBasedSymbol<*>,
) : FirAnnotationCall() {
  override val source: KtSourceElement?
    get() = delegate.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated)

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
