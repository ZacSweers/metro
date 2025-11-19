// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.fir.MetroFirExtensionRegistrar
import dev.zacsweers.metro.compiler.ir.MetroIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer.PLAIN_FULL_PATHS
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker

public class MetroCompilerPluginRegistrar : CompilerPluginRegistrar() {

  public val pluginId: String = PLUGIN_ID

  override val supportsK2: Boolean
    get() = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    val options = MetroOptions.load(configuration)

    if (!options.enabled) return

    val classIds = ClassIds.fromOptions(options)

    val realMessageCollector = configuration.messageCollector
    val messageCollector =
      if (options.debug) {
        DebugMessageCollector(realMessageCollector)
      } else {
        configuration.messageCollector
      }

    if (options.maxIrErrorsCount < 1) {
      messageCollector.report(
        CompilerMessageSeverity.ERROR,
        "maxIrErrorsCount must be greater than zero but was ${options.maxIrErrorsCount}",
      )
      return
    }

    if (options.debug) {
      messageCollector.report(CompilerMessageSeverity.INFO, "Metro options:\n$options")
    }

    val compatContext = CompatContext.getInstance()
    FirExtensionRegistrarAdapter.registerExtension(
      MetroFirExtensionRegistrar(classIds, options, compatContext)
    )
    val lookupTracker = configuration.get(CommonConfigurationKeys.LOOKUP_TRACKER)
    val expectActualTracker: ExpectActualTracker =
      configuration.get(
        CommonConfigurationKeys.EXPECT_ACTUAL_TRACKER,
        ExpectActualTracker.DoNothing,
      )
    IrGenerationExtension.registerExtension(
      MetroIrGenerationExtension(
        messageCollector = configuration.messageCollector,
        classIds = classIds,
        options = options,
        lookupTracker = lookupTracker,
        expectActualTracker = expectActualTracker,
        compatContext = compatContext,
      )
    )
  }
}

internal val CompilerConfiguration.messageCollector: MessageCollector
  get() = get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)

private class DebugMessageCollector(private val delegate: MessageCollector) : MessageCollector {
  override fun clear() {
    delegate.clear()
  }

  override fun report(
    severity: CompilerMessageSeverity,
    message: String,
    location: CompilerMessageSourceLocation?,
  ) {
    println(PLAIN_FULL_PATHS.render(severity, message, location))
    println("${severity.presentableName}: $message")
    delegate.report(severity, message, location)
  }

  override fun hasErrors(): Boolean {
    return delegate.hasErrors()
  }
}
