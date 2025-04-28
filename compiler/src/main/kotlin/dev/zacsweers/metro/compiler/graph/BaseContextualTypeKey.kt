package dev.zacsweers.metro.compiler.graph

internal interface BaseContextualTypeKey<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  ImplType : BaseContextualTypeKey<Type, TypeKey, ImplType>,
> {
  val typeKey: TypeKey
  val wrappedType: WrappedType<Type>
  val hasDefault: Boolean
  val isIntoMultibinding: Boolean
  val rawType: Type?
  val isDeferrable: Boolean
    get() = wrappedType.isDeferrable()

  val requiresProviderInstance: Boolean
    get() = isDeferrable

  val isWrappedInProvider: Boolean
    get() = wrappedType is WrappedType.Provider

  val isWrappedInLazy: Boolean
    get() = wrappedType is WrappedType.Lazy

  val isLazyWrappedInProvider: Boolean
    get() =
      wrappedType is WrappedType.Provider &&
        (wrappedType as WrappedType.Provider<Type>).innerType is WrappedType.Lazy

  fun withTypeKey(typeKey: TypeKey, rawType: Type? = null): ImplType

  fun render(short: Boolean, includeQualifier: Boolean = true): String
}
