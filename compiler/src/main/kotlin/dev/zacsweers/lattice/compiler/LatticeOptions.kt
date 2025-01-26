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

import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.name.ClassId

internal data class RawLatticeOption<T>(
  val name: String,
  val defaultValue: T,
  val description: String,
  val valueDescription: String,
  val required: Boolean = false,
  val allowMultipleOccurrences: Boolean = false,
  val valueMapper: (String) -> T & Any,
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
  REPORTS_DESTINATION(
    RawLatticeOption<String?>(
      name = "reports-destination",
      defaultValue = null,
      valueDescription = "Path to a directory to dump Lattice reports information",
      description = "Path to a directory to dump Lattice reports information",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it }
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
  ),
  CUSTOM_ASSISTED(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-assisted",
      defaultValue = emptySet(),
      valueDescription = "Assisted annotations",
      description = "Assisted annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_ASSISTED_FACTORY(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-assisted-factory",
      defaultValue = emptySet(),
      valueDescription = "AssistedFactory annotations",
      description = "AssistedFactory annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_ASSISTED_INJECT(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-assisted-inject",
      defaultValue = emptySet(),
      valueDescription = "AssistedInject annotations",
      description = "AssistedInject annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_BINDS(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-binds",
      defaultValue = emptySet(),
      valueDescription = "Binds annotations",
      description = "Binds annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_BINDS_INSTANCE(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-binds-instance",
      defaultValue = emptySet(),
      valueDescription = "BindsInstance annotations",
      description = "BindsInstance annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_CONTRIBUTES_TO(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-contributes-to",
      defaultValue = emptySet(),
      valueDescription = "ContributesTo annotations",
      description = "ContributesTo annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_CONTRIBUTES_BINDING(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-contributes-binding",
      defaultValue = emptySet(),
      valueDescription = "ContributesBinding annotations",
      description = "ContributesBinding annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_ELEMENTS_INTO_SET(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-elements-into-set",
      defaultValue = emptySet(),
      valueDescription = "ElementsIntoSet annotations",
      description = "ElementsIntoSet annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_GRAPH(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-graph",
      defaultValue = emptySet(),
      valueDescription = "Graph annotations",
      description = "Graph annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_GRAPH_FACTORY(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-graph-factory",
      defaultValue = emptySet(),
      valueDescription = "GraphFactory annotations",
      description = "GraphFactory annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_INJECT(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-inject",
      defaultValue = emptySet(),
      valueDescription = "Inject annotations",
      description = "Inject annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_INTO_MAP(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-into-map",
      defaultValue = emptySet(),
      valueDescription = "IntoMap annotations",
      description = "IntoMap annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_INTO_SET(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-into-set",
      defaultValue = emptySet(),
      valueDescription = "IntoSet annotations",
      description = "IntoSet annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_MAP_KEY(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-map-key",
      defaultValue = emptySet(),
      valueDescription = "MapKey annotations",
      description = "MapKey annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_MULTIBINDS(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-multibinds",
      defaultValue = emptySet(),
      valueDescription = "Multibinds annotations",
      description = "Multibinds annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_PROVIDES(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-provides",
      defaultValue = emptySet(),
      valueDescription = "Provides annotations",
      description = "Provides annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_QUALIFIER(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-qualifier",
      defaultValue = emptySet(),
      valueDescription = "Qualifier annotations",
      description = "Qualifier annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_SCOPE(
    RawLatticeOption<Set<ClassId>>(
      name = "custom-scope",
      defaultValue = emptySet(),
      valueDescription = "Scope annotations",
      description = "Scope annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  );

  companion object {
    val entriesByOptionName = entries.associateBy { it.raw.name }
  }
}

public data class LatticeOptions(
  val debug: Boolean = LatticeOption.DEBUG.raw.defaultValue!!.expectAs(),
  val enabled: Boolean = LatticeOption.ENABLED.raw.defaultValue!!.expectAs(),
  val reportsDestination: Path? = LatticeOption.REPORTS_DESTINATION.raw.defaultValue?.expectAs<String>()?.takeUnless(String::isBlank)?.let(Paths::get),
  val generateAssistedFactories: Boolean =
    LatticeOption.GENERATE_ASSISTED_FACTORIES.raw.defaultValue!!.expectAs(),
  val enabledLoggers: Set<LatticeLogger.Type> = LatticeOption.LOGGING.raw.defaultValue!!.expectAs(),
  // Custom annotations
  val customAssistedAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_ASSISTED.raw.defaultValue!!.expectAs(),
  val customAssistedFactoryAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_ASSISTED_FACTORY.raw.defaultValue!!.expectAs(),
  val customAssistedInjectAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_ASSISTED_INJECT.raw.defaultValue!!.expectAs(),
  val customBindsAnnotations: Set<ClassId> = LatticeOption.CUSTOM_BINDS.raw.defaultValue!!.expectAs(),
  val customBindsInstanceAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_BINDS_INSTANCE.raw.defaultValue!!.expectAs(),
  val customContributesToAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_CONTRIBUTES_TO.raw.defaultValue!!.expectAs(),
  val customContributesBindingAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_CONTRIBUTES_BINDING.raw.defaultValue!!.expectAs(),
  val customElementsIntoSetAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_ELEMENTS_INTO_SET.raw.defaultValue!!.expectAs(),
  val customGraphAnnotations: Set<ClassId> = LatticeOption.CUSTOM_GRAPH.raw.defaultValue!!.expectAs(),
  val customGraphFactoryAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_GRAPH_FACTORY.raw.defaultValue!!.expectAs(),
  val customInjectAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_INJECT.raw.defaultValue!!.expectAs(),
  val customIntoMapAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_INTO_MAP.raw.defaultValue!!.expectAs(),
  val customIntoSetAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_INTO_SET.raw.defaultValue!!.expectAs(),
  val customMapKeyAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_MAP_KEY.raw.defaultValue!!.expectAs(),
  val customMultibindsAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_MULTIBINDS.raw.defaultValue!!.expectAs(),
  val customProvidesAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_PROVIDES.raw.defaultValue!!.expectAs(),
  val customQualifierAnnotations: Set<ClassId> =
    LatticeOption.CUSTOM_QUALIFIER.raw.defaultValue!!.expectAs(),
  val customScopeAnnotations: Set<ClassId> = LatticeOption.CUSTOM_SCOPE.raw.defaultValue!!.expectAs(),
) {
  internal companion object {
    fun load(configuration: CompilerConfiguration): LatticeOptions {
      var options = LatticeOptions()
      val enabledLoggers = mutableSetOf<LatticeLogger.Type>()

      // Custom annotations
      val customAssistedAnnotations = mutableSetOf<ClassId>()
      val customAssistedFactoryAnnotations = mutableSetOf<ClassId>()
      val customAssistedInjectAnnotations = mutableSetOf<ClassId>()
      val customBindsAnnotations = mutableSetOf<ClassId>()
      val customBindsInstanceAnnotations = mutableSetOf<ClassId>()
      val customContributesToAnnotations = mutableSetOf<ClassId>()
      val customContributesBindingAnnotations = mutableSetOf<ClassId>()
      val customElementsIntoSetAnnotations = mutableSetOf<ClassId>()
      val customGraphAnnotations = mutableSetOf<ClassId>()
      val customGraphFactoryAnnotations = mutableSetOf<ClassId>()
      val customInjectAnnotations = mutableSetOf<ClassId>()
      val customIntoMapAnnotations = mutableSetOf<ClassId>()
      val customIntoSetAnnotations = mutableSetOf<ClassId>()
      val customMapKeyAnnotations = mutableSetOf<ClassId>()
      val customMultibindsAnnotations = mutableSetOf<ClassId>()
      val customProvidesAnnotations = mutableSetOf<ClassId>()
      val customQualifierAnnotations = mutableSetOf<ClassId>()
      val customScopeAnnotations = mutableSetOf<ClassId>()

      for (entry in LatticeOption.entries) {
        when (entry) {
          LatticeOption.DEBUG -> options = options.copy(debug = configuration.getAsBoolean(entry))
          LatticeOption.ENABLED ->
            options = options.copy(enabled = configuration.getAsBoolean(entry))
          LatticeOption.REPORTS_DESTINATION -> {
            @Suppress("UNCHECKED_CAST")
            options = options.copy(reportsDestination = configuration.get<String>(entry.raw.key as CompilerConfigurationKey<String>, "").takeUnless(String::isBlank)?.let(Paths::get))
          }
          LatticeOption.GENERATE_ASSISTED_FACTORIES ->
            options = options.copy(generateAssistedFactories = configuration.getAsBoolean(entry))
          LatticeOption.LOGGING -> {
            enabledLoggers +=
              configuration.get(entry.raw.key)?.expectAs<Set<LatticeLogger.Type>>().orEmpty()
          }

          // Custom annotations
          LatticeOption.CUSTOM_ASSISTED ->
            customAssistedAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_ASSISTED_FACTORY ->
            customAssistedFactoryAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_ASSISTED_INJECT ->
            customAssistedInjectAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_BINDS ->
            customBindsAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_BINDS_INSTANCE ->
            customBindsInstanceAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_CONTRIBUTES_TO ->
            customContributesToAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_CONTRIBUTES_BINDING ->
            customContributesBindingAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_ELEMENTS_INTO_SET ->
            customElementsIntoSetAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_GRAPH ->
            customGraphAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_GRAPH_FACTORY ->
            customGraphFactoryAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_INJECT ->
            customInjectAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_INTO_MAP ->
            customIntoMapAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_INTO_SET ->
            customIntoSetAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_MAP_KEY ->
            customMapKeyAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_MULTIBINDS ->
            customMultibindsAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_PROVIDES ->
            customProvidesAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_QUALIFIER ->
            customQualifierAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
          LatticeOption.CUSTOM_SCOPE ->
            customScopeAnnotations.addAll(configuration.getAsSet<ClassId>(entry))
        }
      }

      if (options.debug) {
        enabledLoggers += LatticeLogger.Type.entries
      }
      options = options.copy(enabledLoggers = enabledLoggers)

      options =
        options.copy(
          customAssistedAnnotations = customAssistedAnnotations,
          customAssistedFactoryAnnotations = customAssistedFactoryAnnotations,
          customAssistedInjectAnnotations = customAssistedInjectAnnotations,
          customBindsAnnotations = customBindsAnnotations,
          customBindsInstanceAnnotations = customBindsInstanceAnnotations,
          customContributesToAnnotations = customContributesToAnnotations,
          customContributesBindingAnnotations = customContributesBindingAnnotations,
          customElementsIntoSetAnnotations = customElementsIntoSetAnnotations,
          customGraphAnnotations = customGraphAnnotations,
          customGraphFactoryAnnotations = customGraphFactoryAnnotations,
          customInjectAnnotations = customInjectAnnotations,
          customIntoMapAnnotations = customIntoMapAnnotations,
          customIntoSetAnnotations = customIntoSetAnnotations,
          customMapKeyAnnotations = customMapKeyAnnotations,
          customMultibindsAnnotations = customMultibindsAnnotations,
          customProvidesAnnotations = customProvidesAnnotations,
          customQualifierAnnotations = customQualifierAnnotations,
          customScopeAnnotations = customScopeAnnotations,
        )

      return options
    }

    private fun CompilerConfiguration.getAsBoolean(option: LatticeOption): Boolean {
      @Suppress("UNCHECKED_CAST") val typed = option.raw as RawLatticeOption<Boolean>
      return get(typed.key, typed.defaultValue)
    }

    private fun <E> CompilerConfiguration.getAsSet(option: LatticeOption): Set<E> {
      @Suppress("UNCHECKED_CAST") val typed = option.raw as RawLatticeOption<Set<E>>
      return get(typed.key, typed.defaultValue)
    }
  }
}
