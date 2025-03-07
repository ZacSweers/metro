// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.transformers.DependencyGraphData
import dev.zacsweers.metro.compiler.ir.transformers.DependencyGraphTransformer
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

public class MetroIrGenerationExtension(
  private val messageCollector: MessageCollector,
  private val classIds: ClassIds,
  private val options: MetroOptions,
) : IrGenerationExtension {

  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val symbols = Symbols(moduleFragment, pluginContext, classIds, options)
    val context = IrMetroContext(pluginContext, messageCollector, symbols, options)
    val dependencyGraphTransformer = DependencyGraphTransformer(context, moduleFragment)
    // TODO is this really necessary?
    val dependencyGraphData = DependencyGraphData()
    moduleFragment.transform(dependencyGraphTransformer, dependencyGraphData)
  }
}
