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
package dev.zacsweers.metro.compiler

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.name.ClassId

internal data class RawMetroOption<T : Any>(
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
      RawMetroOption(
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

internal enum class MetroOption(val raw: RawMetroOption<*>) {
  DEBUG(
    RawMetroOption.boolean(
      name = "debug",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Enable/disable debug logging on the given compilation",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  ENABLED(
    RawMetroOption.boolean(
      name = "enabled",
      defaultValue = true,
      valueDescription = "<true | false>",
      description = "Enable/disable Metro's plugin on the given compilation",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  REPORTS_DESTINATION(
    RawMetroOption(
      name = "reports-destination",
      defaultValue = "",
      valueDescription = "Path to a directory to dump Metro reports information",
      description = "Path to a directory to dump Metro reports information",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it },
    )
  ),
  GENERATE_ASSISTED_FACTORIES(
    RawMetroOption.boolean(
      name = "generate-assisted-factories",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Enable/disable automatic generation of assisted factories",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  PUBLIC_PROVIDER_SEVERITY(
    RawMetroOption(
      name = "public-provider-severity",
      defaultValue = MetroOptions.DiagnosticSeverity.NONE.name,
      valueDescription = "NONE|WARN|ERROR",
      description = "Control diagnostic severity reporting of public providers",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it },
    )
  ),
  LOGGING(
    RawMetroOption(
      name = "logging",
      defaultValue = emptySet(),
      valueDescription = MetroLogger.Type.entries.joinToString("|") { it.name },
      description = "Enabled logging types",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence('|').map(MetroLogger.Type::valueOf).toSet() },
    )
  ),
  CUSTOM_ASSISTED(
    RawMetroOption(
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
    RawMetroOption(
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
    RawMetroOption(
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
    RawMetroOption(
      name = "custom-binds",
      defaultValue = emptySet(),
      valueDescription = "Binds annotations",
      description = "Binds annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_CONTRIBUTES_TO(
    RawMetroOption(
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
    RawMetroOption(
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
    RawMetroOption(
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
    RawMetroOption(
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
    RawMetroOption(
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
    RawMetroOption(
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
    RawMetroOption(
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
    RawMetroOption(
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
    RawMetroOption(
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
    RawMetroOption(
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
    RawMetroOption(
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
    RawMetroOption(
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
    RawMetroOption(
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

public data class MetroOptions(
  val debug: Boolean = MetroOption.DEBUG.raw.defaultValue.expectAs(),
  val enabled: Boolean = MetroOption.ENABLED.raw.defaultValue.expectAs(),
  val reportsDestination: Path? =
    MetroOption.REPORTS_DESTINATION.raw.defaultValue
      .expectAs<String>()
      .takeUnless(String::isBlank)
      ?.let(Paths::get),
  val generateAssistedFactories: Boolean =
    MetroOption.GENERATE_ASSISTED_FACTORIES.raw.defaultValue.expectAs(),
  val publicProviderSeverity: DiagnosticSeverity =
    MetroOption.PUBLIC_PROVIDER_SEVERITY.raw.defaultValue.expectAs<String>().let {
      DiagnosticSeverity.valueOf(it)
    },
  val enabledLoggers: Set<MetroLogger.Type> = MetroOption.LOGGING.raw.defaultValue.expectAs(),
  // Custom annotations
  val customAssistedAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_ASSISTED.raw.defaultValue.expectAs(),
  val customAssistedFactoryAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_ASSISTED_FACTORY.raw.defaultValue.expectAs(),
  val customAssistedInjectAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_ASSISTED_INJECT.raw.defaultValue.expectAs(),
  val customBindsAnnotations: Set<ClassId> = MetroOption.CUSTOM_BINDS.raw.defaultValue.expectAs(),
  val customContributesToAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_CONTRIBUTES_TO.raw.defaultValue.expectAs(),
  val customContributesBindingAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_CONTRIBUTES_BINDING.raw.defaultValue.expectAs(),
  val customElementsIntoSetAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_ELEMENTS_INTO_SET.raw.defaultValue.expectAs(),
  val customGraphAnnotations: Set<ClassId> = MetroOption.CUSTOM_GRAPH.raw.defaultValue.expectAs(),
  val customGraphFactoryAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_GRAPH_FACTORY.raw.defaultValue.expectAs(),
  val customInjectAnnotations: Set<ClassId> = MetroOption.CUSTOM_INJECT.raw.defaultValue.expectAs(),
  val customIntoMapAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_INTO_MAP.raw.defaultValue.expectAs(),
  val customIntoSetAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_INTO_SET.raw.defaultValue.expectAs(),
  val customMapKeyAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_MAP_KEY.raw.defaultValue.expectAs(),
  val customMultibindsAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_MULTIBINDS.raw.defaultValue.expectAs(),
  val customProvidesAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_PROVIDES.raw.defaultValue.expectAs(),
  val customQualifierAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_QUALIFIER.raw.defaultValue.expectAs(),
  val customScopeAnnotations: Set<ClassId> = MetroOption.CUSTOM_SCOPE.raw.defaultValue.expectAs(),
) {
  internal companion object {
    fun load(configuration: CompilerConfiguration): MetroOptions {
      var options = MetroOptions()
      val enabledLoggers = mutableSetOf<MetroLogger.Type>()

      // Custom annotations
      val customAssistedAnnotations = mutableSetOf<ClassId>()
      val customAssistedFactoryAnnotations = mutableSetOf<ClassId>()
      val customAssistedInjectAnnotations = mutableSetOf<ClassId>()
      val customBindsAnnotations = mutableSetOf<ClassId>()
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

      for (entry in MetroOption.entries) {
        when (entry) {
          MetroOption.DEBUG -> options = options.copy(debug = configuration.getAsBoolean(entry))
          MetroOption.ENABLED -> options = options.copy(enabled = configuration.getAsBoolean(entry))
          MetroOption.REPORTS_DESTINATION -> {
            options =
              options.copy(
                reportsDestination =
                  configuration.getAsString(entry).takeUnless(String::isBlank)?.let(Paths::get)
              )
          }
          MetroOption.GENERATE_ASSISTED_FACTORIES ->
            options = options.copy(generateAssistedFactories = configuration.getAsBoolean(entry))

          MetroOption.PUBLIC_PROVIDER_SEVERITY ->
            options =
              options.copy(
                publicProviderSeverity =
                  configuration.getAsString(entry).let {
                    DiagnosticSeverity.valueOf(it.uppercase(Locale.US))
                  }
              )
          MetroOption.LOGGING -> {
            enabledLoggers +=
              configuration.get(entry.raw.key)?.expectAs<Set<MetroLogger.Type>>().orEmpty()
          }

          // Custom annotations
          MetroOption.CUSTOM_ASSISTED ->
            customAssistedAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_ASSISTED_FACTORY ->
            customAssistedFactoryAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_ASSISTED_INJECT ->
            customAssistedInjectAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_BINDS -> customBindsAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_CONTRIBUTES_TO ->
            customContributesToAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_CONTRIBUTES_BINDING ->
            customContributesBindingAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_ELEMENTS_INTO_SET ->
            customElementsIntoSetAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_GRAPH -> customGraphAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_GRAPH_FACTORY ->
            customGraphFactoryAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_INJECT -> customInjectAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_INTO_MAP ->
            customIntoMapAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_INTO_SET ->
            customIntoSetAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_MAP_KEY ->
            customMapKeyAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_MULTIBINDS ->
            customMultibindsAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_PROVIDES ->
            customProvidesAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_QUALIFIER ->
            customQualifierAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_SCOPE -> customScopeAnnotations.addAll(configuration.getAsSet(entry))
        }
      }

      if (options.debug) {
        enabledLoggers += MetroLogger.Type.entries
      }
      options = options.copy(enabledLoggers = enabledLoggers)

      options =
        options.copy(
          customAssistedAnnotations = customAssistedAnnotations,
          customAssistedFactoryAnnotations = customAssistedFactoryAnnotations,
          customAssistedInjectAnnotations = customAssistedInjectAnnotations,
          customBindsAnnotations = customBindsAnnotations,
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

    private fun CompilerConfiguration.getAsString(option: MetroOption): String {
      @Suppress("UNCHECKED_CAST") val typed = option.raw as RawMetroOption<String>
      return get(typed.key, typed.defaultValue.orEmpty())
    }

    private fun CompilerConfiguration.getAsBoolean(option: MetroOption): Boolean {
      @Suppress("UNCHECKED_CAST") val typed = option.raw as RawMetroOption<Boolean>
      return get(typed.key, typed.defaultValue)
    }

    private fun <E> CompilerConfiguration.getAsSet(option: MetroOption): Set<E> {
      @Suppress("UNCHECKED_CAST") val typed = option.raw as RawMetroOption<Set<E>>
      return get(typed.key, typed.defaultValue)
    }
  }

  public enum class DiagnosticSeverity {
    NONE,
    WARN,
    ERROR,
  }
}
