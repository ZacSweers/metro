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
package dev.zacsweers.lattice.compiler

import com.google.auto.service.AutoService
import dev.zacsweers.lattice.compiler.fir.LatticeFirExtensionRegistrar
import dev.zacsweers.lattice.compiler.ir.LatticeIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@AutoService(CompilerPluginRegistrar::class)
public class LatticeCompilerPluginRegistrar : CompilerPluginRegistrar() {

  override val supportsK2: Boolean
    get() = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    val options = LatticeOptions.load(configuration)

    if (!options.enabled) return

    val classIds =
      LatticeClassIds(
        customAssistedAnnotations = options.customAssistedAnnotations,
        customAssistedFactoryAnnotations = options.customAssistedFactoryAnnotations,
        customAssistedInjectAnnotations = options.customAssistedInjectAnnotations,
        customBindsAnnotations = options.customBindsAnnotations,
        customBindsInstanceAnnotations = options.customBindsInstanceAnnotations,
        customContributesToAnnotations = options.customContributesToAnnotations,
        customContributesBindingAnnotations = options.customContributesBindingAnnotations,
        customElementsIntoSetAnnotations = options.customElementsIntoSetAnnotations,
        customGraphAnnotations = options.customGraphAnnotations,
        customGraphFactoryAnnotations = options.customGraphFactoryAnnotations,
        customInjectAnnotations = options.customInjectAnnotations,
        customIntoMapAnnotations = options.customIntoMapAnnotations,
        customIntoSetAnnotations = options.customIntoSetAnnotations,
        customMapKeyAnnotations = options.customMapKeyAnnotations,
        customMultibindsAnnotations = options.customMultibindsAnnotations,
        customProvidesAnnotations = options.customProvidesAnnotations,
        customQualifierAnnotations = options.customQualifierAnnotations,
        customScopeAnnotations = options.customScopeAnnotations,
      )

    if (options.debug) {
      configuration.messageCollector.report(
        CompilerMessageSeverity.STRONG_WARNING,
        "Lattice options:\n$options"
      )
    }

    FirExtensionRegistrarAdapter.registerExtension(LatticeFirExtensionRegistrar(classIds, options))
    IrGenerationExtension.registerExtension(
      LatticeIrGenerationExtension(configuration.messageCollector, classIds, options)
    )
  }
}

internal val CompilerConfiguration.messageCollector: MessageCollector
  get() = get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
