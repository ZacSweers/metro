// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.ir.transformers.ContributionIrTransformer
import dev.zacsweers.metro.compiler.ir.transformers.CoreTransformers
import dev.zacsweers.metro.compiler.ir.transformers.CreateGraphTransformer
import dev.zacsweers.metro.compiler.ir.transformers.DependencyGraphTransformer
import dev.zacsweers.metro.compiler.ir.transformers.Lockable
import dev.zacsweers.metro.compiler.ir.transformers.MutableMetroGraphData
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.util.concurrent.ForkJoinPool
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

internal abstract class IrScope private constructor()

@Qualifier internal annotation class SyntheticGraphs

@DependencyGraph(IrScope::class)
internal interface IrMetroGraph {

  val metroContext: IrMetroContext
  val forkJoinPool: ForkJoinPool?
  val createGraphTransformer: CreateGraphTransformer.Factory
  val contributionTransformer: ContributionIrTransformer.Factory
  val coreTransformersFactory: CoreTransformers.Factory
  val dependencyGraphTransformerFactory: DependencyGraphTransformer.Factory
  val graphData: MutableMetroGraphData
  val contributionData: IrContributionData

  val lockableTransformers: Set<Lockable>

  @Provides
  @SingleIn(IrScope::class)
  fun provideForkJoinPool(options: MetroOptions): ForkJoinPool? {
    return if (options.parallelThreads > 0) {
      ForkJoinPool(options.parallelThreads)
    } else {
      null
    }
  }

  @Provides
  @SyntheticGraphs
  @SingleIn(IrScope::class)
  fun provideSyntheticGraphs(): MutableList<GraphToProcess> = mutableListOf()

  @SingleIn(IrScope::class)
  @Provides
  fun provideIrMetroContext(
    pluginContext: IrPluginContext,
    messageCollector: MessageCollector,
    compatContext: CompatContext,
    symbols: Symbols,
    options: MetroOptions,
    lookupTracker: LookupTracker?,
    expectActualTracker: ExpectActualTracker,
  ): IrMetroContext {
    return IrMetroContext(
      pluginContext,
      messageCollector,
      compatContext,
      symbols,
      options,
      lookupTracker,
      expectActualTracker,
    )
  }

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      @Provides messageCollector: MessageCollector,
      @Provides classIds: ClassIds,
      @Provides options: MetroOptions,
      @Provides lookupTracker: LookupTracker?,
      @Provides expectActualTracker: ExpectActualTracker,
      @Provides compatContext: CompatContext,
      @Provides moduleFragment: IrModuleFragment,
      @Provides pluginContext: IrPluginContext,
    ): IrMetroGraph
  }
}
