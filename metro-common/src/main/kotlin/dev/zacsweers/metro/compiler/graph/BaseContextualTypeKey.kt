// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

public interface BaseContextualTypeKey<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  ImplType : BaseContextualTypeKey<Type, TypeKey, ImplType>,
> {
  public val typeKey: TypeKey
  public val wrappedType: WrappedType<Type>
  public val hasDefault: Boolean
  public val rawType: Type?
  public val isDeferrable: Boolean
    get() = wrappedType.isDeferrable()

  /**
   * True when the outer type is any wrapper (Provider/Lazy or their suspend analogues). Sites that
   * care only about the non-suspend wrappers should check [isWrappedInProvider] and
   * [isWrappedInLazy] explicitly.
   */
  public val isWrapped: Boolean
    get() =
      isWrappedInProvider || isWrappedInLazy || isWrappedInSuspendProvider || isWrappedInSuspendLazy

  public val requiresProviderInstance: Boolean
    get() = isWrapped

  public val isWrappedInProvider: Boolean
    get() = wrappedType is WrappedType.Provider

  public val isWrappedInSuspendProvider: Boolean
    get() = wrappedType is WrappedType.SuspendProvider

  public val isWrappedInSuspendLazy: Boolean
    get() = wrappedType is WrappedType.SuspendLazy

  public val isWrappedInLazy: Boolean
    get() = wrappedType is WrappedType.Lazy

  public val isLazyWrappedInProvider: Boolean
    get() =
      wrappedType is WrappedType.Provider &&
        (wrappedType as WrappedType.Provider<Type>).innerType is WrappedType.Lazy

  public val isMapProvider: Boolean
    get() = wrappedType.findMapValueType() is WrappedType.Provider

  public val isMapLazy: Boolean
    get() = wrappedType.findMapValueType() is WrappedType.Lazy

  public val isMapSuspendProvider: Boolean
    get() = wrappedType.findMapValueType() is WrappedType.SuspendProvider

  /** Whether the wrapper nearest the bound value can evaluate a suspend binding. */
  public val isSuspendCapableBoundary: Boolean
    get() = wrappedType.usesSuspendProvider() == true || isMapSuspendProvider

  public val isMapProviderLazy: Boolean
    get() {
      val valueType = wrappedType.findMapValueType()
      return valueType is WrappedType.Provider && valueType.innerType is WrappedType.Lazy
    }

  public fun render(
    short: Boolean,
    includeQualifier: Boolean = true,
    useRelativeClassNames: Boolean = false,
  ): String
}
