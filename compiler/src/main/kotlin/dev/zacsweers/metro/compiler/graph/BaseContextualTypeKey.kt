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

  /**
   * True when the outer type is any wrapper (Provider/Lazy or their suspend analogues). Sites that
   * care only about the non-suspend wrappers should check [isWrappedInProvider] and
   * [isWrappedInLazy] explicitly.
   */
  val isWrapped: Boolean
    get() =
      isWrappedInProvider || isWrappedInLazy || isWrappedInSuspendProvider || isWrappedInSuspendLazy

  val requiresProviderInstance: Boolean
    get() = isWrapped

  val isWrappedInProvider: Boolean
    get() = wrappedType is WrappedType.Provider

  val isWrappedInSuspendProvider: Boolean
    get() = wrappedType is WrappedType.SuspendProvider

  val isWrappedInSuspendLazy: Boolean
    get() = wrappedType is WrappedType.SuspendLazy

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

  /**
   * Whether this contextual type defers suspend resolution at access time, meaning an accessor
   * returning it does not itself need to be `suspend`. Provider/Lazy wrappings defer; an improper
   * Provider/Lazy wrapping a suspend leaf is reported separately.
   */
  val defersSuspendAtAccess: Boolean
    get() =
      isWrappedInSuspendProvider ||
        isWrappedInSuspendLazy ||
        isMapSuspendProvider ||
        isWrappedInProvider ||
        isWrappedInLazy

  val isMapProviderLazy: Boolean
    get() {
      val valueType = wrappedType.findMapValueType()
      return valueType is WrappedType.Provider && valueType.innerType is WrappedType.Lazy
    }

  fun render(
    short: Boolean,
    includeQualifier: Boolean = true,
    useRelativeClassNames: Boolean = false,
  ): String
}
