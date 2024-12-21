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
package dev.zacsweers.lattice

import com.google.auto.service.AutoService
import dev.zacsweers.lattice.fir.LatticeFirExtensionRegistrar
import dev.zacsweers.lattice.ir.LatticeIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@OptIn(ExperimentalCompilerApi::class)
@AutoService(CompilerPluginRegistrar::class)
public class LatticeCompilerPluginRegistrar : CompilerPluginRegistrar() {

  override val supportsK2: Boolean
    get() = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    if (configuration[KEY_ENABLED] == false) return
    val debug = configuration[KEY_DEBUG] == true
    val generateAssistedFactories = configuration[KEY_GENERATE_ASSISTED_FACTORIES] == true

    val messageCollector =
      configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)

    val classIds = LatticeClassIds()
    FirExtensionRegistrarAdapter.registerExtension(
      LatticeFirExtensionRegistrar(classIds, generateAssistedFactories)
    )
    IrGenerationExtension.registerExtension(
      LatticeIrGenerationExtension(messageCollector, classIds, debug)
    )
  }
}
