// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.CompilerVersionAliases
import dev.zacsweers.metro.compiler.compat.KotlinToolingVersion
import dev.zacsweers.metro.compiler.fir.MetroFirExtensionRegistrar
import dev.zacsweers.metro.compiler.ir.MetroIrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer.PLAIN_FULL_PATHS
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker

public class MetroCompilerPluginRegistrar : CompilerPluginRegistrar() {

  private companion object {
    val isIde by lazy {
      try {
        // Try to look up an IntelliJ-only class
        Class.forName("org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSession")
        true
      } catch (_: ClassNotFoundException) {
        false
      }
    }
  }

  public override val pluginId: String = PLUGIN_ID

  override val supportsK2: Boolean
    get() = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    val options = MetroOptions.load(configuration)

    if (!options.enabled) return

    val version =
      options.compilerVersion?.let(::KotlinToolingVersion)
        ?: CompatContext.Factory.loadCompilerVersionOrNull()?.let { rawVersion ->
          CompilerVersionAliases.map(rawVersion, options.compilerVersionAliases)
            ?: run {
              System.err.println(
                "[METRO] Skipping enabling Metro extensions in IDE. " +
                  "Detected Kotlin version '$rawVersion' is not supported for IDE use (CLI_ONLY)."
              )
              return
            }
        }

    val enableFir = version != null || (isIde && options.forceEnableFirInIde)

    if (!enableFir) {
      // While the option is about FIR, this really also means we can't/don't enable IR
      System.err.println(
        "[METRO] Skipping enabling Metro extensions. Detected Kotlin version: $version"
      )
      return
    }

    val compatContext =
      try {
        CompatContext.create(version)
      } catch (t: Throwable) {
        System.err.println(
          "[METRO] Skipping enabling Metro extensions, unable to create CompatContext for version $version"
        )
        t.printStackTrace()
        return
      }

    val classIds = ClassIds.fromOptions(options)

    val realMessageCollector = configuration.messageCollector
    val messageCollector =
      if (options.debug) {
        DebugMessageCollector(realMessageCollector)
      } else {
        configuration.messageCollector
      }

    if (options.debug) {
      messageCollector.report(
        CompilerMessageSeverity.INFO,
        "Metro mode: ${if (isIde) "IDE" else "CLI"}",
      )
      messageCollector.report(CompilerMessageSeverity.INFO, "Metro options:\n$options")
    }

    if (options.maxIrErrorsCount < 1) {
      messageCollector.report(
        CompilerMessageSeverity.ERROR,
        "maxIrErrorsCount must be greater than zero but was ${options.maxIrErrorsCount}",
      )
      return
    }

    if (options.keysPerGraphShard < 1) {
      messageCollector.report(
        CompilerMessageSeverity.ERROR,
        "keysPerGraphShard must be greater than zero but was ${options.keysPerGraphShard}",
      )
      return
    }

    if (options.parallelThreads < 0) {
      messageCollector.report(
        CompilerMessageSeverity.ERROR,
        "parallelMetroThreads must be non-negative but was ${options.parallelThreads}",
      )
      return
    }

    with(compatContext) {
      registerFirExtensionCompat(
        MetroFirExtensionRegistrar(classIds, options, isIde, compatContext)
      )
    }

    if (!isIde) {
      val lookupTracker = configuration.get(CommonConfigurationKeys.LOOKUP_TRACKER)
      val expectActualTracker: ExpectActualTracker =
        configuration.get(
          CommonConfigurationKeys.EXPECT_ACTUAL_TRACKER,
          ExpectActualTracker.DoNothing,
        )
      with(compatContext) {
        registerIrExtensionCompat(
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
