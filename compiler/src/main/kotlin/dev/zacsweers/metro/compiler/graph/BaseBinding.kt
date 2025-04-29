// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

internal interface BaseBinding<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, *>,
> {
  val contextualTypeKey: ContextualTypeKey
  val typeKey: TypeKey
    get() = contextualTypeKey.typeKey

  val dependencies: List<ContextualTypeKey>
}
