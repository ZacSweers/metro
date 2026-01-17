// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.graph.PropertyKind
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name

/** Models a shard binding before its property is created. */
internal data class ShardBinding(
  val typeKey: IrTypeKey,
  val contextKey: IrContextualTypeKey,
  val propertyKind: PropertyKind,
  val irType: IrType,
  val nameHint: Name,
  val isScoped: Boolean,
  /**
   * True if this binding is a deferred type (i.e., `DelegateFactory` for breaking cycles). Deferred
   * properties are initialized with empty DelegateFactory(), then `setDelegate` is called at the
   * end of the shard's initialization.
   */
  val isDeferred: Boolean = false,
)
