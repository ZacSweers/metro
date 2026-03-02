// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

internal interface BaseContextualTypeKey<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  ImplType : BaseContextualTypeKey<Type, TypeKey, ImplType>,
> {
  val typeKey: TypeKey
  val wrappedType: WrappedType<Type>
  val hasDefault: Boolean
  val rawType: Type?
  val isDeferrable: Boolean
    get() = wrappedType.isDeferrable()

  val requiresProviderInstance: Boolean
    get() =
      isWrappedInProvider ||
        isWrappedInLazy ||
        isLazyWrappedInProvider ||
        isWrappedInSuspendProvider

  val isWrappedInProvider: Boolean
    get() = wrappedType is WrappedType.Provider

  val isWrappedInSuspendProvider: Boolean
    get() = wrappedType is WrappedType.SuspendProvider

  val isWrappedInLazy: Boolean
    get() = wrappedType is WrappedType.Lazy

  val isLazyWrappedInProvider: Boolean
    get() =
      wrappedType is WrappedType.Provider &&
        (wrappedType as WrappedType.Provider<Type>).innerType is WrappedType.Lazy

  val isMapProvider: Boolean
    get() = wrappedType.findMapValueType() is WrappedType.Provider

  val isMapLazy: Boolean
    get() = wrappedType.findMapValueType() is WrappedType.Lazy

  val isMapSuspendProvider: Boolean
    get() = wrappedType.findMapValueType() is WrappedType.SuspendProvider

  val isMapProviderLazy: Boolean
    get() {
      val valueType = wrappedType.findMapValueType()
      return valueType is WrappedType.Provider && valueType.innerType is WrappedType.Lazy
    }

  fun render(short: Boolean, includeQualifier: Boolean = true): String
}
