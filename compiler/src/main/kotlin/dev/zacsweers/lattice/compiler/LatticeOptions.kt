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

import dev.zacsweers.lattice.compiler.fir.generators.DependencyGraphFirGenerator
import dev.zacsweers.lattice.compiler.fir.generators.GraphFactoryFirSupertypeGenerator
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
  /**
   * If true, graph class companion objects will implement graph factory interfaces.
   *
   * This is gated at the moment because it seems that enabling [GraphFactoryFirSupertypeGenerator]
   * causes a bug? in FIR that results in all supertype callables to be unresolved during
   * [DependencyGraphFirGenerator.getCallableNamesForClass]. This breaks our ability to detect the
   * SAM function for the factory interface and generate an override declaration.
   *
   * When this mode is disabled, the companion object will still have the SAM function generated but
   * it will _not_ be an override of the factory. Instead, it will just have an identical signature
   * and call through to the generated [LatticeSymbols.Names.latticeImpl] class for the factory
   * under the hood.
   */
  MAKE_EXISTING_COMPANIONS_IMPLEMENT_GRAPH_FACTORIES(
    RawLatticeOption.boolean(
      name = "make-existing-companions-implement-graph-factories",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Enable/disable making existing graph class companion objects implement their graph factories (if they are interfaces).",
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
    RawLatticeOption(
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
  val makeExistingCompanionsImplementGraphFactories: Boolean =
    LatticeOption.MAKE_EXISTING_COMPANIONS_IMPLEMENT_GRAPH_FACTORIES.raw.defaultValue.expectAs(),
) {
  internal companion object {
    fun load(configuration: CompilerConfiguration): LatticeOptions {
      var options = LatticeOptions()
      val enabledLoggers = mutableSetOf<LatticeLogger.Type>()
      for (entry in LatticeOption.entries) {
        when (entry) {
          LatticeOption.DEBUG -> options = options.copy(debug = configuration.getAsBoolean(entry))
          LatticeOption.ENABLED ->
            options = options.copy(enabled = configuration.getAsBoolean(entry))
          LatticeOption.GENERATE_ASSISTED_FACTORIES ->
            options = options.copy(generateAssistedFactories = configuration.getAsBoolean(entry))

          LatticeOption.LOGGING -> {
            enabledLoggers +=
              configuration.get(entry.raw.key)?.expectAs<Set<LatticeLogger.Type>>().orEmpty()
          }

          LatticeOption.MAKE_EXISTING_COMPANIONS_IMPLEMENT_GRAPH_FACTORIES -> {
            options =
              options.copy(
                makeExistingCompanionsImplementGraphFactories = configuration.getAsBoolean(entry)
              )
          }
        }
      }

      if (options.debug) {
        enabledLoggers += LatticeLogger.Type.entries
      }
      options = options.copy(enabledLoggers = enabledLoggers)

      return options
    }

    private fun CompilerConfiguration.getAsBoolean(option: LatticeOption): Boolean {
      @Suppress("UNCHECKED_CAST") val typed = option.raw as RawLatticeOption<Boolean>
      return get(typed.key, typed.defaultValue)
    }
  }
}
