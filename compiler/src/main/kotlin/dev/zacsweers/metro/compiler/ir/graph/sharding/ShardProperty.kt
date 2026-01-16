package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import org.jetbrains.kotlin.ir.declarations.IrProperty

/** Models a shard property created inside a shard. */
internal data class ShardProperty(
  val property: IrProperty,
  val contextKey: IrContextualTypeKey,
  val shardBinding: ShardBinding,
)
