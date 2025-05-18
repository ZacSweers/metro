// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration

public class MetroCommandLineProcessor : CommandLineProcessor {

  override val pluginId: String = "dev.zacsweers.metro.compiler"

  override val pluginOptions: Collection<AbstractCliOption> =
    MetroOption.entries.map { it.raw.cliOption }

  override fun processOption(
    option: AbstractCliOption,
    value: String,
    configuration: CompilerConfiguration,
  ) {
    when (val metroOption = MetroOption.entriesByOptionName[option.optionName]) {
      null -> throw CliOptionProcessingException("Unknown plugin option: ${option.optionName}")
      else -> with(metroOption.raw) { configuration.put(value) }
    }
  }
}
