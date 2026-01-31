// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.ExitProcessingException
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.ir.transformers.ContributionTransformer
import dev.zacsweers.metro.compiler.ir.transformers.DependencyGraphTransformer
import dev.zacsweers.metro.compiler.ir.transformers.HintGenerator
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

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
        Executors.newFixedThreadPool(options.parallelMetroThreads).use { executorService ->
          context.generateInner(moduleFragment, executorService)
        }
      } else {
        context.generateInner(moduleFragment, null)
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

          // First - transform `MetroContribution` interfaces and collect contribution data in a
          // single pass
          trace("Transform contributions") {
            moduleFragment.transform(ContributionTransformer(metroContext, this), contributionData)
          }

          // Second - transform the dependency graphs
          trace("Core transformers") {
            val dependencyGraphTransformer =
              DependencyGraphTransformer(
                metroContext,
                contributionData,
                this,
                HintGenerator(metroContext, moduleFragment),
                executorService,
              )
            moduleFragment.transform(dependencyGraphTransformer, null)
          }
        }
      }
    } catch (_: ExitProcessingException) {
      // Reported internally
      return
    }
  }
}
