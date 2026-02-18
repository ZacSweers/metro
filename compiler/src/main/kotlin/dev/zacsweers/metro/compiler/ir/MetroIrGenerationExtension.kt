// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.ExitProcessingException
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.ir.transformers.AssistedFactoryTransformer
import dev.zacsweers.metro.compiler.ir.transformers.BindingContainerTransformer
import dev.zacsweers.metro.compiler.ir.transformers.ContributionHintIrTransformer
import dev.zacsweers.metro.compiler.ir.transformers.ContributionTransformer
import dev.zacsweers.metro.compiler.ir.transformers.CoreTransformers
import dev.zacsweers.metro.compiler.ir.transformers.DependencyGraphTransformer
import dev.zacsweers.metro.compiler.ir.transformers.FirstPassData
import dev.zacsweers.metro.compiler.ir.transformers.HintGenerator
import dev.zacsweers.metro.compiler.ir.transformers.InjectedClassTransformer
import dev.zacsweers.metro.compiler.ir.transformers.MembersInjectorTransformer
import dev.zacsweers.metro.compiler.memoize
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

          // Run non-graph transforms + aggregate contribution data in a single pass
          trace("Core transformers") {
            moduleFragment.transform(
              CoreTransformers(
                metroContext,
                this,
                ContributionTransformer(metroContext, this),
                membersInjectorTransformer,
                injectedClassTransformer,
                assistedFactoryTransformer,
                bindingContainerTransformer,
                contributionHintIrTransformer,
              ),
              FirstPassData(contributionData),
            )
          }

          membersInjectorTransformer.lock()
          injectedClassTransformer.lock()
          assistedFactoryTransformer.lock()
          bindingContainerTransformer.lock()
          if (contributionHintIrTransformer.isInitialized()) {
            contributionHintIrTransformer.value.lock()
          }

          // Second - transform the dependency graphs
          trace("Graph transformers") {
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
                contributionHintIrTransformer,
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
