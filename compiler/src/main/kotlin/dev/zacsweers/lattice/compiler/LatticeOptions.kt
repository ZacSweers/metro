/*
 * Copyright (C) 2025 Zac Sweers
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

import dev.zacsweers.lattice.compiler.LatticeOption.entries
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal data class RawLatticeOption<T : Any>(
  val name: String,
  val defaultValue: T,
  val description: String,
  val valueDescription: String,
  val required: Boolean = false,
  val allowMultipleOccurrences: Boolean = false,
  val valueMapper: (String) -> T,
) {
  val key: CompilerConfigurationKey<T> = CompilerConfigurationKey(name)
  val cliOption =
    CliOption(
      optionName = name,
      valueDescription = valueDescription,
      description = description,
      required = required,
      allowMultipleOccurrences = allowMultipleOccurrences,
    )

  fun CompilerConfiguration.put(value: String) {
    put(key, valueMapper(value))
  }

  companion object {
    fun boolean(
      name: String,
      defaultValue: Boolean,
      description: String,
      valueDescription: String,
      required: Boolean = false,
      allowMultipleOccurrences: Boolean = false,
    ) =
      RawLatticeOption(
        name,
        defaultValue,
        description,
        valueDescription,
        required,
        allowMultipleOccurrences,
        String::toBooleanStrict,
      )
  }
}

internal enum class LatticeOption(val raw: RawLatticeOption<*>) {
  DEBUG(
    RawLatticeOption.boolean(
      name = "debug",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Enable/disable debug logging on the given compilation",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  ENABLED(
    RawLatticeOption.boolean(
      name = "enabled",
      defaultValue = true,
      valueDescription = "<true | false>",
      description = "Enable/disable Lattice's plugin on the given compilation",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  GENERATE_ASSISTED_FACTORIES(
    RawLatticeOption.boolean(
      name = "generate-assisted-factories",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Enable/disable automatic generation of assisted factories",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  LOGGING(
    RawLatticeOption<Set<LatticeLogger.Type>>(
      name = "logging",
      defaultValue = emptySet(),
      valueDescription = LatticeLogger.Type.entries.joinToString("|") { it.name },
      description = "Enabled logging types",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence('|').map(LatticeLogger.Type::valueOf).toSet() },
    )
  );

  companion object {
    val entriesByOptionName = entries.associateBy { it.raw.name }
  }
}

public data class LatticeOptions(
  val debug: Boolean = LatticeOption.DEBUG.raw.defaultValue.expectAs(),
  val enabled: Boolean = LatticeOption.ENABLED.raw.defaultValue.expectAs(),
  val generateAssistedFactories: Boolean =
    LatticeOption.GENERATE_ASSISTED_FACTORIES.raw.defaultValue.expectAs(),
  val enabledLoggers: Set<LatticeLogger.Type> = LatticeOption.LOGGING.raw.defaultValue.expectAs(),
) {
  internal companion object {
    fun load(configuration: CompilerConfiguration): LatticeOptions {
      var debug = false
      var enabled = true
      var generateAssistedFactories = false
      val enabledLoggers = mutableSetOf<LatticeLogger.Type>()
      for (entry in LatticeOption.entries) {
        when (entry) {
          LatticeOption.DEBUG -> debug = configuration.getAsBoolean(entry)
          LatticeOption.ENABLED -> enabled = configuration.getAsBoolean(entry)
          LatticeOption.GENERATE_ASSISTED_FACTORIES ->
            generateAssistedFactories = configuration.getAsBoolean(entry)

          LatticeOption.LOGGING -> {
            enabledLoggers +=
              configuration.get(entry.raw.key)?.expectAs<Set<LatticeLogger.Type>>().orEmpty()
          }
        }
      }

      if (debug) {
        enabledLoggers += LatticeLogger.Type.entries
      }

      return LatticeOptions(
        debug = debug,
        enabled = enabled,
        generateAssistedFactories = generateAssistedFactories,
        enabledLoggers = enabledLoggers,
      )
    }

    private fun CompilerConfiguration.getAsBoolean(option: LatticeOption): Boolean {
      @Suppress("UNCHECKED_CAST") val typed = option.raw as RawLatticeOption<Boolean>
      return get(typed.key, typed.defaultValue)
    }
  }
}
