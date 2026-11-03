// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.tracing.TraceContext
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
import org.jetbrains.kotlin.platform.jvm.isJvm

public class MetroIrGenerationExtension(
  private val messageCollector: MessageCollector,
  private val classIds: ClassIds,
  private val options: MetroOptions,
  private val lookupTracker: LookupTracker?,
  private val expectActualTracker: ExpectActualTracker,
  private val compatContext: CompatContext,
  private val traceContext: TraceContext,
) : IrGenerationExtension {

  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val graph =
      createGraphFactory<IrDependencyGraph.Factory>()
        .create(
          messageCollector,
          classIds,
          options,
          lookupTracker,
          expectActualTracker,
          compatContext,
          moduleFragment,
          pluginContext,
          traceContext,
        )
    graph.pipeline.run()

    if (options.fixEmptySmapRanges && pluginContext.platform.isJvm()) {
      // Repair synthetic files after all FIR and IR generated declarations have been processed.
      for (file in moduleFragment.files) {
        file.ensureSyntheticFileHasLineTable()
      }
    }
  }
}

internal data class GraphToProcess(
  val declaration: IrClass,
  val dependencyGraphAnno: IrConstructorCall,
  val graphImpl: IrClass,
  val scopes: Set<ClassId>,
)
