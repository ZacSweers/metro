// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import com.google.auto.service.AutoService
import dev.zacsweers.metro.compiler.fir.MetroFirExtensionRegistrar
import dev.zacsweers.metro.compiler.ir.MetroIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@AutoService(CompilerPluginRegistrar::class)
public class MetroCompilerPluginRegistrar : CompilerPluginRegistrar() {

  override val supportsK2: Boolean
    get() = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    val options = MetroOptions.load(configuration)

    if (!options.enabled) return

    val classIds =
      ClassIds(
        customAssistedAnnotations = options.customAssistedAnnotations,
        customAssistedFactoryAnnotations = options.customAssistedFactoryAnnotations,
        customAssistedInjectAnnotations = options.customAssistedInjectAnnotations,
        customBindsAnnotations = options.customBindsAnnotations,
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
        "Metro options:\n$options",
      )
    }

    FirExtensionRegistrarAdapter.registerExtension(MetroFirExtensionRegistrar(classIds, options))
    IrGenerationExtension.registerExtension(
      MetroIrGenerationExtension(configuration.messageCollector, classIds, options)
    )
  }
}

internal val CompilerConfiguration.messageCollector: MessageCollector
  get() = get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
