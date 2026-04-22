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
import dev.zacsweers.metro.compiler.ir.transformers.ContributionIrTransformer
import dev.zacsweers.metro.compiler.ir.transformers.CoreTransformers
import dev.zacsweers.metro.compiler.ir.transformers.CreateGraphTransformer
import dev.zacsweers.metro.compiler.ir.transformers.DefaultBindingMirrorTransformer
import dev.zacsweers.metro.compiler.ir.transformers.DependencyGraphTransformer
import dev.zacsweers.metro.compiler.ir.transformers.HintGenerator
import dev.zacsweers.metro.compiler.ir.transformers.InjectedClassTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MutableMetroGraphData
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.trace
import dev.zacsweers.metro.createGraphFactory
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.name.ClassId

public class MetroIrGenerationExtension(
  private val messageCollector: MessageCollector,
  private val classIds: ClassIds,
  private val options: MetroOptions,
  private val lookupTracker: LookupTracker?,
  private val expectActualTracker: ExpectActualTracker,
  private val compatContext: CompatContext,
) : IrGenerationExtension {

  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val graph =
      createGraphFactory<IrMetroGraph.Factory>()
        .create(
          messageCollector,
          classIds,
          options,
          lookupTracker,
          expectActualTracker,
          compatContext,
          moduleFragment,
          pluginContext,
        )

    val context = graph.metroContext

    context.traceDriver.use {
      val forkJoinPool = graph.forkJoinPool
      if (forkJoinPool != null) {
        graph.forkJoinPool.use { generateInner(graph, moduleFragment) }
      } else {
        generateInner(graph, moduleFragment)
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

  private fun generateInner(graph: IrMetroGraph, moduleFragment: IrModuleFragment) =
    with(graph.metroContext) {
      log("Starting IR processing of ${moduleFragment.name.asString()}")
      try {
        traceWithScope(moduleFragment.name.asString().removePrefix("<").removeSuffix(">")) {
          trace("Metro compiler") {
            // Create contribution data container
            val createGraphTransformer = graph.createGraphTransformer.create(this)
            val contributionTransformer = graph.contributionTransformer.create(this)
            val coreTransformers =
              graph.coreTransformersFactory.create(
                this,
                contributionTransformer,
                createGraphTransformer,
              )

            // Run non-graph transforms + aggregate contribution data in a single pass
            trace("Core transformers") { moduleFragment.transform(coreTransformers, null) }

            val data = graph.graphData
            val contributionData = graph.contributionData

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
            graph.lockableTransformers.forEach { it.lock() }

            val dependencyGraphTransformer = graph.dependencyGraphTransformerFactory.create(this)

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
      }
    }
}

internal data class GraphToProcess(
  val declaration: IrClass,
  val dependencyGraphAnno: IrConstructorCall,
  val graphImpl: IrClass,
  val scopes: Set<ClassId>,
)
