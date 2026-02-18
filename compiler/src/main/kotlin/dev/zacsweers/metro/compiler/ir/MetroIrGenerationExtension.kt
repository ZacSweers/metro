// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.ExitProcessingException
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.ir.graph.IrDynamicGraphGenerator
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.ContributionHintIrTransformer
import dev.zacsweers.metro.compiler.ir.transformers.ContributionTransformer
import dev.zacsweers.metro.compiler.ir.transformers.CoreTransformers
import dev.zacsweers.metro.compiler.ir.transformers.CreateGraphTransformer
import dev.zacsweers.metro.compiler.ir.transformers.DependencyGraphTransformer
import dev.zacsweers.metro.compiler.ir.transformers.HintGenerator
import dev.zacsweers.metro.compiler.ir.transformers.InjectedClassTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MutableMetroGraphData
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall

public class MetroIrGenerationExtension(
  private val messageCollector: MessageCollector,
  private val classIds: ClassIds,
  private val options: MetroOptions,
  private val lookupTracker: LookupTracker?,
  private val expectActualTracker: ExpectActualTracker,
  private val compatContext: CompatContext,
) : IrGenerationExtension {

  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val symbols = Symbols(moduleFragment, pluginContext, classIds, options)
    val context =
      IrMetroContext(
        pluginContext,
        messageCollector,
        compatContext,
        symbols,
        options,
        lookupTracker,
        expectActualTracker,
      )

    context.traceDriver.use {
      if (options.parallelMetroThreads > 0) {
        val threadCount = AtomicInteger(0)
        val executorService =
          Executors.newFixedThreadPool(options.parallelMetroThreads) { runnable ->
            Thread(runnable).apply {
              isDaemon = true
              name = "metro-thread-${threadCount.incrementAndGet()}"
            }
          }
        try {
          context.generateInner(moduleFragment, executorService)
        } finally {
          // TODO on JDK 19+ just use close()
          executorService.shutdown()
          if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
            executorService.shutdownNow()
          }
        }
      } else {
        context.generateInner(moduleFragment, null)
      }
      if (options.traceEnabled) {
        // Find and print the trace file
        options.traceDir.value?.let { traceDir ->
          traceDir
            .toFile()
            .walkTopDown()
            .find { it.extension == "perfetto-trace" }
            ?.let {
              context.log(
                // Trailing space intentional for terminal linkifying
                "Metro trace written to file://${it.absolutePath} "
              )
            }
        }
      }
    }
  }

  private fun IrMetroContext.generateInner(
    moduleFragment: IrModuleFragment,
    executorService: ExecutorService?,
  ) {
    log("Starting IR processing of ${moduleFragment.name.asString()}")
    try {
      traceWithScope(moduleFragment.name.asString().removePrefix("<").removeSuffix(">")) {
        trace("Metro compiler") {
          // Create contribution data container
          val contributionData = IrContributionData(metroContext)

          val hintGenerator = HintGenerator(metroContext, moduleFragment)
          val membersInjectorTransformer = MembersInjectorTransformer(metroContext)
          val injectedClassTransformer =
            InjectedClassTransformer(metroContext, membersInjectorTransformer)
          val assistedFactoryTransformer =
            AssistedFactoryTransformer(metroContext, injectedClassTransformer)
          val bindingContainerTransformer = BindingContainerTransformer(metroContext)
          val contributionHintIrTransformer: Lazy<ContributionHintIrTransformer> = memoize {
            ContributionHintIrTransformer(metroContext, hintGenerator)
          }

          val contributionMerger = IrContributionMerger(metroContext, contributionData)
          val bindingContainerResolver = IrBindingContainerResolver(bindingContainerTransformer)
          val syntheticGraphs = mutableListOf<GraphToProcess>()
          val dynamicGraphGenerator =
            IrDynamicGraphGenerator(metroContext, bindingContainerResolver, contributionMerger) {
              impl,
              anno ->
              syntheticGraphs += GraphToProcess(impl, anno, impl)
            }
          val createGraphTransformer =
            CreateGraphTransformer(metroContext, dynamicGraphGenerator, this)

          val graphs = mutableListOf<GraphToProcess>()
          val data = MutableMetroGraphData(contributionData, graphs, syntheticGraphs)

          // Run non-graph transforms + aggregate contribution data in a single pass
          trace("Core transformers") {
            moduleFragment.transform(
              CoreTransformers(
                metroContext,
                this,
                data,
                ContributionTransformer(metroContext, this),
                membersInjectorTransformer,
                injectedClassTransformer,
                assistedFactoryTransformer,
                bindingContainerTransformer,
                contributionHintIrTransformer,
                createGraphTransformer,
              ),
              null,
            )
          }

          membersInjectorTransformer.lock()
          injectedClassTransformer.lock()
          assistedFactoryTransformer.lock()
          bindingContainerTransformer.lock()

          val dependencyGraphTransformer =
            DependencyGraphTransformer(
              metroContext,
              contributionData,
              this,
              executorService,
              membersInjectorTransformer,
              injectedClassTransformer,
              assistedFactoryTransformer,
              bindingContainerTransformer,
            )

          // Second - transform the dependency graphs
          trace("Graph transformers") {
            for ((declaration, anno, impl) in data.allGraphs) {
              dependencyGraphTransformer.processGraph(declaration, anno, impl)
            }
          }
        }
      }
    } catch (_: ExitProcessingException) {
      // Reported internally
      return
    }
  }
}

internal data class GraphToProcess(
  val declaration: IrClass,
  val dependencyGraphAnno: IrConstructorCall,
  val graphImpl: IrClass,
)
