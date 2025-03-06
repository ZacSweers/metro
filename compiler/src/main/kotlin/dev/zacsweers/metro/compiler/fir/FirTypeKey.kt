// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirReceiverParameter
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.renderer.ConeIdRendererForDiagnostics
import org.jetbrains.kotlin.fir.renderer.ConeIdShortRenderer
import org.jetbrains.kotlin.fir.renderer.ConeTypeRendererForReadability
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneType

// TODO cache these?
@Poko
internal class FirTypeKey(val type: ConeKotlinType, val qualifier: MetroFirAnnotation? = null) :
  Comparable<FirTypeKey> {
  private val cachedToString by unsafeLazy { render(short = false, includeQualifier = true) }

  override fun toString(): String = cachedToString

  override fun compareTo(other: FirTypeKey): Int {
    if (this == other) return 0
    return toString().compareTo(other.toString())
  }

  fun render(short: Boolean, includeQualifier: Boolean = true): String = buildString {
    if (includeQualifier) {
      qualifier?.let {
        append("@")
        append(it.simpleString())
        append(" ")
      }
    }
    renderType(short)
  }

  companion object {
    fun from(session: FirSession, property: FirProperty): FirTypeKey {
      return from(session, property.returnTypeRef, property.annotations)
    }

    fun from(session: FirSession, parameter: FirValueParameter): FirTypeKey {
      return from(session, parameter.symbol)
    }

    fun from(session: FirSession, parameter: FirValueParameterSymbol): FirTypeKey {
      val annotations =
        if (parameter.containingFunctionSymbol.receiverParameter == parameter) {
          parameter.containingFunctionSymbol.annotations.filter {
            it.useSiteTarget == AnnotationUseSiteTarget.RECEIVER
          }
        } else {
          parameter.annotations
        }
      return from(session, parameter.resolvedReturnTypeRef, annotations)
    }

    fun from(
      session: FirSession,
      parameter: FirReceiverParameter,
      target: FirCallableDeclaration,
    ): FirTypeKey {
      val receiverAnnotations =
        parameter.annotations +
          target.annotations.filter { it.useSiteTarget == AnnotationUseSiteTarget.RECEIVER }
      return from(session, parameter.typeRef, receiverAnnotations)
    }

    fun from(session: FirSession, function: FirSimpleFunction): FirTypeKey {
      return from(session, function.returnTypeRef, function.annotations)
    }

    fun from(
      session: FirSession,
      typeRef: FirTypeRef,
      annotations: List<FirAnnotation>,
    ): FirTypeKey {
      val qualifier = annotations.qualifierAnnotation(session)
      return FirTypeKey(typeRef.coneType, qualifier)
    }

    fun from(
      session: FirSession,
      coneType: ConeKotlinType,
      annotations: List<FirAnnotation>
    ): FirTypeKey {
      val qualifier = annotations.qualifierAnnotation(session)
      return FirTypeKey(coneType, qualifier)
    }
  }

  // Custom renderer that excludes annotations
  private fun StringBuilder.renderType(short: Boolean) {
    val renderer =
      object :
        ConeTypeRendererForReadability(
          this,
          null,
          { if (short) ConeIdShortRenderer() else ConeIdRendererForDiagnostics() },
        ) {
        override fun ConeKotlinType.renderAttributes() {
          // Do nothing, we don't want annotations
        }
      }
    renderer.render(type)
  }
}
