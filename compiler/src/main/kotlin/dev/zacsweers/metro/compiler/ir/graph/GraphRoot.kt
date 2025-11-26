// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction

internal sealed interface GraphRoot {
  val contextKey: IrContextualTypeKey
  val metroFunction: MetroSimpleFunction
}

internal data class GraphAccessor(
  override val contextKey: IrContextualTypeKey,
  override val metroFunction: MetroSimpleFunction,
  val isAnnotatedOptionalBinding: Boolean,
) : GraphRoot

internal data class InjectorFunction(
  override val contextKey: IrContextualTypeKey,
  override val metroFunction: MetroSimpleFunction,
) : GraphRoot
