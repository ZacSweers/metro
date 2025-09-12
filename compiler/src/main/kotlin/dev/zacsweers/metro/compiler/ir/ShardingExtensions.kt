// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.graph.GraphSharding

/**
 * Extension property that computes sharding information for an [IrBindingGraph].
 *
 * When targeting JVM backends, large dependency graphs can exceed limits on the size of a single
 * generated class or method.  Dagger addresses this by splitting the generated component into
 * multiple implementation classes (“shards”) and delegating calls between them.  Metro
 * implements a similar approach via [GraphSharding].  Consumers can query this extension to
 * obtain a list of shards representing contiguous subsets of the binding graph.  Each inner
 * list contains the [IrTypeKey]s that belong to that shard in dependency order.
 *
 * The shards are computed on demand using the [GraphSharding.computeShardsFromBindings] helper.
 * For deterministic output, the adjacency is constructed from the bindings snapshot of the
 * [IrBindingGraph] and sorted.  This property does not cache its result, so repeated calls
 * recompute the sharding; callers should memoize if necessary.
 */
internal val IrBindingGraph.shards: List<List<IrTypeKey>>
  get() {
    val bindings = bindingsSnapshot()
    if (bindings.isEmpty()) return emptyList()
    return GraphSharding.computeShardsFromBindings(
      bindings = bindings,
      dependenciesOf = { binding -> binding.dependencies.map { it.typeKey } },
      threshold = GraphSharding.DEFAULT_BINDINGS_PER_SHARD,
    )
  }
