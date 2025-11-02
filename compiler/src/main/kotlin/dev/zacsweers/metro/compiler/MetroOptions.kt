// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

// Borrowed from Dagger
// https://github.com/google/dagger/blob/b39cf2d0640e4b24338dd290cb1cb2e923d38cb3/dagger-compiler/main/java/dagger/internal/codegen/writing/ComponentImplementation.java#L263
internal const val DEFAULT_STATEMENTS_PER_INIT_FUN = 25

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
  ENABLE_TOP_LEVEL_FUNCTION_INJECTION(
    RawMetroOption.boolean(
      name = "enable-top-level-function-injection",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Enable/disable top-level function injection. Note this is disabled by default because this is not compatible with incremental compilation yet.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  ENABLE_DAGGER_RUNTIME_INTEROP(
    RawMetroOption.boolean(
      name = "enable-dagger-runtime-interop",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Enable/disable interop with Dagger's runtime (Provider, Lazy, and generated Dagger factories).",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  GENERATE_CONTRIBUTION_HINTS(
    RawMetroOption.boolean(
      name = "generate-contribution-hints",
      defaultValue = true,
      valueDescription = "<true | false>",
      description = "Enable/disable generation of contribution hints.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  GENERATE_JVM_CONTRIBUTION_HINTS_IN_FIR(
    RawMetroOption.boolean(
      name = "generate-jvm-contribution-hints-in-fir",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Enable/disable generation of contribution hint generation in FIR for JVM compilations types.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  TRANSFORM_PROVIDERS_TO_PRIVATE(
    RawMetroOption.boolean(
      name = "transform-providers-to-private",
      defaultValue = true,
      valueDescription = "<true | false>",
      description = "Enable/disable automatic transformation of providers to be private.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  SHRINK_UNUSED_BINDINGS(
    RawMetroOption.boolean(
      name = "shrink-unused-bindings",
      defaultValue = true,
      valueDescription = "<true | false>",
      description = "Enable/disable shrinking of unused bindings from binding graphs.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  CHUNK_FIELD_INITS(
    RawMetroOption.boolean(
      name = "chunk-field-inits",
      defaultValue = true,
      valueDescription = "<true | false>",
      description = "Enable/disable chunking of field initializers in binding graphs.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  STATEMENTS_PER_INIT_FUN(
    RawMetroOption(
      name = "statements-per-init-fun",
      defaultValue = DEFAULT_STATEMENTS_PER_INIT_FUN,
      valueDescription = "<count>",
      description =
        "Maximum number of statements per init method when chunking field initializers. Default is $DEFAULT_STATEMENTS_PER_INIT_FUN, must be > 0.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.toInt() },
    )
  ),
  PUBLIC_PROVIDER_SEVERITY(
    RawMetroOption(
      name = "public-provider-severity",
      defaultValue = MetroOptions.DiagnosticSeverity.NONE.name,
      valueDescription = "NONE|WARN|ERROR",
      description =
        "Control diagnostic severity reporting of public providers. Only applies if `transform-providers-to-private` is false.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it },
    )
  ),
  WARN_ON_INJECT_ANNOTATION_PLACEMENT(
    RawMetroOption.boolean(
      name = "warn-on-inject-annotation-placement",
      defaultValue = true,
      valueDescription = "<true | false>",
      description =
        "Enable/disable suggestion to lift @Inject/@AssistedInject to class when there is only one constructor.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY(
    RawMetroOption(
      name = "interop-annotations-named-arg-severity",
      defaultValue = MetroOptions.DiagnosticSeverity.NONE.name,
      valueDescription = "NONE|WARN|ERROR",
      description =
        "Control diagnostic severity reporting of interop annotations using positional arguments instead of named arguments.",
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
  MAX_IR_ERRORS_COUNT(
    RawMetroOption(
      name = "max-ir-errors-count",
      defaultValue = 20,
      valueDescription = "<count>",
      description =
        "Maximum number of errors to report before exiting IR processing. Default is 20, must be > 0.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.toInt() },
    )
  ),
  CUSTOM_PROVIDER(
    RawMetroOption(
      name = "custom-provider",
      defaultValue = emptySet(),
      valueDescription = "Provider types",
      description = "Provider types",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_LAZY(
    RawMetroOption(
      name = "custom-lazy",
      defaultValue = emptySet(),
      valueDescription = "Lazy types",
      description = "Lazy types",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
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
  CUSTOM_CONTRIBUTES_INTO_SET(
    RawMetroOption(
      name = "custom-contributes-into-set",
      defaultValue = emptySet(),
      valueDescription = "ContributesIntoSet annotations",
      description = "ContributesIntoSet annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_GRAPH_EXTENSION(
    RawMetroOption(
      name = "custom-graph-extension",
      defaultValue = emptySet(),
      valueDescription = "GraphExtension annotations",
      description = "GraphExtension annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_GRAPH_EXTENSION_FACTORY(
    RawMetroOption(
      name = "custom-graph-extension-factory",
      defaultValue = emptySet(),
      valueDescription = "GraphExtension.Factory annotations",
      description = "GraphExtension.Factory annotations",
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
  CUSTOM_DEPENDENCY_GRAPH(
    RawMetroOption(
      name = "custom-dependency-graph",
      defaultValue = emptySet(),
      valueDescription = "Graph annotations",
      description = "Graph annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_DEPENDENCY_GRAPH_FACTORY(
    RawMetroOption(
      name = "custom-dependency-graph-factory",
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
  ),
  CUSTOM_BINDING_CONTAINER(
    RawMetroOption(
      name = "custom-binding-container",
      defaultValue = emptySet(),
      valueDescription = "BindingContainer annotations",
      description = "BindingContainer annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  ENABLE_DAGGER_ANVIL_INTEROP(
    RawMetroOption.boolean(
      name = "enable-dagger-anvil-interop",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Enable/disable interop with Dagger Anvil's additional functionality (currently for 'rank' support).",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  ENABLE_FULL_BINDING_GRAPH_VALIDATION(
    RawMetroOption.boolean(
      name = "enable-full-binding-graph-validation",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Enable/disable full validation of all binds and provides declarations, even if they are unused.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE(
    RawMetroOption.boolean(
      name = "enable-graph-impl-class-as-return-type",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "If true changes the return type of generated Graph Factories from the declared interface type to the generated Metro graph type. This is helpful for Dagger/Anvil interop.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  CUSTOM_ORIGIN(
    RawMetroOption(
      name = "custom-origin",
      defaultValue = emptySet(),
      valueDescription = "Origin annotations",
      description =
        "Custom annotations that indicate the origin class of generated types for contribution merging",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  CUSTOM_OPTIONAL_BINDING(
    RawMetroOption(
      name = "custom-optional-binding",
      defaultValue = emptySet(),
      valueDescription = "OptionalBinding annotations",
      description = "OptionalBinding annotations",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.splitToSequence(':').mapToSet { ClassId.fromString(it, false) } },
    )
  ),
  OPTIONAL_BINDING_BEHAVIOR(
    RawMetroOption(
      name = "optional-binding-behavior",
      defaultValue = OptionalBindingBehavior.DEFAULT.name,
      valueDescription = OptionalBindingBehavior.entries.joinToString("|"),
      description = "Controls the behavior of optional bindings",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it },
    )
  ),
  CONTRIBUTES_AS_INJECT(
    RawMetroOption.boolean(
      name = "contributes-as-inject",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "If enabled, treats `@Contributes*` annotations (except ContributesTo) as implicit `@Inject` annotations",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_INCLUDE_JAVAX_ANNOTATIONS(
    RawMetroOption.boolean(
      name = "interop-include-javax-annotations",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Interop with javax annotations",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_INCLUDE_JAKARTA_ANNOTATIONS(
    RawMetroOption.boolean(
      name = "interop-include-jakarta-annotations",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Interop with jakarta annotations",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_INCLUDE_DAGGER_ANNOTATIONS(
    RawMetroOption.boolean(
      name = "interop-include-dagger-annotations",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Interop with Dagger annotations (automatically includes javax and jakarta annotations)",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_INCLUDE_KOTLIN_INJECT_ANNOTATIONS(
    RawMetroOption.boolean(
      name = "interop-include-kotlin-inject-annotations",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Interop with kotlin-inject annotations",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_INCLUDE_ANVIL_ANNOTATIONS(
    RawMetroOption.boolean(
      name = "interop-include-anvil-annotations",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Interop with Anvil annotations (automatically includes Dagger annotations)",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_INCLUDE_KOTLIN_INJECT_ANVIL_ANNOTATIONS(
    RawMetroOption.boolean(
      name = "interop-include-kotlin-inject-anvil-annotations",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Interop with kotlin-inject Anvil annotations (automatically includes kotlin-inject annotations)",
      required = false,
      allowMultipleOccurrences = false,
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
  val enableTopLevelFunctionInjection: Boolean =
    MetroOption.ENABLE_TOP_LEVEL_FUNCTION_INJECTION.raw.defaultValue.expectAs(),
  val generateContributionHints: Boolean =
    MetroOption.GENERATE_CONTRIBUTION_HINTS.raw.defaultValue.expectAs(),
  val generateJvmContributionHintsInFir: Boolean =
    MetroOption.GENERATE_JVM_CONTRIBUTION_HINTS_IN_FIR.raw.defaultValue.expectAs(),
  val transformProvidersToPrivate: Boolean =
    MetroOption.TRANSFORM_PROVIDERS_TO_PRIVATE.raw.defaultValue.expectAs(),
  val shrinkUnusedBindings: Boolean =
    MetroOption.SHRINK_UNUSED_BINDINGS.raw.defaultValue.expectAs(),
  val chunkFieldInits: Boolean = MetroOption.CHUNK_FIELD_INITS.raw.defaultValue.expectAs(),
  val statementsPerInitFun: Int = MetroOption.STATEMENTS_PER_INIT_FUN.raw.defaultValue.expectAs(),
  val publicProviderSeverity: DiagnosticSeverity =
    if (transformProvidersToPrivate) {
      DiagnosticSeverity.NONE
    } else {
      MetroOption.PUBLIC_PROVIDER_SEVERITY.raw.defaultValue.expectAs<String>().let {
        DiagnosticSeverity.valueOf(it)
      }
    },
  val optionalBindingBehavior: OptionalBindingBehavior =
    MetroOption.OPTIONAL_BINDING_BEHAVIOR.raw.defaultValue.expectAs<String>().let { rawValue ->
      val adjusted =
        rawValue.uppercase(Locale.US).let {
          // temporary cover for deprecated entry
          if (it == "REQUIRE_OPTIONAL_DEPENDENCY") {
            "REQUIRE_OPTIONAL_BINDING"
          } else {
            it
          }
        }
      OptionalBindingBehavior.valueOf(adjusted)
    },
  val warnOnInjectAnnotationPlacement: Boolean =
    MetroOption.WARN_ON_INJECT_ANNOTATION_PLACEMENT.raw.defaultValue.expectAs(),
  val interopAnnotationsNamedArgSeverity: DiagnosticSeverity =
    MetroOption.INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY.raw.defaultValue.expectAs<String>().let {
      DiagnosticSeverity.valueOf(it)
    },
  val enabledLoggers: Set<MetroLogger.Type> =
    if (debug) {
      // Debug enables _all_
      MetroLogger.Type.entries.filterNot { it == MetroLogger.Type.None }.toSet()
    } else {
      MetroOption.LOGGING.raw.defaultValue.expectAs()
    },
  val enableDaggerRuntimeInterop: Boolean =
    MetroOption.ENABLE_DAGGER_RUNTIME_INTEROP.raw.defaultValue.expectAs(),
  val maxIrErrorsCount: Int = MetroOption.MAX_IR_ERRORS_COUNT.raw.defaultValue.expectAs(),
  // Intrinsics
  val customProviderTypes: Set<ClassId> = MetroOption.CUSTOM_PROVIDER.raw.defaultValue.expectAs(),
  val customLazyTypes: Set<ClassId> = MetroOption.CUSTOM_LAZY.raw.defaultValue.expectAs(),
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
  val customContributesIntoSetAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_CONTRIBUTES_INTO_SET.raw.defaultValue.expectAs(),
  val customGraphExtensionAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_GRAPH_EXTENSION.raw.defaultValue.expectAs(),
  val customGraphExtensionFactoryAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_GRAPH_EXTENSION_FACTORY.raw.defaultValue.expectAs(),
  val customElementsIntoSetAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_ELEMENTS_INTO_SET.raw.defaultValue.expectAs(),
  val customGraphAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_DEPENDENCY_GRAPH.raw.defaultValue.expectAs(),
  val customGraphFactoryAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_DEPENDENCY_GRAPH_FACTORY.raw.defaultValue.expectAs(),
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
  val customBindingContainerAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_BINDING_CONTAINER.raw.defaultValue.expectAs(),
  val enableDaggerAnvilInterop: Boolean =
    MetroOption.ENABLE_DAGGER_ANVIL_INTEROP.raw.defaultValue.expectAs(),
  val enableFullBindingGraphValidation: Boolean =
    MetroOption.ENABLE_FULL_BINDING_GRAPH_VALIDATION.raw.defaultValue.expectAs(),
  val enableGraphImplClassAsReturnType: Boolean =
    MetroOption.ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE.raw.defaultValue.expectAs(),
  val customOriginAnnotations: Set<ClassId> = MetroOption.CUSTOM_ORIGIN.raw.defaultValue.expectAs(),
  val customOptionalBindingAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_OPTIONAL_BINDING.raw.defaultValue.expectAs(),
  val contributesAsInject: Boolean = MetroOption.CONTRIBUTES_AS_INJECT.raw.defaultValue.expectAs(),
) {
  public fun toBuilder(): Builder = Builder(this)

  public class Builder(base: MetroOptions = MetroOptions()) {
    public var debug: Boolean = base.debug
    public var enabled: Boolean = base.enabled
    public var reportsDestination: Path? = base.reportsDestination
    public var generateAssistedFactories: Boolean = base.generateAssistedFactories
    public var enableTopLevelFunctionInjection: Boolean = base.enableTopLevelFunctionInjection
    public var generateContributionHints: Boolean = base.generateContributionHints
    public var generateJvmContributionHintsInFir: Boolean = base.generateJvmContributionHintsInFir
    public var transformProvidersToPrivate: Boolean = base.transformProvidersToPrivate
    public var shrinkUnusedBindings: Boolean = base.shrinkUnusedBindings
    public var chunkFieldInits: Boolean = base.chunkFieldInits
    public var statementsPerInitFun: Int = base.statementsPerInitFun
    public var publicProviderSeverity: DiagnosticSeverity = base.publicProviderSeverity
    public var optionalBindingBehavior: OptionalBindingBehavior = base.optionalBindingBehavior
    public var warnOnInjectAnnotationPlacement: Boolean = base.warnOnInjectAnnotationPlacement
    public var interopAnnotationsNamedArgSeverity: DiagnosticSeverity =
      base.interopAnnotationsNamedArgSeverity
    public var enabledLoggers: MutableSet<MetroLogger.Type> = base.enabledLoggers.toMutableSet()
    public var enableDaggerRuntimeInterop: Boolean = base.enableDaggerRuntimeInterop
    public var maxIrErrorsCount: Int = base.maxIrErrorsCount
    public var customProviderTypes: MutableSet<ClassId> = base.customProviderTypes.toMutableSet()
    public var customLazyTypes: MutableSet<ClassId> = base.customLazyTypes.toMutableSet()
    public var customAssistedAnnotations: MutableSet<ClassId> =
      base.customAssistedAnnotations.toMutableSet()
    public var customAssistedFactoryAnnotations: MutableSet<ClassId> =
      base.customAssistedFactoryAnnotations.toMutableSet()
    public var customAssistedInjectAnnotations: MutableSet<ClassId> =
      base.customAssistedInjectAnnotations.toMutableSet()
    public var customBindsAnnotations: MutableSet<ClassId> =
      base.customBindsAnnotations.toMutableSet()
    public var customContributesToAnnotations: MutableSet<ClassId> =
      base.customContributesToAnnotations.toMutableSet()
    public var customContributesBindingAnnotations: MutableSet<ClassId> =
      base.customContributesBindingAnnotations.toMutableSet()
    public var customContributesIntoSetAnnotations: MutableSet<ClassId> =
      base.customContributesIntoSetAnnotations.toMutableSet()
    public var customGraphExtensionAnnotations: MutableSet<ClassId> =
      base.customGraphExtensionAnnotations.toMutableSet()
    public var customGraphExtensionFactoryAnnotations: MutableSet<ClassId> =
      base.customGraphExtensionFactoryAnnotations.toMutableSet()
    public var customElementsIntoSetAnnotations: MutableSet<ClassId> =
      base.customElementsIntoSetAnnotations.toMutableSet()
    public var customGraphAnnotations: MutableSet<ClassId> =
      base.customGraphAnnotations.toMutableSet()
    public var customGraphFactoryAnnotations: MutableSet<ClassId> =
      base.customGraphFactoryAnnotations.toMutableSet()
    public var customInjectAnnotations: MutableSet<ClassId> =
      base.customInjectAnnotations.toMutableSet()
    public var customIntoMapAnnotations: MutableSet<ClassId> =
      base.customIntoMapAnnotations.toMutableSet()
    public var customIntoSetAnnotations: MutableSet<ClassId> =
      base.customIntoSetAnnotations.toMutableSet()
    public var customMapKeyAnnotations: MutableSet<ClassId> =
      base.customMapKeyAnnotations.toMutableSet()
    public var customMultibindsAnnotations: MutableSet<ClassId> =
      base.customMultibindsAnnotations.toMutableSet()
    public var customProvidesAnnotations: MutableSet<ClassId> =
      base.customProvidesAnnotations.toMutableSet()
    public var customQualifierAnnotations: MutableSet<ClassId> =
      base.customQualifierAnnotations.toMutableSet()
    public var customScopeAnnotations: MutableSet<ClassId> =
      base.customScopeAnnotations.toMutableSet()
    public var customBindingContainerAnnotations: MutableSet<ClassId> =
      base.customBindingContainerAnnotations.toMutableSet()
    public var enableDaggerAnvilInterop: Boolean = base.enableDaggerAnvilInterop
    public var enableFullBindingGraphValidation: Boolean = base.enableFullBindingGraphValidation
    public var enableGraphImplClassAsReturnType: Boolean = base.enableGraphImplClassAsReturnType
    public var customOriginAnnotations: MutableSet<ClassId> =
      base.customOriginAnnotations.toMutableSet()
    public var customOptionalBindingAnnotations: MutableSet<ClassId> =
      base.customOptionalBindingAnnotations.toMutableSet()
    public var contributesAsInject: Boolean = base.contributesAsInject

    private fun FqName.classId(name: String): ClassId {
      return ClassId(this, Name.identifier(name))
    }

    public fun includeJavaxAnnotations() {
      customProviderTypes.add(javaxInjectPackage.classId("Provider"))
      customInjectAnnotations.add(javaxInjectPackage.classId("Inject"))
      customQualifierAnnotations.add(javaxInjectPackage.classId("Qualifier"))
      customScopeAnnotations.add(javaxInjectPackage.classId("Scope"))
    }

    public fun includeJakartaAnnotations() {
      customProviderTypes.add(jakartaInjectPackage.classId("Provider"))
      customInjectAnnotations.add(jakartaInjectPackage.classId("Inject"))
      customQualifierAnnotations.add(jakartaInjectPackage.classId("Qualifier"))
      customScopeAnnotations.add(jakartaInjectPackage.classId("Scope"))
    }

    public fun includeDaggerAnnotations() {
      enableDaggerRuntimeInterop = true
      // Assisted inject
      customAssistedAnnotations.add(daggerAssistedPackage.classId("Assisted"))
      customAssistedFactoryAnnotations.add(daggerAssistedPackage.classId("AssistedFactory"))
      customAssistedInjectAnnotations.add(daggerAssistedPackage.classId("AssistedInject"))
      // Multibindings
      customElementsIntoSetAnnotations.add(daggerMultibindingsPackage.classId("ElementsIntoSet"))
      customIntoMapAnnotations.add(daggerMultibindingsPackage.classId("IntoMap"))
      customIntoSetAnnotations.add(daggerMultibindingsPackage.classId("IntoSet"))
      customMultibindsAnnotations.add(daggerMultibindingsPackage.classId("Multibinds"))
      customMapKeyAnnotations.add(daggerPackage.classId("MapKey"))
      // Everything else
      customBindingContainerAnnotations.add(daggerPackage.classId("Module"))
      customBindsAnnotations.add(daggerPackage.classId("Binds"))
      customGraphAnnotations.add(daggerPackage.classId("Component"))
      customGraphExtensionAnnotations.add(daggerPackage.classId("Subcomponent"))
      customGraphExtensionFactoryAnnotations.add(daggerPackage.classId("Subcomponent.Factory"))
      customGraphFactoryAnnotations.add(daggerPackage.classId("Component.Factory"))
      customLazyTypes.add(daggerPackage.classId("Lazy"))
      customProviderTypes.add(daggerPackage.child(internalName).classId("Provider"))
      customProvidesAnnotations.addAll(
        listOf(daggerPackage.classId("Provides"), daggerPackage.classId("BindsInstance"))
      )
      // Implicitly includes javax/jakarta
      includeJavaxAnnotations()
      includeJakartaAnnotations()
    }

    public fun includeKotlinInjectAnnotations() {
      customAssistedAnnotations.add(kotlinInjectPackage.classId("Assisted"))
      customAssistedFactoryAnnotations.add(kotlinInjectPackage.classId("AssistedFactory"))
      customGraphAnnotations.add(kotlinInjectPackage.classId("Component"))
      customInjectAnnotations.add(kotlinInjectPackage.classId("Inject"))
      customIntoMapAnnotations.add(kotlinInjectPackage.classId("IntoMap"))
      customIntoSetAnnotations.add(kotlinInjectPackage.classId("IntoSet"))
      customProvidesAnnotations.add(kotlinInjectPackage.classId("Provides"))
      customQualifierAnnotations.add(kotlinInjectPackage.classId("Qualifier"))
      customScopeAnnotations.add(kotlinInjectPackage.classId("Scope"))
    }

    public fun includeAnvilAnnotations() {
      enableDaggerAnvilInterop = true
      customContributesBindingAnnotations.add(anvilPackage.classId("ContributesBinding"))
      customContributesIntoSetAnnotations.add(anvilPackage.classId("ContributesMultibinding"))
      customContributesToAnnotations.add(anvilPackage.classId("ContributesTo"))
      customGraphAnnotations.add(anvilPackage.classId("MergeComponent"))
      customGraphExtensionAnnotations.add(anvilPackage.classId("ContributesSubcomponent"))
      customGraphExtensionAnnotations.add(anvilPackage.classId("MergeSubcomponent"))
      // Anvil for Dagger doesn't have MergeSubcomponent.Factory
      customGraphFactoryAnnotations.add(anvilPackage.classId("MergeComponent.Factory"))
      includeDaggerAnnotations()
    }

    public fun includeKotlinInjectAnvilAnnotations() {
      customContributesBindingAnnotations.add(
        kotlinInjectAnvilPackage.classId("ContributesBinding")
      )
      customContributesToAnnotations.add(kotlinInjectAnvilPackage.classId("ContributesTo"))
      customGraphAnnotations.add(kotlinInjectAnvilPackage.classId("MergeComponent"))
      customGraphExtensionAnnotations.add(
        kotlinInjectAnvilPackage.classId("ContributesSubcomponent")
      )
      customGraphExtensionFactoryAnnotations.add(
        kotlinInjectAnvilPackage.classId("ContributesSubcomponent.Factory")
      )
      customOriginAnnotations.add(kotlinInjectAnvilPackage.child(internalName).classId("Origin"))
      includeKotlinInjectAnnotations()
    }

    public fun build(): MetroOptions {
      if (debug) {
        enabledLoggers += MetroLogger.Type.entries
      }
      return MetroOptions(
        debug = debug,
        enabled = enabled,
        reportsDestination = reportsDestination,
        generateAssistedFactories = generateAssistedFactories,
        enableTopLevelFunctionInjection = enableTopLevelFunctionInjection,
        generateContributionHints = generateContributionHints,
        generateJvmContributionHintsInFir = generateJvmContributionHintsInFir,
        transformProvidersToPrivate = transformProvidersToPrivate,
        shrinkUnusedBindings = shrinkUnusedBindings,
        chunkFieldInits = chunkFieldInits,
        statementsPerInitFun = statementsPerInitFun,
        publicProviderSeverity = publicProviderSeverity,
        optionalBindingBehavior = optionalBindingBehavior,
        warnOnInjectAnnotationPlacement = warnOnInjectAnnotationPlacement,
        interopAnnotationsNamedArgSeverity = interopAnnotationsNamedArgSeverity,
        enabledLoggers = enabledLoggers,
        enableDaggerRuntimeInterop = enableDaggerRuntimeInterop,
        maxIrErrorsCount = maxIrErrorsCount,
        customProviderTypes = customProviderTypes,
        customLazyTypes = customLazyTypes,
        customAssistedAnnotations = customAssistedAnnotations,
        customAssistedFactoryAnnotations = customAssistedFactoryAnnotations,
        customAssistedInjectAnnotations = customAssistedInjectAnnotations,
        customBindsAnnotations = customBindsAnnotations,
        customContributesToAnnotations = customContributesToAnnotations,
        customContributesBindingAnnotations = customContributesBindingAnnotations,
        customContributesIntoSetAnnotations = customContributesIntoSetAnnotations,
        customGraphExtensionAnnotations = customGraphExtensionAnnotations,
        customGraphExtensionFactoryAnnotations = customGraphExtensionFactoryAnnotations,
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
        customBindingContainerAnnotations = customBindingContainerAnnotations,
        enableDaggerAnvilInterop = enableDaggerAnvilInterop,
        enableFullBindingGraphValidation = enableFullBindingGraphValidation,
        enableGraphImplClassAsReturnType = enableGraphImplClassAsReturnType,
        customOriginAnnotations = customOriginAnnotations,
        customOptionalBindingAnnotations = customOptionalBindingAnnotations,
        contributesAsInject = contributesAsInject,
      )
    }

    private companion object {
      val javaxInjectPackage = FqName("javax.inject")
      val jakartaInjectPackage = FqName("jakarta.inject")
      val daggerPackage = FqName("dagger")
      val daggerAssistedPackage = FqName("dagger.assisted")
      val daggerMultibindingsPackage = FqName("dagger.multibindings")
      val kotlinInjectPackage = FqName("me.tatarka.inject.annotations")
      val anvilPackage = FqName("com.squareup.anvil.annotations")
      val kotlinInjectAnvilPackage = FqName("software.amazon.lastmile.kotlin.inject.anvil")
      val internalName = Name.identifier("internal")
    }
  }

  public companion object {
    public fun buildOptions(body: Builder.() -> Unit): MetroOptions {
      return Builder().apply(body).build()
    }

    internal fun load(configuration: CompilerConfiguration): MetroOptions = buildOptions {
      for (entry in MetroOption.entries) {
        when (entry) {
          MetroOption.DEBUG -> debug = configuration.getAsBoolean(entry)

          MetroOption.ENABLED -> enabled = configuration.getAsBoolean(entry)

          MetroOption.REPORTS_DESTINATION -> {
            reportsDestination =
              configuration.getAsString(entry).takeUnless(String::isBlank)?.let(Paths::get)
          }

          MetroOption.GENERATE_ASSISTED_FACTORIES ->
            generateAssistedFactories = configuration.getAsBoolean(entry)

          MetroOption.ENABLE_TOP_LEVEL_FUNCTION_INJECTION ->
            enableTopLevelFunctionInjection = configuration.getAsBoolean(entry)

          MetroOption.GENERATE_CONTRIBUTION_HINTS ->
            generateContributionHints = configuration.getAsBoolean(entry)

          MetroOption.GENERATE_JVM_CONTRIBUTION_HINTS_IN_FIR ->
            generateJvmContributionHintsInFir = configuration.getAsBoolean(entry)

          MetroOption.TRANSFORM_PROVIDERS_TO_PRIVATE ->
            transformProvidersToPrivate = configuration.getAsBoolean(entry)

          MetroOption.SHRINK_UNUSED_BINDINGS ->
            shrinkUnusedBindings = configuration.getAsBoolean(entry)

          MetroOption.CHUNK_FIELD_INITS -> chunkFieldInits = configuration.getAsBoolean(entry)

          MetroOption.STATEMENTS_PER_INIT_FUN ->
            statementsPerInitFun = configuration.getAsInt(entry)

          MetroOption.PUBLIC_PROVIDER_SEVERITY ->
            publicProviderSeverity =
              configuration.getAsString(entry).let {
                DiagnosticSeverity.valueOf(it.uppercase(Locale.US))
              }

          MetroOption.WARN_ON_INJECT_ANNOTATION_PLACEMENT ->
            warnOnInjectAnnotationPlacement = configuration.getAsBoolean(entry)

          MetroOption.INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY ->
            interopAnnotationsNamedArgSeverity =
              configuration.getAsString(entry).let {
                DiagnosticSeverity.valueOf(it.uppercase(Locale.US))
              }

          MetroOption.LOGGING -> {
            enabledLoggers +=
              configuration.get(entry.raw.key)?.expectAs<Set<MetroLogger.Type>>().orEmpty()
          }

          MetroOption.ENABLE_DAGGER_RUNTIME_INTEROP ->
            enableDaggerRuntimeInterop = configuration.getAsBoolean(entry)

          MetroOption.MAX_IR_ERRORS_COUNT -> maxIrErrorsCount = configuration.getAsInt(entry)

          // Intrinsics
          MetroOption.CUSTOM_PROVIDER -> customProviderTypes.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_LAZY -> customLazyTypes.addAll(configuration.getAsSet(entry))

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
          MetroOption.CUSTOM_GRAPH_EXTENSION ->
            customGraphExtensionAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_GRAPH_EXTENSION_FACTORY ->
            customGraphExtensionFactoryAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_ELEMENTS_INTO_SET ->
            customElementsIntoSetAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_DEPENDENCY_GRAPH ->
            customGraphAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_DEPENDENCY_GRAPH_FACTORY ->
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
          MetroOption.CUSTOM_BINDING_CONTAINER ->
            customBindingContainerAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_CONTRIBUTES_INTO_SET ->
            customContributesIntoSetAnnotations.addAll(configuration.getAsSet(entry))

          MetroOption.ENABLE_DAGGER_ANVIL_INTEROP ->
            enableDaggerAnvilInterop = configuration.getAsBoolean(entry)

          MetroOption.ENABLE_FULL_BINDING_GRAPH_VALIDATION ->
            enableFullBindingGraphValidation = configuration.getAsBoolean(entry)

          MetroOption.ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE ->
            enableGraphImplClassAsReturnType = configuration.getAsBoolean(entry)

          MetroOption.CUSTOM_ORIGIN -> customOriginAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.CUSTOM_OPTIONAL_BINDING ->
            customOptionalBindingAnnotations.addAll(configuration.getAsSet(entry))
          MetroOption.OPTIONAL_BINDING_BEHAVIOR ->
            optionalBindingBehavior =
              configuration.getAsString(entry).let {
                OptionalBindingBehavior.valueOf(it.uppercase(Locale.US))
              }

          MetroOption.CONTRIBUTES_AS_INJECT ->
            contributesAsInject = configuration.getAsBoolean(entry)

          MetroOption.INTEROP_INCLUDE_JAVAX_ANNOTATIONS -> {
            if (configuration.getAsBoolean(entry)) includeJavaxAnnotations()
          }
          MetroOption.INTEROP_INCLUDE_JAKARTA_ANNOTATIONS -> {
            if (configuration.getAsBoolean(entry)) includeJakartaAnnotations()
          }
          MetroOption.INTEROP_INCLUDE_DAGGER_ANNOTATIONS -> {
            if (configuration.getAsBoolean(entry)) includeDaggerAnnotations()
          }
          MetroOption.INTEROP_INCLUDE_KOTLIN_INJECT_ANNOTATIONS -> {
            if (configuration.getAsBoolean(entry)) includeKotlinInjectAnnotations()
          }
          MetroOption.INTEROP_INCLUDE_ANVIL_ANNOTATIONS -> {
            if (configuration.getAsBoolean(entry)) includeAnvilAnnotations()
          }
          MetroOption.INTEROP_INCLUDE_KOTLIN_INJECT_ANVIL_ANNOTATIONS -> {
            if (configuration.getAsBoolean(entry)) includeKotlinInjectAnvilAnnotations()
          }
        }
      }
    }

    private fun CompilerConfiguration.getAsString(option: MetroOption): String {
      @Suppress("UNCHECKED_CAST") val typed = option.raw as RawMetroOption<String>
      return get(typed.key, typed.defaultValue)
    }

    private fun CompilerConfiguration.getAsBoolean(option: MetroOption): Boolean {
      @Suppress("UNCHECKED_CAST") val typed = option.raw as RawMetroOption<Boolean>
      return get(typed.key, typed.defaultValue)
    }

    private fun CompilerConfiguration.getAsInt(option: MetroOption): Int {
      @Suppress("UNCHECKED_CAST") val typed = option.raw as RawMetroOption<Int>
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
    ERROR;

    public val isEnabled: Boolean
      get() = this != NONE
  }
}
