/*
 * Copyright (C) 2021 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.ir

import dev.zacsweers.lattice.ExitProcessingException
import dev.zacsweers.lattice.LatticeClassIds
import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.transformers.DependencyGraphData
import dev.zacsweers.lattice.transformers.DependencyGraphTransformer
import dev.zacsweers.lattice.transformers.LatticeTransformerContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

internal class LatticeIrGenerationExtension(
  private val messageCollector: MessageCollector,
  private val classIds: LatticeClassIds,
  private val debug: Boolean,
) : IrGenerationExtension {

  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    val symbols = LatticeSymbols(moduleFragment, pluginContext, classIds)
    val context = LatticeTransformerContext(pluginContext, messageCollector, symbols, debug)
    val dependencyGraphTransformer = DependencyGraphTransformer(context)
    // TODO is this really necessary?
    val dependencyGraphData = DependencyGraphData()
    try {
      moduleFragment.transform(dependencyGraphTransformer, dependencyGraphData)
    } catch (_: ExitProcessingException) {
      // End processing, don't fail up because this would've been warned before
    }
  }
}
