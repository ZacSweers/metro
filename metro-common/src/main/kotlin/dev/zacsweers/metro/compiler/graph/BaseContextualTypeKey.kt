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

  public val isWrapped: Boolean
    get() = isWrappedInProvider || isWrappedInLazy

  public val requiresProviderInstance: Boolean
    get() = isWrappedInProvider || isWrappedInLazy || isLazyWrappedInProvider

  public val isWrappedInProvider: Boolean
    get() = wrappedType is WrappedType.Provider

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
