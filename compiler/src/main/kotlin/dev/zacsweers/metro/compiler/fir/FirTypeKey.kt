// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.graph.BaseTypeKey
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirReceiverParameter
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType

@Poko
internal class FirTypeKey(override val type: ConeKotlinType, override val qualifier: MetroFirAnnotation? = null) :
  BaseTypeKey<ConeKotlinType, MetroFirAnnotation, FirTypeKey>() {

  override val classId by memoize { type.classId!! }

  override fun copy(
    type: ConeKotlinType,
    qualifier: MetroFirAnnotation?
  ): FirTypeKey {
    return FirTypeKey(type, qualifier)
  }

  override fun render(short: Boolean, includeQualifier: Boolean): String = buildString {
    if (includeQualifier) {
      qualifier?.let {
        append(it.simpleString())
        append(" ")
      }
    }
    renderType(short, type)
  }

  companion object {
    fun from(
      session: FirSession,
      property: FirProperty,
      substitutor: ConeSubstitutor = ConeSubstitutor.Empty,
    ): FirTypeKey {
      return from(session, property.returnTypeRef, property.annotations, substitutor)
    }

    fun from(
      session: FirSession,
      parameter: FirValueParameter,
      substitutor: ConeSubstitutor = ConeSubstitutor.Empty,
    ): FirTypeKey {
      return from(session, parameter.symbol, substitutor)
    }

    fun from(
      session: FirSession,
      parameter: FirValueParameterSymbol,
      substitutor: ConeSubstitutor = ConeSubstitutor.Empty,
    ): FirTypeKey {
      val annotations = parameter.resolvedCompilerAnnotationsWithClassIds
      return from(session, parameter.resolvedReturnTypeRef, annotations, substitutor)
    }

    fun from(
      session: FirSession,
      parameter: FirReceiverParameter,
      target: FirCallableDeclaration,
      substitutor: ConeSubstitutor = ConeSubstitutor.Empty,
    ): FirTypeKey {
      val receiverAnnotations =
        parameter.annotations +
          target.annotations.filter { it.useSiteTarget == AnnotationUseSiteTarget.RECEIVER }
      return from(session, parameter.typeRef, receiverAnnotations, substitutor)
    }

    fun from(
      session: FirSession,
      function: FirSimpleFunction,
      substitutor: ConeSubstitutor = ConeSubstitutor.Empty,
    ): FirTypeKey {
      return from(session, function.returnTypeRef, function.annotations, substitutor)
    }

    fun from(
      session: FirSession,
      typeRef: FirTypeRef,
      annotations: List<FirAnnotation>,
      substitutor: ConeSubstitutor = ConeSubstitutor.Empty,
    ): FirTypeKey {
      val qualifier = annotations.qualifierAnnotation(session)
      return FirTypeKey(substitutor.substituteOrSelf(typeRef.coneType), qualifier)
    }

    fun from(
      session: FirSession,
      coneType: ConeKotlinType,
      annotations: List<FirAnnotation>,
    ): FirTypeKey {
      val qualifier = annotations.qualifierAnnotation(session)
      return FirTypeKey(coneType, qualifier)
    }
  }
}
