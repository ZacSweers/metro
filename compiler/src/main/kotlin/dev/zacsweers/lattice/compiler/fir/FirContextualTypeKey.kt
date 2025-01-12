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
package dev.zacsweers.lattice.compiler.fir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.expectAs
import dev.zacsweers.lattice.compiler.expectAsOrNull
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.classId

@Poko
internal class FirContextualTypeKey(
  val typeKey: FirTypeKey,
  val isWrappedInProvider: Boolean = false,
  val isWrappedInLazy: Boolean = false,
  val isLazyWrappedInProvider: Boolean = false,
  val hasDefault: Boolean = false,
  val isDeferrable: Boolean = isWrappedInProvider || isWrappedInLazy || isLazyWrappedInProvider,
) {

  val originalType: ConeKotlinType
    get() =
      when {
        isLazyWrappedInProvider -> typeKey.type.wrapInLazy().wrapInProvider()
        isWrappedInProvider -> typeKey.type.wrapInProvider()
        isWrappedInLazy -> typeKey.type.wrapInLazy()
        else -> typeKey.type
      }

  override fun toString(): String = render(short = true)

  fun render(short: Boolean, includeQualifier: Boolean = true): String = buildString {
    val wrapperType =
      when {
        isWrappedInProvider -> "Provider"
        isWrappedInLazy -> "Lazy"
        isLazyWrappedInProvider -> "Provider<Lazy<"
        else -> null
      }
    if (wrapperType != null) {
      append(wrapperType)
      append("<")
    }
    append(typeKey.render(short, includeQualifier))
    if (wrapperType != null) {
      append(">")
      if (isLazyWrappedInProvider) {
        // One more bracket
        append(">")
      }
    }
    if (hasDefault) {
      append(" = ...")
    }
  }

  // TODO cache these?
  companion object {
    fun from(
      session: FirSession,
      callable: FirCallableSymbol<*>,
      type: ConeKotlinType = callable.resolvedReturnTypeRef.coneType,
    ): FirContextualTypeKey {
      return type.asFirContextualTypeKey(
        session = session,
        qualifierAnnotation =
          callable.findAnnotation(session, FirBasedSymbol<*>::qualifierAnnotation),
        hasDefault = callable is FirValueParameterSymbol && callable.hasDefaultValue,
      )
    }
  }
}

internal fun ConeKotlinType.asFirContextualTypeKey(
  session: FirSession,
  qualifierAnnotation: LatticeFirAnnotation?,
  hasDefault: Boolean,
): FirContextualTypeKey {
  val declaredType = this
  val rawClassId = declaredType.classId

  val isWrappedInProvider = rawClassId in session.latticeClassIds.providerTypes
  val isWrappedInLazy = rawClassId in session.latticeClassIds.lazyTypes
  val isLazyWrappedInProvider =
    isWrappedInProvider &&
      declaredType.typeArguments[0].expectAsOrNull<ConeKotlinTypeProjection>()?.type?.classId in
      session.latticeClassIds.lazyTypes

  val type =
    when {
      isLazyWrappedInProvider ->
        declaredType.typeArguments[0]
          .expectAs<ConeKotlinTypeProjection>()
          .type
          .typeArguments
          .single()
          .expectAs<ConeKotlinTypeProjection>()
          .type

      isWrappedInProvider || isWrappedInLazy ->
        declaredType.typeArguments[0].expectAs<ConeKotlinTypeProjection>().type

      else -> declaredType
    }

  val isDeferrable =
    isLazyWrappedInProvider ||
      isWrappedInProvider ||
      isWrappedInLazy ||
      run {
        // Check if this is a Map<Key, Provider<Value>>
        // If it has no type args we can skip
        if (declaredType.typeArguments.size != 2) return@run false
        // TODO check implements instead?
        val isMap = rawClassId == LatticeSymbols.ClassIds.map
        if (!isMap) return@run false
        val valueTypeContextKey =
          declaredType.typeArguments[1]
            .expectAs<ConeKotlinTypeProjection>()
            .type
            .asFirContextualTypeKey(
              session,
              // TODO could we actually support these?
              qualifierAnnotation = null,
              hasDefault = false,
            )

        valueTypeContextKey.isDeferrable
      }

  val typeKey = FirTypeKey(type, qualifierAnnotation)
  return FirContextualTypeKey(
    typeKey = typeKey,
    isWrappedInProvider = isWrappedInProvider,
    isWrappedInLazy = isWrappedInLazy,
    isLazyWrappedInProvider = isLazyWrappedInProvider,
    hasDefault = hasDefault,
    isDeferrable = isDeferrable,
  )
}
