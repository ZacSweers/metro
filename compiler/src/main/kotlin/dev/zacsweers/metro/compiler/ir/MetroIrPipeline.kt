// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.compiler.ExitProcessingException
import dev.zacsweers.metro.compiler.ir.transformers.CoreTransformers
import dev.zacsweers.metro.compiler.ir.transformers.DependencyGraphTransformer
import dev.zacsweers.metro.compiler.ir.transformers.Lockable
import dev.zacsweers.metro.compiler.ir.transformers.MutableMetroGraphData
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.concurrent.ForkJoinPool
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.name.ClassId

@Inject
internal class MetroIrPipeline(
  override val metroContext: IrMetroContext,
  private val forkJoinPool: ForkJoinPool?,
  private val moduleFragment: IrModuleFragment,
  private val coreTransformers: CoreTransformers,
  private val dependencyGraphTransformer: DependencyGraphTransformer,
  private val graphData: MutableMetroGraphData,
  private val contributionData: IrContributionData,
  private val lockableTransformers: Set<Lockable>,
  traceScope: TraceScope,
) : IrMetroContext by metroContext, TraceScope by traceScope {
  fun run() {
    traceDriver.use {
      if (forkJoinPool != null) {
        forkJoinPool.use { runTraced(moduleFragment) }
      } else {
        runTraced(moduleFragment)
      }

      if (options.traceEnabled) {
        // Find and print the most-recent trace file. Traces accumulate
        // across reruns, so always pick the freshest by mtime.
        options.traceDir.value?.let { traceDir ->
          traceDir
            .toFile()
            .walkTopDown()
            .filter { it.extension == "perfetto-trace" }
            .maxByOrNull { it.lastModified() }
            ?.let {
              log(
                // Trailing space intentional for terminal linkifying
                "Metro trace written to file://${it.absolutePath} "
              )
            }
        }
      }
    }
  }

  private fun runTraced(moduleFragment: IrModuleFragment) {
    log("Starting IR processing of ${moduleFragment.name.asString()}")
    try {
      trace("Metro compiler") {
        // Create contribution data container
        // Run non-graph transforms + aggregate contribution data in a single pass
        trace("Core transformers") { moduleFragment.transform(coreTransformers, null) }

        val data = graphData

        // Eagerly populate contribution caches for all known graph scopes
        // so that lookups are O(1) during (possibly parallel) graph validation.
        // Extension scopes not known here are lazily populated via ConcurrentHashMap.
        @Suppress("RETURN_VALUE_NOT_USED")
        trace("Populate contribution caches") {
          val seenScopes = mutableSetOf<ClassId>()
          for (graph in data.allGraphs) {
            for (scope in graph.scopes) {
              if (seenScopes.add(scope)) {
                contributionData.getContributions(scope, graph.declaration)
                contributionData.getBindingContainerContributions(scope, graph.declaration)
              }
            }
          }
        }
        lockableTransformers.forEach { it.lock() }

        // Second - transform the dependency graphs
        trace("Graph transformers") {
          for ((declaration, anno, impl) in data.allGraphs) {
            dependencyGraphTransformer.processGraph(declaration, anno, impl)
          }
        }
      }
    } catch (_: ExitProcessingException) {
      // Reported internally
    }
  }
}
