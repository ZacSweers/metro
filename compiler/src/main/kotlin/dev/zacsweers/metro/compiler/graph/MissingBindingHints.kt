package dev.zacsweers.metro.compiler.graph

internal data class MissingBindingHints<Type : Any, TypeKey : BaseTypeKey<Type, *, TypeKey>>(
  val similarBindings: Map<TypeKey, String> = emptyMap()
)
