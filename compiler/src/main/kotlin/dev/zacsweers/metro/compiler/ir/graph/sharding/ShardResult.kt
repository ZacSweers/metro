package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.ir.graph.BindingPropertyContext
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.declarations.IrProperty

/** Result of shard generation. */
internal data class ShardResult(
  val shards: List<Shard>,
  val shardLookup: ShardLookup,
  /** True if using the graph class as a single shard (no nested shard classes). */
  val isGraphAsShard: Boolean,
) {

  /**
   * Registers all shard properties in the binding property context.
   *
   * @param bindingPropertyContext The context to register properties in
   * @param shardFields Map of shard index to the shard field property on the main class. Empty for
   *   graph-as-shard mode.
   */
  fun registerProperties(
    bindingPropertyContext: BindingPropertyContext,
    shardFields: Map<Int, IrProperty>,
  ) {
    for (shard in shards) {
      val shardField = if (shard.isGraphAsShard) null else shardFields[shard.index]
      if (!shard.isGraphAsShard && shardField == null) {
        reportCompilerBug("Missing shard field for shard ${shard.index}")
      }
      for (shardProperty in shard.properties.values) {
        bindingPropertyContext.put(
          key = shardProperty.contextKey,
          property = shardProperty.property,
          shardProperty = shardField,
          shardIndex = if (shard.isGraphAsShard) null else shard.index,
        )
      }
    }
  }
}
