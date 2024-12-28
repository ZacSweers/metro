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
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal const val KEY_ENABLED_NAME = "enabled"
internal val KEY_ENABLED =
  CompilerConfigurationKey<Boolean>("Enable/disable Lattice's plugin on the given compilation")
internal const val KEY_DEBUG_NAME = "debug"
internal val KEY_DEBUG =
  CompilerConfigurationKey<Boolean>("Enable/disable debug logging on the given compilation")
internal const val KEY_GENERATE_ASSISTED_FACTORIES_NAME = "generate-assisted-factories"
internal val KEY_GENERATE_ASSISTED_FACTORIES =
  CompilerConfigurationKey<Boolean>("Enable/disable automatic generation of assisted factories")

@OptIn(ExperimentalCompilerApi::class)
@AutoService(CommandLineProcessor::class)
public class LatticeCommandLineProcessor : CommandLineProcessor {

  internal companion object {
    val OPTION_ENABLED =
      CliOption(
        optionName = KEY_ENABLED_NAME,
        valueDescription = "<true | false>",
        description = KEY_ENABLED.toString(),
        required = false,
        allowMultipleOccurrences = false,
      )
    val OPTION_DEBUG =
      CliOption(
        optionName = KEY_DEBUG_NAME,
        valueDescription = "<true | false>",
        description = KEY_DEBUG.toString(),
        required = false,
        allowMultipleOccurrences = false,
      )
    val OPTION_GENERATE_ASSISTED_FACTORIES =
      CliOption(
        optionName = KEY_GENERATE_ASSISTED_FACTORIES_NAME,
        valueDescription = "<true | false>",
        description = KEY_GENERATE_ASSISTED_FACTORIES.toString(),
        required = false,
        allowMultipleOccurrences = false,
      )
  }

  override val pluginId: String = "dev.zacsweers.lattice.compiler"

  override val pluginOptions: Collection<AbstractCliOption> =
    listOf(OPTION_ENABLED, OPTION_DEBUG, OPTION_GENERATE_ASSISTED_FACTORIES)

  override fun processOption(
    option: AbstractCliOption,
    value: String,
    configuration: CompilerConfiguration,
  ): Unit =
    when (option.optionName) {
      KEY_ENABLED_NAME -> configuration.put(KEY_ENABLED, value.toBoolean())
      KEY_DEBUG_NAME -> configuration.put(KEY_DEBUG, value.toBoolean())
      KEY_GENERATE_ASSISTED_FACTORIES_NAME ->
        configuration.put(KEY_GENERATE_ASSISTED_FACTORIES, value.toBoolean())
      else -> error("Unknown plugin option: ${option.optionName}")
    }
}
