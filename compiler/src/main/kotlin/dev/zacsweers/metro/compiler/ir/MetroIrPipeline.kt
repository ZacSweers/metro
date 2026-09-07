// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.compiler.ExitProcessingException
import dev.zacsweers.metro.compiler.ir.transformers.CoreTransformers
import dev.zacsweers.metro.compiler.ir.transformers.DependencyGraphTransformer
import dev.zacsweers.metro.compiler.ir.transformers.Lockable
import dev.zacsweers.metro.compiler.ir.transformers.MutableMetroGraphData
import dev.zacsweers.metro.compiler.tracing.TraceContext
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.concurrent.ForkJoinPool
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

@Inject
internal class MetroIrPipeline(
  override val metroContext: IrMetroContext,
  private val forkJoinPool: ForkJoinPool?,
  private val moduleFragment: IrModuleFragment,
  private val coreTransformers: CoreTransformers,
  private val dependencyGraphTransformer: DependencyGraphTransformer,
  private val graphData: MutableMetroGraphData,
  private val lockableTransformers: Set<Lockable>,
  private val traceContext: TraceContext,
  traceScope: TraceScope,
) : IrMetroContext by metroContext, TraceScope by traceScope {
  fun run() {
    // FIR is done by the time IR begins, so finalize any FIR-side trace files now. Idempotent
    // across multi-fragment IR runs.
    traceContext.close()
    // This fragment's own IR driver is closed at the end of run() via .use {}.
    traceDriver.use {
      if (forkJoinPool != null) {
        forkJoinPool.use { runTraced(moduleFragment) }
      } else {
        runTraced(moduleFragment)
      }
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

  private fun runTraced(moduleFragment: IrModuleFragment) {
    log("Starting IR processing of ${moduleFragment.name.asString()}")
    try {
      trace("Metro compiler") {
        // Create contribution data container
        // Run non-graph transforms + aggregate contribution data in a single pass
        trace("Core transformers") { moduleFragment.transform(coreTransformers, null) }

        val data = graphData

        // An early graph can include a later graph, so finish every root's supertypes before
        // graph preparation caches their hierarchies.
        val graphs =
          trace("Prepare graph headers") {
            data.allGraphs.filter { (declaration, annotation, impl) ->
              try {
                dependencyGraphTransformer.applyIrContributionMergeIfNeeded(
                  declaration,
                  annotation,
                  impl,
                )
                true
              } catch (_: ExitProcessingException) {
                // This graph already reported its error. Other roots can still be processed.
                false
              }
            }
          }
        lockableTransformers.forEach { it.lock() }

        // Second - transform the dependency graphs
        trace("Graph transformers") {
          for ((declaration, anno, impl) in graphs) {
            dependencyGraphTransformer.processGraph(declaration, anno, impl)
          }
        }
      }

      // All (possibly parallel) IR work is done, so flush the buffered IC tracking
      trace("Flush IC tracking") { flushIcTracking() }
    } catch (_: ExitProcessingException) {
      // Reported internally
    }
  }
}
