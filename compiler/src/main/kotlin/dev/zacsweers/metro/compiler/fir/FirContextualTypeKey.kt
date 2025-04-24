// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.WrappedType
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.letIf
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.StandardClassIds

/** A class that represents a type with contextual information. */
@Poko
internal class FirContextualTypeKey(
  val typeKey: FirTypeKey,
  val wrappedType: WrappedType<ConeKotlinType>,
  val hasDefault: Boolean = false,
  val isDeferrable: Boolean = wrappedType.isDeferrable(),
) {
  // For backward compatibility
  val isWrappedInProvider: Boolean
    get() = wrappedType is WrappedType.Provider

  val isWrappedInLazy: Boolean
    get() = wrappedType is WrappedType.Lazy

  val isLazyWrappedInProvider: Boolean
    get() = wrappedType is WrappedType.Provider && wrappedType.innerType is WrappedType.Lazy

  fun originalType(session: FirSession): ConeKotlinType {
    return when (val wt = wrappedType) {
      is WrappedType.Canonical -> wt.type
      is WrappedType.Provider -> {
        val innerType =
          FirContextualTypeKey(typeKey, wt.innerType, hasDefault, isDeferrable)
            .originalType(session)
        innerType.wrapInProviderIfNecessary(session, wt.providerType)
      }
      is WrappedType.Lazy -> {
        val innerType =
          FirContextualTypeKey(typeKey, wt.innerType, hasDefault, isDeferrable)
            .originalType(session)
        innerType.wrapInLazyIfNecessary(session, wt.lazyType)
      }
      is WrappedType.Map -> {
        wt.type()
      }
    }
  }

  override fun toString(): String = render(short = true)

  fun render(short: Boolean, includeQualifier: Boolean = true): String = buildString {
    append(
      wrappedType.render { type ->
        if (type == typeKey.type) {
          typeKey.render(short, includeQualifier)
        } else {
          buildString { renderType(short, type) }
        }
      }
    )
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
      wrapInProvider: Boolean = false,
    ): FirContextualTypeKey {
      return type
        .letIf(wrapInProvider) {
          it.wrapInProviderIfNecessary(session, Symbols.ClassIds.metroProvider)
        }
        .asFirContextualTypeKey(
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
  qualifierAnnotation: MetroFirAnnotation?,
  hasDefault: Boolean,
): FirContextualTypeKey {
  val declaredType = this

  // Analyze the type to determine its wrapped structure
  val wrappedType = declaredType.asWrappedType(session)

  val typeKey =
    FirTypeKey(
      when (wrappedType) {
        is WrappedType.Canonical -> wrappedType.type
        // For Map types, we keep the original type in the TypeKey
        is WrappedType.Map -> declaredType
        else -> wrappedType.canonicalType()
      },
      qualifierAnnotation,
    )

  return FirContextualTypeKey(
    typeKey = typeKey,
    wrappedType = wrappedType,
    hasDefault = hasDefault,
    isDeferrable = wrappedType.isDeferrable(),
  )
}

private fun ConeKotlinType.asWrappedType(session: FirSession): WrappedType<ConeKotlinType> {
  val rawClassId = classId

  // Check if this is a Map type
  if (rawClassId == StandardClassIds.Map && typeArguments.size == 2) {
    val keyType = typeArguments[0].expectAs<ConeKotlinTypeProjection>().type
    val valueType = typeArguments[1].expectAs<ConeKotlinTypeProjection>().type

    // Recursively analyze the value type
    val valueWrappedType = valueType.asWrappedType(session)

    return WrappedType.Map(keyType, valueWrappedType) {
      session.metroFirBuiltIns.mapClassSymbol.constructType(
        arrayOf(keyType, valueWrappedType.canonicalType())
      )
    }
  }

  // Check if this is a Provider type
  if (rawClassId in session.classIds.providerTypes) {
    val innerType = typeArguments[0].expectAs<ConeKotlinTypeProjection>().type

    // Check if the inner type is a Lazy type
    val innerRawClassId = innerType.classId
    if (innerRawClassId in session.classIds.lazyTypes) {
      val lazyInnerType = innerType.typeArguments[0].expectAs<ConeKotlinTypeProjection>().type
      return WrappedType.Provider(
        WrappedType.Lazy(WrappedType.Canonical(lazyInnerType), innerRawClassId!!),
        rawClassId!!,
      )
    }

    // Recursively analyze the inner type
    val innerWrappedType = innerType.asWrappedType(session)

    return WrappedType.Provider(innerWrappedType, rawClassId!!)
  }

  // Check if this is a Lazy type
  if (rawClassId in session.classIds.lazyTypes) {
    val innerType = typeArguments[0].expectAs<ConeKotlinTypeProjection>().type

    // Recursively analyze the inner type
    val innerWrappedType = innerType.asWrappedType(session)

    return WrappedType.Lazy(innerWrappedType, rawClassId!!)
  }

  // If it's not a special type, it's a canonical type
  return WrappedType.Canonical(this)
}
