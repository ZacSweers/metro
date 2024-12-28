/*
 * Copyright (C) 2024 Zac Sweers
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
package dev.zacsweers.lattice.fir

import dev.zacsweers.lattice.LatticeClassIds
import dev.zacsweers.lattice.unsafeLazy
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirReceiverParameter
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames

// TODO cache these?
internal class FirTypeKey(val type: FirTypeRef, val qualifier: LatticeFirAnnotation? = null) :
  Comparable<FirTypeKey> {
  private val cachedToString by unsafeLazy {
    buildString {
      qualifier?.let {
        append(it)
        append(" ")
      }
      append(type.coneType.renderReadableWithFqNames())
    }
  }

  override fun equals(other: Any?) = cachedToString.hashCode() == other.hashCode()

  override fun hashCode() = cachedToString.hashCode()

  override fun toString(): String = cachedToString

  override fun compareTo(other: FirTypeKey) = toString().compareTo(other.toString())

  fun simpleString(): String = buildString {
    qualifier?.let {
      append("@")
      append(it.simpleString())
      append(" ")
    }
    append(type.coneType.renderReadableWithFqNames())
  }

  companion object {
    fun from(
      session: FirSession,
      latticeClassIds: LatticeClassIds,
      property: FirProperty,
    ): FirTypeKey {
      return from(session, latticeClassIds, property.returnTypeRef, property.annotations)
    }

    fun from(
      session: FirSession,
      latticeClassIds: LatticeClassIds,
      parameter: FirValueParameter,
    ): FirTypeKey {
      return from(session, latticeClassIds, parameter.symbol)
    }

    fun from(
      session: FirSession,
      latticeClassIds: LatticeClassIds,
      parameter: FirValueParameterSymbol,
    ): FirTypeKey {
      val annotations =
        if (parameter.containingFunctionSymbol.receiverParameter == parameter) {
          parameter.containingFunctionSymbol.annotations.filter {
            it.useSiteTarget == AnnotationUseSiteTarget.RECEIVER
          }
        } else {
          parameter.annotations
        }
      return from(session, latticeClassIds, parameter.resolvedReturnTypeRef, annotations)
    }

    fun from(
      session: FirSession,
      latticeClassIds: LatticeClassIds,
      parameter: FirReceiverParameter,
      target: FirCallableDeclaration,
    ): FirTypeKey {
      val receiverAnnotations =
        parameter.annotations +
          target.annotations.filter { it.useSiteTarget == AnnotationUseSiteTarget.RECEIVER }
      return from(session, latticeClassIds, parameter.typeRef, receiverAnnotations)
    }

    fun from(
      session: FirSession,
      latticeClassIds: LatticeClassIds,
      function: FirSimpleFunction,
    ): FirTypeKey {
      return from(session, latticeClassIds, function.returnTypeRef, function.annotations)
    }

    fun from(
      session: FirSession,
      latticeClassIds: LatticeClassIds,
      typeRef: FirTypeRef,
      annotations: List<FirAnnotation>,
    ): FirTypeKey {
      // Check duplicate params
      val qualifier = annotations.qualifierAnnotation(session)
      return FirTypeKey(typeRef, qualifier)
    }
  }
}
