// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import org.jetbrains.kotlin.ir.declarations.IrProperty

/** Models a shard property created inside a shard. */
internal data class ShardProperty(
  val property: IrProperty,
  val contextKey: IrContextualTypeKey,
  val shardBinding: ShardBinding,
)
