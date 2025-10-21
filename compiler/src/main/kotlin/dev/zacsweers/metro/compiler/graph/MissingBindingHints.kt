// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

internal data class MissingBindingHints<Type : Any, TypeKey : BaseTypeKey<Type, *, TypeKey>>(
  val messages: List<String> = emptyList(),
  val similarBindings: Map<TypeKey, String> = emptyMap(),
)
