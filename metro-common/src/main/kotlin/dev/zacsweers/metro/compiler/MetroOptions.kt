// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:UseSerializers(ClassIdSerializer::class)

package dev.zacsweers.metro.compiler

import dev.drewhamilton.poko.Poko
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

// Borrowed from Dagger
// https://github.com/google/dagger/blob/b39cf2d0640e4b24338dd290cb1cb2e923d38cb3/dagger-compiler/main/java/dagger/internal/codegen/writing/ComponentImplementation.java#L263
public const val DEFAULT_STATEMENTS_PER_INIT_FUN: Int = 25

// Default is lower than Dagger's 3500 to be more aggressive with sharding since Kotlin classes
// reach JVM limits earlier than Java ones.
// https://github.com/google/dagger/blob/master/dagger-compiler/main/java/dagger/internal/codegen/compileroption/CompilerOptions.java#L142
public const val DEFAULT_KEYS_PER_GRAPH_SHARD: Int = 2000

private fun FqName.classId(simpleName: String): ClassId {
  return ClassId(this, Name.identifier(simpleName))
}

private fun ClassId.nested(simpleName: String): ClassId {
  return createNestedClassId(Name.identifier(simpleName))
}

private fun ClassId.withCustom(customClassIds: Set<ClassId>): Set<ClassId> {
  return setOf(this) + customClassIds
}

public object MetroClassIds {
  public val metroRuntimePackage: FqName = FqName("dev.zacsweers.metro")
  public val metroRuntimeInternalPackage: FqName =
    metroRuntimePackage.child(Name.identifier("internal"))

  public val dependencyGraph: ClassId = metroRuntimePackage.classId("DependencyGraph")
  public val dependencyGraphFactory: ClassId = dependencyGraph.nested("Factory")
  public val assisted: ClassId = metroRuntimePackage.classId("Assisted")
  public val assistedFactory: ClassId = metroRuntimePackage.classId("AssistedFactory")
  public val assistedInject: ClassId = metroRuntimePackage.classId("AssistedInject")
  public val inject: ClassId = metroRuntimePackage.classId("Inject")
  public val qualifier: ClassId = metroRuntimePackage.classId("Qualifier")
  public val scope: ClassId = metroRuntimePackage.classId("Scope")
  public val singleIn: ClassId = metroRuntimePackage.classId("SingleIn")
  public val bindingContainer: ClassId = metroRuntimePackage.classId("BindingContainer")
  public val origin: ClassId = metroRuntimePackage.classId("Origin")
  public val defaultBinding: ClassId = metroRuntimePackage.classId("DefaultBinding")
  public val graphPrivate: ClassId = metroRuntimePackage.classId("GraphPrivate")
  public val exposeImplBinding: ClassId = metroRuntimePackage.classId("ExposeImplBinding")
  public val optionalBinding: ClassId = metroRuntimePackage.classId("OptionalBinding")
  public val optionalDependency: ClassId = metroRuntimePackage.classId("OptionalDependency")
  public val binds: ClassId = metroRuntimePackage.classId("Binds")
  public val provides: ClassId = metroRuntimePackage.classId("Provides")
  public val intoSet: ClassId = metroRuntimePackage.classId("IntoSet")
  public val elementsIntoSet: ClassId = metroRuntimePackage.classId("ElementsIntoSet")
  public val mapKey: ClassId = metroRuntimePackage.classId("MapKey")
  public val intoMap: ClassId = metroRuntimePackage.classId("IntoMap")
  public val multibinds: ClassId = metroRuntimePackage.classId("Multibinds")
  public val contributesTo: ClassId = metroRuntimePackage.classId("ContributesTo")
  public val contributesBinding: ClassId = metroRuntimePackage.classId("ContributesBinding")
  public val contributesIntoSet: ClassId = metroRuntimePackage.classId("ContributesIntoSet")
  public val contributesIntoMap: ClassId = metroRuntimePackage.classId("ContributesIntoMap")
  public val graphExtension: ClassId = metroRuntimePackage.classId("GraphExtension")
  public val graphExtensionFactory: ClassId = graphExtension.nested("Factory")
  public val provider: ClassId = metroRuntimePackage.classId("Provider")
  public val includes: ClassId = metroRuntimePackage.classId("Includes")
  public val multibindingElement: ClassId =
    metroRuntimeInternalPackage.classId("MultibindingElement")
  public val hasMemberInjections: ClassId = metroRuntimePackage.classId("HasMemberInjections")
  public val lazy: ClassId = StandardClassIds.byName("Lazy")
  public val function0: ClassId = StandardClassIds.FunctionN(0)
}

public data class RawMetroOption<T : Any>(
  public val name: String,
  public val defaultValue: T,
  public val description: String,
  public val valueDescription: String,
  public val required: Boolean = false,
  public val allowMultipleOccurrences: Boolean = false,
  public val valueMapper: (String) -> T,
) {
  public companion object {
    public fun boolean(
      name: String,
      defaultValue: Boolean,
      description: String,
      valueDescription: String,
      required: Boolean = false,
      allowMultipleOccurrences: Boolean = false,
    ): RawMetroOption<Boolean> =
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

public enum class MetroOption(public val raw: RawMetroOption<*>) {
  DEBUG(
    RawMetroOption.boolean(
      name = "debug",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Enable debug logging for this compilation.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  ENABLED(
    RawMetroOption.boolean(
      name = "enabled",
      defaultValue = true,
      valueDescription = "<true | false>",
      description = "Enable Metro for this compilation.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  REPORTS_DESTINATION(
    RawMetroOption(
      name = "reports-destination",
      defaultValue = "",
      valueDescription = "<path>",
      description = "Directory where Metro writes diagnostic and metadata reports.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it },
    )
  ),
  TRACE_DESTINATION(
    RawMetroOption(
      name = "trace-destination",
      defaultValue = "",
      valueDescription = "<path>",
      description = "Directory where Metro writes compiler trace files.",
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
      description = "Generate assisted factories automatically.",
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
        "Enable injection for top-level functions. Disabled by default because it is not " +
          "compatible with incremental compilation.",
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
        "Recognize Dagger runtime types: Provider, Lazy, and generated Dagger factories.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  ENABLE_GUICE_RUNTIME_INTEROP(
    RawMetroOption.boolean(
      name = "enable-guice-runtime-interop",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Recognize Guice runtime types: Provider and MembersInjector.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  GENERATE_CONTRIBUTION_HINTS(
    RawMetroOption.boolean(
      name = "generate-contribution-hints",
      defaultValue = true,
      valueDescription = "<true | false>",
      description = "Generate contribution hints.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  GENERATE_CONTRIBUTION_HINTS_IN_FIR(
    RawMetroOption.boolean(
      name = "generate-contribution-hints-in-fir",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Generate contribution hints in FIR.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  GENERATE_CLASSES_IN_IR(
    RawMetroOption.boolean(
      name = "generate-classes-in-ir",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Generate metadata-visible hidden classes in IR instead of FIR.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  SHRINK_UNUSED_BINDINGS(
    RawMetroOption.boolean(
      name = "shrink-unused-bindings",
      defaultValue = true,
      valueDescription = "<true | false>",
      description = "Remove unused bindings from binding graphs.",
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
        "Maximum statements per init method when chunking field initializers. Default is " +
          "$DEFAULT_STATEMENTS_PER_INIT_FUN. Must be > 0.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.toInt() },
    )
  ),
  ENABLE_GRAPH_SHARDING(
    RawMetroOption.boolean(
      name = "enable-graph-sharding",
      defaultValue = true,
      valueDescription = "<true | false>",
      description = "Shard generated binding graphs.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  KEYS_PER_GRAPH_SHARD(
    RawMetroOption(
      name = "keys-per-graph-shard",
      defaultValue = DEFAULT_KEYS_PER_GRAPH_SHARD,
      valueDescription = "<count>",
      description =
        "Maximum binding keys per graph shard when sharding is enabled. Default is " +
          "$DEFAULT_KEYS_PER_GRAPH_SHARD. Must be > 0.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.toInt() },
    )
  ),
  MERGED_SUPERTYPE_CHUNK_SIZE(
    RawMetroOption(
      name = "merged-supertype-chunk-size",
      defaultValue = 0,
      valueDescription = "<count>",
      description =
        "Maximum contribution supertypes per chunk when merging contributions in IR." +
          " When set, the IR contribution merger groups merged supertypes into synthetic intermediate" +
          " interfaces of at most this size, which is useful for graphs whose merged supertype list" +
          " exceeds the JVM's 65k class-signature byte limit. Default 0 disables chunking. Values < 2" +
          " are treated as disabled.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.toInt() },
    )
  ),
  ENABLE_SWITCHING_PROVIDERS(
    RawMetroOption.boolean(
      name = "enable-switching-providers",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Use SwitchingProviders for deferred class loading.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  PUBLIC_SCOPED_PROVIDER_SEVERITY(
    RawMetroOption(
      name = "public-scoped-provider-severity",
      defaultValue = MetroOptions.DiagnosticSeverity.NONE.name,
      valueDescription = MetroOptions.DiagnosticSeverity.entries.joinToString("|"),
      description =
        "Severity for public scoped-provider diagnostics. Only applies when " +
          "`transform-providers-to-private` is false.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it },
    )
  ),
  NON_PUBLIC_CONTRIBUTION_SEVERITY(
    RawMetroOption(
      name = "non-public-contribution-severity",
      defaultValue = MetroOptions.DiagnosticSeverity.NONE.name,
      valueDescription = MetroOptions.DiagnosticSeverity.entries.joinToString("|"),
      description = "Severity for non-public `@Contributes*` declaration diagnostics.",
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
        "Suggest moving `@Inject`/`@AssistedInject` to the class when it has only one constructor.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY(
    RawMetroOption(
      name = "interop-annotations-named-arg-severity",
      defaultValue = MetroOptions.DiagnosticSeverity.NONE.name,
      valueDescription = MetroOptions.DiagnosticSeverity.entries.joinToString("|"),
      description =
        "Severity for interop annotations that use positional arguments instead of named arguments.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it },
    )
  ),
  UNUSED_GRAPH_INPUTS_SEVERITY(
    RawMetroOption(
      name = "unused-graph-inputs-severity",
      defaultValue = MetroOptions.DiagnosticSeverity.WARN.name,
      valueDescription = MetroOptions.DiagnosticSeverity.entries.joinToString("|"),
      description =
        "Severity for unused graph inputs, such as factory parameters not used by the graph.",
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
      description = "Logging types to enable.",
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
        "Maximum errors to report before exiting IR processing. Default is 20. Must be > 0.",
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
        "Enable Dagger Anvil interop beyond annotation aliases, currently rank support.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  ENABLE_FULL_BINDING_GRAPH_VALIDATION(
    RawMetroOption.boolean(
      name = "enable-full-binding-graph-validation",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Validate all binds and provides declarations, including unused declarations.",
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
        "Return the generated Metro graph type from generated graph factories instead of the " +
          "declared graph interface. Useful for Dagger/Anvil interop.",
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
        "Custom annotations that identify the origin class of generated contribution types.",
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
      description = "Optional binding behavior.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it },
    )
  ),
  CONTRIBUTES_AS_INJECT(
    RawMetroOption.boolean(
      name = "contributes-as-inject",
      defaultValue = true,
      valueDescription = "<true | false>",
      description =
        "Treat `@Contributes*` annotations, except `@ContributesTo`, as implicit `@Inject` annotations.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  ENABLE_KLIB_PARAMS_CHECK(
    RawMetroOption.boolean(
      name = "enable-klib-params-check",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Check klib parameter qualifiers. Intended for Kotlin versions [2.3.0, 2.3.20-Beta2).",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  PATCH_KLIB_PARAMS(
    RawMetroOption.boolean(
      name = "patch-klib-params",
      defaultValue = true,
      valueDescription = "<true | false>",
      description =
        "Patch klib parameter qualifiers to work around a kotlinc bug. Only applies when " +
          "`enable-klib-params-check` is enabled.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_INCLUDE_JAVAX_ANNOTATIONS(
    RawMetroOption.boolean(
      name = "interop-include-javax-annotations",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Recognize javax.inject annotations.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_INCLUDE_JAKARTA_ANNOTATIONS(
    RawMetroOption.boolean(
      name = "interop-include-jakarta-annotations",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Recognize jakarta.inject annotations.",
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
        "Recognize Dagger annotations. Also includes javax.inject and jakarta.inject annotations.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_INCLUDE_KOTLIN_INJECT_ANNOTATIONS(
    RawMetroOption.boolean(
      name = "interop-include-kotlin-inject-annotations",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Recognize kotlin-inject annotations.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_INCLUDE_ANVIL_ANNOTATIONS(
    RawMetroOption.boolean(
      name = "interop-include-anvil-annotations",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Recognize Anvil annotations. Also includes Dagger annotations.",
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
        "Recognize kotlin-inject Anvil annotations. Also includes kotlin-inject annotations.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_INCLUDE_HILT_ANNOTATIONS(
    RawMetroOption.boolean(
      name = "interop-include-hilt-annotations",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Recognize Hilt `@InstallIn` and `@AggregatedDeps` annotations. Usually paired with " +
          "Dagger annotations because Hilt modules are Dagger modules.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  INTEROP_INCLUDE_GUICE_ANNOTATIONS(
    RawMetroOption.boolean(
      name = "interop-include-guice-annotations",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Recognize Guice annotations. Also includes javax.inject annotations.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  FORCE_ENABLE_FIR_IN_IDE(
    RawMetroOption.boolean(
      name = "force-enable-fir-in-ide",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Enable Metro FIR extensions in the IDE even when the compat layer cannot be determined.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  PLUGIN_ORDER_SET(
    RawMetroOption(
      name = "plugin-order-set",
      defaultValue = "",
      valueDescription = "<true | false | empty>",
      description =
        "Internal marker for whether plugin order was set before compose-compiler. Empty means unset.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it },
    )
  ),
  COMPILER_VERSION(
    RawMetroOption(
      name = "compiler-version",
      defaultValue = "",
      valueDescription = "<version>",
      description =
        "Override the Kotlin compiler version Metro uses for compatibility decisions, for " +
          "example 2.3.20-dev-1234.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it },
    )
  ),
  COMPILER_VERSION_ALIASES(
    RawMetroOption(
      name = "compiler-version-aliases",
      defaultValue = emptyMap(),
      valueDescription = "<from1=to1:from2=to2>",
      description =
        "Map fake IDE compiler versions to real compiler versions. Format: from1=to1:from2=to2.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { value ->
        if (value.isBlank()) {
          emptyMap()
        } else {
          value.split(":").associate { entry ->
            val (from, to) = entry.split("=", limit = 2)
            from to to
          }
        }
      },
    )
  ),
  PARALLEL_THREADS(
    RawMetroOption(
      name = "parallel-threads",
      defaultValue = 0,
      valueDescription = "<count>",
      description = "Threads to use for parallel graph validation. 0 disables parallelism.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it.toInt() },
    )
  ),
  BUFFERED_IC_TRACKING(
    RawMetroOption.boolean(
      name = "buffered-ic-tracking",
      defaultValue = true,
      valueDescription = "<true | false>",
      description =
        "Buffer incremental-compilation lookup and expect/actual tracking during IR, then flush " +
          "once after graph validation. Enabled by default; disable as a kill switch.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  ENABLE_PROVIDER_INLINING(
    RawMetroOption.boolean(
      name = "enable-provider-inlining",
      defaultValue = true,
      valueDescription = "<true | false>",
      description =
        "Inline constant provider bodies into generated graph accessors. Enabled by default; " +
          "disable as a kill switch.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  ENABLE_FUNCTION_PROVIDERS(
    RawMetroOption.boolean(
      name = "enable-function-providers",
      defaultValue = true,
      valueDescription = "<true | false>",
      description = "Treat `() -> T` as a provider type.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  DESUGARED_PROVIDER_SEVERITY(
    RawMetroOption(
      name = "desugared-provider-severity",
      defaultValue = MetroOptions.DiagnosticSeverity.WARN.name,
      valueDescription = MetroOptions.DiagnosticSeverity.entries.joinToString("|"),
      description =
        "Severity for desugared `Provider<T>` function types. Prefer `() -> T`. Only applies " +
          "when `enable-function-providers` is enabled; otherwise treated as NONE.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it },
    )
  ),
  ENABLE_KCLASS_TO_CLASS_INTEROP(
    RawMetroOption.boolean(
      name = "enable-kclass-to-class-interop",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Treat `java.lang.Class` and `kotlin.reflect.KClass` as interchangeable in multibinding " +
          "map key types.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  GENERATE_CONTRIBUTION_PROVIDERS(
    RawMetroOption.boolean(
      name = "generate-contribution-providers",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Generate top-level contribution provider classes with `@Provides` functions instead of " +
          "nested `@Binds` interfaces, allowing implementation classes to stay internal.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  ENABLE_CIRCUIT_CODEGEN(
    RawMetroOption.boolean(
      name = "enable-circuit-codegen",
      defaultValue = false,
      valueDescription = "<true | false>",
      description =
        "Generate Metro-native Circuit bindings for `@CircuitInject` classes and functions.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  DIAGNOSTICS_RENDER_MODE(
    RawMetroOption(
      name = "diagnostics-render-mode",
      defaultValue = DiagnosticsRenderMode.PLAIN.name,
      valueDescription = DiagnosticsRenderMode.entries.joinToString("|"),
      description =
        "Build-output rendering mode for diagnostics. PLAIN uses ASCII with no ANSI styling. RICH " +
          "uses Unicode glyphs and ANSI styling. AUTO is resolved by the Gradle plugin and " +
          "falls back to PLAIN in the compiler. The `metro.diagnosticsRenderMode` system property " +
          "overrides this option.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it },
    )
  ),
  GENERATE_STATIC_ANNOTATIONS(
    RawMetroOption.boolean(
      name = "generate-static-annotations",
      defaultValue = true,
      valueDescription = "<true | false>",
      description =
        "Annotate generated static factory functions, such as `create`, `newInstance`, and " +
          "`inject{Name}`, with `@JvmStatic` and `@JsStatic`.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  ENABLE_RUNTIME_TRACING(
    RawMetroOption.boolean(
      name = "enable-runtime-tracing",
      defaultValue = false,
      valueDescription = "<true | false>",
      description = "Enables bytecode/IR tracing for binding injections using androidx.tracing.",
      required = false,
      allowMultipleOccurrences = false,
    )
  ),
  MEMBER_NAMING_STRATEGY(
    RawMetroOption(
      name = "member-naming-strategy",
      defaultValue = MemberNamingStrategy.DESCRIPTIVE.name,
      valueDescription = MemberNamingStrategy.entries.joinToString("|"),
      description =
        "Strategy for naming generated provider/instance/factory fields in graph, factory, and " +
          "members-injector classes. DESCRIPTIVE keeps names derived from types/parameters; " +
          "TYPED uses short typed prefixes (provider*, instance*, factory*); MINIMAL collapses " +
          "all kinds to a single short vocabulary. Nested-shard graphs always collapse to MINIMAL " +
          "when the strategy is not DESCRIPTIVE. Default is DESCRIPTIVE.",
      required = false,
      allowMultipleOccurrences = false,
      valueMapper = { it },
    )
  );

  public companion object {
    public val entriesByOptionName: Map<String, MetroOption> = entries.associateBy { it.raw.name }
  }
}

@Serializable
@Poko
public class MetroOptions(
  public val debug: Boolean = MetroOption.DEBUG.raw.defaultValue.expectAs(),
  public val enabled: Boolean = MetroOption.ENABLED.raw.defaultValue.expectAs(),
  @Transient
  private val rawReportsDestination: Path? =
    MetroOption.REPORTS_DESTINATION.raw.defaultValue
      .expectAs<String>()
      .takeUnless(String::isBlank)
      ?.let(Paths::get),
  @Transient
  private val rawTraceDestination: Path? =
    MetroOption.TRACE_DESTINATION.raw.defaultValue
      .expectAs<String>()
      .takeUnless(String::isBlank)
      ?.let(Paths::get),
  public val generateAssistedFactories: Boolean =
    MetroOption.GENERATE_ASSISTED_FACTORIES.raw.defaultValue.expectAs(),
  public val enableTopLevelFunctionInjection: Boolean =
    MetroOption.ENABLE_TOP_LEVEL_FUNCTION_INJECTION.raw.defaultValue.expectAs(),
  public val generateContributionHints: Boolean =
    MetroOption.GENERATE_CONTRIBUTION_HINTS.raw.defaultValue.expectAs(),
  public val generateContributionHintsInFir: Boolean =
    MetroOption.GENERATE_CONTRIBUTION_HINTS_IN_FIR.raw.defaultValue.expectAs(),
  public val generateClassesInIr: Boolean =
    MetroOption.GENERATE_CLASSES_IN_IR.raw.defaultValue.expectAs(),
  public val shrinkUnusedBindings: Boolean =
    MetroOption.SHRINK_UNUSED_BINDINGS.raw.defaultValue.expectAs(),
  public val statementsPerInitFun: Int =
    MetroOption.STATEMENTS_PER_INIT_FUN.raw.defaultValue.expectAs(),
  public val enableGraphSharding: Boolean =
    MetroOption.ENABLE_GRAPH_SHARDING.raw.defaultValue.expectAs(),
  public val keysPerGraphShard: Int = MetroOption.KEYS_PER_GRAPH_SHARD.raw.defaultValue.expectAs(),
  public val mergedSupertypeChunkSize: Int =
    MetroOption.MERGED_SUPERTYPE_CHUNK_SIZE.raw.defaultValue.expectAs(),
  public val enableSwitchingProviders: Boolean =
    MetroOption.ENABLE_SWITCHING_PROVIDERS.raw.defaultValue.expectAs(),
  public val publicScopedProviderSeverity: DiagnosticSeverity =
    MetroOption.PUBLIC_SCOPED_PROVIDER_SEVERITY.raw.defaultValue.expectAs<String>().let {
      DiagnosticSeverity.valueOf(it)
    },
  public val nonPublicContributionSeverity: DiagnosticSeverity =
    MetroOption.NON_PUBLIC_CONTRIBUTION_SEVERITY.raw.defaultValue.expectAs<String>().let {
      DiagnosticSeverity.valueOf(it)
    },
  public val optionalBindingBehavior: OptionalBindingBehavior =
    MetroOption.OPTIONAL_BINDING_BEHAVIOR.raw.defaultValue.expectAs<String>().let { rawValue ->
      val adjusted =
        rawValue.uppercase(Locale.US).let {
          // Compatibility for the removed REQUIRE_OPTIONAL_DEPENDENCY enum entry.
          if (it == "REQUIRE_OPTIONAL_DEPENDENCY") {
            "REQUIRE_OPTIONAL_BINDING"
          } else {
            it
          }
        }
      OptionalBindingBehavior.valueOf(adjusted)
    },
  public val warnOnInjectAnnotationPlacement: Boolean =
    MetroOption.WARN_ON_INJECT_ANNOTATION_PLACEMENT.raw.defaultValue.expectAs(),
  public val interopAnnotationsNamedArgSeverity: DiagnosticSeverity =
    MetroOption.INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY.raw.defaultValue.expectAs<String>().let {
      DiagnosticSeverity.valueOf(it)
    },
  public val unusedGraphInputsSeverity: DiagnosticSeverity =
    MetroOption.UNUSED_GRAPH_INPUTS_SEVERITY.raw.defaultValue.expectAs<String>().let {
      DiagnosticSeverity.valueOf(it)
    },
  public val enabledLoggers: Set<MetroLogger.Type> =
    if (debug) {
      // Debug enables _all_
      MetroLogger.Type.entries.filterNot { it == MetroLogger.Type.None }.toSet()
    } else {
      MetroOption.LOGGING.raw.defaultValue.expectAs()
    },
  public val enableDaggerRuntimeInterop: Boolean =
    MetroOption.ENABLE_DAGGER_RUNTIME_INTEROP.raw.defaultValue.expectAs(),
  public val enableGuiceRuntimeInterop: Boolean =
    MetroOption.ENABLE_GUICE_RUNTIME_INTEROP.raw.defaultValue.expectAs(),
  public val maxIrErrorsCount: Int = MetroOption.MAX_IR_ERRORS_COUNT.raw.defaultValue.expectAs(),
  // Intrinsics
  public val customProviderTypes: Set<ClassId> =
    MetroOption.CUSTOM_PROVIDER.raw.defaultValue.expectAs(),
  public val customLazyTypes: Set<ClassId> = MetroOption.CUSTOM_LAZY.raw.defaultValue.expectAs(),
  // Custom annotations
  public val customAssistedAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_ASSISTED.raw.defaultValue.expectAs(),
  public val customAssistedFactoryAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_ASSISTED_FACTORY.raw.defaultValue.expectAs(),
  public val customAssistedInjectAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_ASSISTED_INJECT.raw.defaultValue.expectAs(),
  public val customBindsAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_BINDS.raw.defaultValue.expectAs(),
  public val customContributesToAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_CONTRIBUTES_TO.raw.defaultValue.expectAs(),
  public val customContributesBindingAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_CONTRIBUTES_BINDING.raw.defaultValue.expectAs(),
  public val customContributesIntoSetAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_CONTRIBUTES_INTO_SET.raw.defaultValue.expectAs(),
  public val customGraphExtensionAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_GRAPH_EXTENSION.raw.defaultValue.expectAs(),
  public val customGraphExtensionFactoryAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_GRAPH_EXTENSION_FACTORY.raw.defaultValue.expectAs(),
  public val customElementsIntoSetAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_ELEMENTS_INTO_SET.raw.defaultValue.expectAs(),
  public val customGraphAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_DEPENDENCY_GRAPH.raw.defaultValue.expectAs(),
  public val customGraphFactoryAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_DEPENDENCY_GRAPH_FACTORY.raw.defaultValue.expectAs(),
  public val customInjectAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_INJECT.raw.defaultValue.expectAs(),
  public val customIntoMapAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_INTO_MAP.raw.defaultValue.expectAs(),
  public val customIntoSetAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_INTO_SET.raw.defaultValue.expectAs(),
  public val customMapKeyAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_MAP_KEY.raw.defaultValue.expectAs(),
  public val customMultibindsAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_MULTIBINDS.raw.defaultValue.expectAs(),
  public val customProvidesAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_PROVIDES.raw.defaultValue.expectAs(),
  public val customQualifierAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_QUALIFIER.raw.defaultValue.expectAs(),
  public val customScopeAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_SCOPE.raw.defaultValue.expectAs(),
  public val customBindingContainerAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_BINDING_CONTAINER.raw.defaultValue.expectAs(),
  public val enableDaggerAnvilInterop: Boolean =
    MetroOption.ENABLE_DAGGER_ANVIL_INTEROP.raw.defaultValue.expectAs(),
  public val enableFullBindingGraphValidation: Boolean =
    MetroOption.ENABLE_FULL_BINDING_GRAPH_VALIDATION.raw.defaultValue.expectAs(),
  public val enableGraphImplClassAsReturnType: Boolean =
    MetroOption.ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE.raw.defaultValue.expectAs(),
  public val customOriginAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_ORIGIN.raw.defaultValue.expectAs(),
  public val customOptionalBindingAnnotations: Set<ClassId> =
    MetroOption.CUSTOM_OPTIONAL_BINDING.raw.defaultValue.expectAs(),
  public val contributesAsInject: Boolean =
    MetroOption.CONTRIBUTES_AS_INJECT.raw.defaultValue.expectAs(),
  public val enableKlibParamsCheck: Boolean =
    MetroOption.ENABLE_KLIB_PARAMS_CHECK.raw.defaultValue.expectAs(),
  public val patchKlibParams: Boolean = MetroOption.PATCH_KLIB_PARAMS.raw.defaultValue.expectAs(),
  public val forceEnableFirInIde: Boolean =
    MetroOption.FORCE_ENABLE_FIR_IN_IDE.raw.defaultValue.expectAs(),
  public val pluginOrderSet: Boolean? =
    MetroOption.PLUGIN_ORDER_SET.raw.defaultValue
      .expectAs<String>()
      .takeUnless(String::isBlank)
      ?.toBooleanStrict(),
  public val compilerVersion: String? =
    MetroOption.COMPILER_VERSION.raw.defaultValue.expectAs<String>().takeUnless(String::isBlank),
  public val compilerVersionAliases: Map<String, String> =
    MetroOption.COMPILER_VERSION_ALIASES.raw.defaultValue.expectAs(),
  public val parallelThreads: Int = MetroOption.PARALLEL_THREADS.raw.defaultValue.expectAs(),
  public val bufferedIcTracking: Boolean =
    MetroOption.BUFFERED_IC_TRACKING.raw.defaultValue.expectAs(),
  public val enableProviderInlining: Boolean =
    MetroOption.ENABLE_PROVIDER_INLINING.raw.defaultValue.expectAs(),
  public val enableFunctionProviders: Boolean =
    MetroOption.ENABLE_FUNCTION_PROVIDERS.raw.defaultValue.expectAs(),
  public val desugaredProviderSeverity: DiagnosticSeverity =
    MetroOption.DESUGARED_PROVIDER_SEVERITY.raw.defaultValue.expectAs<String>().let {
      DiagnosticSeverity.valueOf(it)
    },
  public val enableKClassToClassInterop: Boolean =
    MetroOption.ENABLE_KCLASS_TO_CLASS_INTEROP.raw.defaultValue.expectAs(),
  public val generateContributionProviders: Boolean =
    MetroOption.GENERATE_CONTRIBUTION_PROVIDERS.raw.defaultValue.expectAs(),
  public val enableCircuitCodegen: Boolean =
    MetroOption.ENABLE_CIRCUIT_CODEGEN.raw.defaultValue.expectAs(),
  public val enableHiltInterop: Boolean =
    MetroOption.INTEROP_INCLUDE_HILT_ANNOTATIONS.raw.defaultValue.expectAs(),
  public val diagnosticsRenderMode: DiagnosticsRenderMode =
    MetroOption.DIAGNOSTICS_RENDER_MODE.raw.defaultValue
      .expectAs<String>()
      .let(DiagnosticsRenderMode::parse),
  public val generateStaticAnnotations: Boolean =
    MetroOption.GENERATE_STATIC_ANNOTATIONS.raw.defaultValue.expectAs(),
  public val enableRuntimeTracing: Boolean =
    MetroOption.ENABLE_RUNTIME_TRACING.raw.defaultValue.expectAs(),
  public val memberNamingStrategy: MemberNamingStrategy =
    MetroOption.MEMBER_NAMING_STRATEGY.raw.defaultValue.expectAs<String>().let {
      MemberNamingStrategy.valueOf(it.uppercase(Locale.US))
    },
) {
  @Transient
  public val providerTypes: Set<ClassId> = buildSet {
    add(MetroClassIds.provider)
    addAll(customProviderTypes)
    if (enableFunctionProviders) {
      add(MetroClassIds.function0)
    }
  }

  @Transient public val lazyTypes: Set<ClassId> = MetroClassIds.lazy.withCustom(customLazyTypes)

  @Transient
  public val dependencyGraphAnnotations: Set<ClassId> =
    MetroClassIds.dependencyGraph.withCustom(customGraphAnnotations)

  @Transient
  public val dependencyGraphFactoryAnnotations: Set<ClassId> =
    MetroClassIds.dependencyGraphFactory.withCustom(customGraphFactoryAnnotations)

  @Transient
  public val assistedInjectAnnotations: Set<ClassId> =
    MetroClassIds.assistedInject.withCustom(customAssistedInjectAnnotations)

  @Transient
  public val assistedAnnotations: Set<ClassId> =
    MetroClassIds.assisted.withCustom(customAssistedAnnotations)

  @Transient
  public val assistedFactoryAnnotations: Set<ClassId> =
    MetroClassIds.assistedFactory.withCustom(customAssistedFactoryAnnotations)

  @Transient
  public val injectAnnotations: Set<ClassId> =
    MetroClassIds.inject.withCustom(customInjectAnnotations)

  @Transient
  public val allInjectAnnotations: Set<ClassId> = injectAnnotations + assistedInjectAnnotations

  @Transient
  public val qualifierAnnotations: Set<ClassId> =
    MetroClassIds.qualifier.withCustom(customQualifierAnnotations)

  @Transient
  public val scopeAnnotations: Set<ClassId> = MetroClassIds.scope.withCustom(customScopeAnnotations)

  @Transient
  public val bindingContainerAnnotations: Set<ClassId> =
    MetroClassIds.bindingContainer.withCustom(customBindingContainerAnnotations)

  @Transient
  public val originAnnotations: Set<ClassId> =
    MetroClassIds.origin.withCustom(customOriginAnnotations)

  @Transient
  public val contributionProviderExclusionAnnotations: Set<ClassId> =
    setOf(MetroClassIds.exposeImplBinding) + assistedFactoryAnnotations

  @Transient
  public val optionalBindingAnnotations: Set<ClassId> =
    setOf(MetroClassIds.optionalBinding, MetroClassIds.optionalDependency) +
      customOptionalBindingAnnotations

  @Transient
  public val bindsAnnotations: Set<ClassId> = MetroClassIds.binds.withCustom(customBindsAnnotations)

  @Transient
  public val providesAnnotations: Set<ClassId> =
    MetroClassIds.provides.withCustom(customProvidesAnnotations)

  @Transient
  public val intoSetAnnotations: Set<ClassId> =
    MetroClassIds.intoSet.withCustom(customIntoSetAnnotations)

  @Transient
  public val elementsIntoSetAnnotations: Set<ClassId> =
    MetroClassIds.elementsIntoSet.withCustom(customElementsIntoSetAnnotations)

  @Transient
  public val mapKeyAnnotations: Set<ClassId> =
    MetroClassIds.mapKey.withCustom(customMapKeyAnnotations)

  @Transient
  public val intoMapAnnotations: Set<ClassId> =
    MetroClassIds.intoMap.withCustom(customIntoMapAnnotations)

  @Transient
  public val multibindsAnnotations: Set<ClassId> =
    MetroClassIds.multibinds.withCustom(customMultibindsAnnotations)

  @Transient
  public val contributesToAnnotations: Set<ClassId> =
    MetroClassIds.contributesTo.withCustom(customContributesToAnnotations)

  @Transient
  public val contributesBindingAnnotations: Set<ClassId> =
    MetroClassIds.contributesBinding.withCustom(customContributesBindingAnnotations)

  @Transient
  public val contributesIntoSetAnnotations: Set<ClassId> =
    MetroClassIds.contributesIntoSet.withCustom(customElementsIntoSetAnnotations)

  @Transient
  public val contributesIntoMapAnnotations: Set<ClassId> =
    MetroClassIds.contributesIntoMap.withCustom(customIntoMapAnnotations)

  @Transient
  public val graphExtensionAnnotations: Set<ClassId> =
    MetroClassIds.graphExtension.withCustom(customGraphExtensionAnnotations)

  @Transient
  public val graphExtensionFactoryAnnotations: Set<ClassId> =
    MetroClassIds.graphExtensionFactory.withCustom(customGraphExtensionFactoryAnnotations)

  @Transient
  public val allContributesAnnotations: Set<ClassId> =
    contributesToAnnotations +
      contributesBindingAnnotations +
      contributesIntoSetAnnotations +
      contributesIntoMapAnnotations +
      customContributesIntoSetAnnotations

  @Transient
  public val contributesBindingLikeAnnotations: Set<ClassId> =
    contributesBindingAnnotations +
      contributesIntoSetAnnotations +
      contributesIntoMapAnnotations +
      customContributesIntoSetAnnotations

  @Transient
  public val injectLikeAnnotations: Set<ClassId> =
    if (contributesAsInject) {
      injectAnnotations +
        assistedInjectAnnotations +
        contributesBindingAnnotations +
        contributesIntoSetAnnotations +
        contributesIntoMapAnnotations
    } else {
      injectAnnotations + assistedInjectAnnotations
    }

  @Transient
  public val allCustomClassIds: Set<ClassId> = buildSet {
    addAll(customLazyTypes)
    addAll(customProviderTypes)
    addAll(customAssistedAnnotations)
    addAll(customAssistedFactoryAnnotations)
    addAll(customAssistedInjectAnnotations)
    addAll(customBindsAnnotations)
    addAll(customContributesToAnnotations)
    addAll(customContributesBindingAnnotations)
    addAll(customContributesIntoSetAnnotations)
    addAll(customGraphExtensionAnnotations)
    addAll(customGraphExtensionFactoryAnnotations)
    addAll(customElementsIntoSetAnnotations)
    addAll(customGraphAnnotations)
    addAll(customGraphFactoryAnnotations)
    addAll(customInjectAnnotations)
    addAll(customIntoMapAnnotations)
    addAll(customIntoSetAnnotations)
    addAll(customMapKeyAnnotations)
    addAll(customMultibindsAnnotations)
    addAll(customProvidesAnnotations)
    addAll(customQualifierAnnotations)
    addAll(customScopeAnnotations)
    addAll(customBindingContainerAnnotations)
    addAll(customOriginAnnotations)
    addAll(customOptionalBindingAnnotations)
  }

  public val reportsEnabled: Boolean
    get() = rawReportsDestination != null

  @OptIn(ExperimentalPathApi::class)
  @Transient
  public val reportsDir: Lazy<Path?> = lazy {
    rawReportsDestination?.apply {
      if (exists()) {
        deleteRecursively()
      }
      createDirectories()
    }
  }

  public val traceEnabled: Boolean
    get() = rawTraceDestination != null

  @Transient
  public val traceDir: Lazy<Path?> = lazy {
    // Don't wipe the directory: when a Gradle daemon reruns compilation
    // (like gradle-profiler iterations), wiping each time loses every
    // prior trace. Filenames are timestamped, so accumulation is safe.
    rawTraceDestination?.apply { createDirectories() }
  }

  public fun toBuilder(): Builder = Builder(this)

  public class Builder(base: MetroOptions = MetroOptions()) {
    public var debug: Boolean = base.debug
    public var enabled: Boolean = base.enabled
    public var reportsDestination: Path? = base.rawReportsDestination
    public var traceDestination: Path? = base.rawTraceDestination
    public var generateAssistedFactories: Boolean = base.generateAssistedFactories
    public var enableTopLevelFunctionInjection: Boolean = base.enableTopLevelFunctionInjection
    public var generateContributionHints: Boolean = base.generateContributionHints
    public var generateContributionHintsInFir: Boolean = base.generateContributionHintsInFir
    public var generateClassesInIr: Boolean = base.generateClassesInIr
    public var shrinkUnusedBindings: Boolean = base.shrinkUnusedBindings
    public var statementsPerInitFun: Int = base.statementsPerInitFun
    public var enableGraphSharding: Boolean = base.enableGraphSharding
    public var keysPerGraphShard: Int = base.keysPerGraphShard
    public var mergedSupertypeChunkSize: Int = base.mergedSupertypeChunkSize
    public var enableSwitchingProviders: Boolean = base.enableSwitchingProviders
    public var publicScopedProviderSeverity: DiagnosticSeverity = base.publicScopedProviderSeverity
    public var nonPublicContributionSeverity: DiagnosticSeverity =
      base.nonPublicContributionSeverity
    public var optionalBindingBehavior: OptionalBindingBehavior = base.optionalBindingBehavior
    public var warnOnInjectAnnotationPlacement: Boolean = base.warnOnInjectAnnotationPlacement
    public var interopAnnotationsNamedArgSeverity: DiagnosticSeverity =
      base.interopAnnotationsNamedArgSeverity
    public var unusedGraphInputsSeverity: DiagnosticSeverity = base.unusedGraphInputsSeverity
    public var enabledLoggers: MutableSet<MetroLogger.Type> = base.enabledLoggers.toMutableSet()
    public var enableDaggerRuntimeInterop: Boolean = base.enableDaggerRuntimeInterop
    public var enableGuiceRuntimeInterop: Boolean = base.enableGuiceRuntimeInterop
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
    public var enableKlibParamsCheck: Boolean = base.enableKlibParamsCheck
    public var patchKlibParams: Boolean = base.patchKlibParams
    public var forceEnableFirInIde: Boolean = base.forceEnableFirInIde
    public var pluginOrderSet: Boolean? = base.pluginOrderSet
    public var compilerVersion: String? = base.compilerVersion
    public var compilerVersionAliases: Map<String, String> = base.compilerVersionAliases
    public var parallelThreads: Int = base.parallelThreads
    public var bufferedIcTracking: Boolean = base.bufferedIcTracking
    public var enableProviderInlining: Boolean = base.enableProviderInlining
    public var enableFunctionProviders: Boolean = base.enableFunctionProviders
    public var desugaredProviderSeverity: DiagnosticSeverity = base.desugaredProviderSeverity
    public var enableKClassToClassInterop: Boolean = base.enableKClassToClassInterop
    public var generateContributionProviders: Boolean = base.generateContributionProviders
    public var enableCircuitCodegen: Boolean = base.enableCircuitCodegen
    public var enableHiltInterop: Boolean = base.enableHiltInterop
    public var diagnosticsRenderMode: DiagnosticsRenderMode = base.diagnosticsRenderMode
    public var generateStaticAnnotations: Boolean = base.generateStaticAnnotations
    public var enableRuntimeTracing: Boolean = base.enableRuntimeTracing
    public var memberNamingStrategy: MemberNamingStrategy = base.memberNamingStrategy

    public fun debug(debug: Boolean): Builder = apply {
      this.debug = debug
    }

    public fun enabled(enabled: Boolean): Builder = apply {
      this.enabled = enabled
    }

    public fun reportsDestination(reportsDestination: Path?): Builder = apply {
      this.reportsDestination = reportsDestination
    }

    public fun generateAssistedFactories(generateAssistedFactories: Boolean): Builder = apply {
      this.generateAssistedFactories = generateAssistedFactories
    }

    public fun enableTopLevelFunctionInjection(enableTopLevelFunctionInjection: Boolean): Builder =
      apply {
        this.enableTopLevelFunctionInjection = enableTopLevelFunctionInjection
      }

    public fun warnOnInjectAnnotationPlacement(warnOnInjectAnnotationPlacement: Boolean): Builder =
      apply {
        this.warnOnInjectAnnotationPlacement = warnOnInjectAnnotationPlacement
      }

    public fun enableDaggerRuntimeInterop(enableDaggerRuntimeInterop: Boolean): Builder = apply {
      this.enableDaggerRuntimeInterop = enableDaggerRuntimeInterop
    }

    public fun enableFullBindingGraphValidation(
      enableFullBindingGraphValidation: Boolean
    ): Builder = apply {
      this.enableFullBindingGraphValidation = enableFullBindingGraphValidation
    }

    public fun enableFunctionProviders(enableFunctionProviders: Boolean): Builder = apply {
      this.enableFunctionProviders = enableFunctionProviders
    }

    public fun unusedGraphInputsSeverity(unusedGraphInputsSeverity: DiagnosticSeverity): Builder =
      apply {
        this.unusedGraphInputsSeverity = unusedGraphInputsSeverity
      }

    public fun contributesAsInject(contributesAsInject: Boolean): Builder = apply {
      this.contributesAsInject = contributesAsInject
    }

    public fun enableKlibParamsCheck(enableKlibParamsCheck: Boolean): Builder = apply {
      this.enableKlibParamsCheck = enableKlibParamsCheck
    }

    public fun keysPerGraphShard(keysPerGraphShard: Int): Builder = apply {
      this.keysPerGraphShard = keysPerGraphShard
    }

    public fun desugaredProviderSeverity(desugaredProviderSeverity: DiagnosticSeverity): Builder =
      apply {
        this.desugaredProviderSeverity = desugaredProviderSeverity
      }

    public fun customQualifierAnnotations(customQualifierAnnotations: Set<ClassId>): Builder =
      apply {
        this.customQualifierAnnotations.clear()
        this.customQualifierAnnotations.addAll(customQualifierAnnotations)
      }

    public fun customContributesBindingAnnotations(
      customContributesBindingAnnotations: Set<ClassId>
    ): Builder = apply {
      this.customContributesBindingAnnotations.clear()
      this.customContributesBindingAnnotations.addAll(customContributesBindingAnnotations)
    }

    public fun customBindingContainerAnnotations(
      customBindingContainerAnnotations: Set<ClassId>
    ): Builder = apply {
      this.customBindingContainerAnnotations.clear()
      this.customBindingContainerAnnotations.addAll(customBindingContainerAnnotations)
    }

    public fun customGraphExtensionAnnotations(
      customGraphExtensionAnnotations: Set<ClassId>
    ): Builder = apply {
      this.customGraphExtensionAnnotations.clear()
      this.customGraphExtensionAnnotations.addAll(customGraphExtensionAnnotations)
    }

    public fun enableDaggerAnvilInterop(enableDaggerAnvilInterop: Boolean): Builder = apply {
      this.enableDaggerAnvilInterop = enableDaggerAnvilInterop
    }

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
      // Core Dagger annotations and runtime types
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

    public fun includeHiltAnnotations() {
      enableHiltInterop = true
      // Hilt modules are also Dagger `@Module`s, so the IR-side `isBindingContainer()` check
      // requires Dagger annotations to be registered.
      includeDaggerAnnotations()
    }

    public fun includeAnvilAnnotations() {
      enableDaggerAnvilInterop = true
      customContributesBindingAnnotations.add(anvilPackage.classId("ContributesBinding"))
      customContributesIntoSetAnnotations.add(anvilPackage.classId("ContributesMultibinding"))
      customContributesToAnnotations.add(anvilPackage.classId("ContributesTo"))
      customGraphAnnotations.add(anvilPackage.classId("MergeComponent"))
      customGraphExtensionAnnotations.add(anvilPackage.classId("ContributesSubcomponent"))
      customGraphExtensionFactoryAnnotations.add(
        anvilPackage.classId("ContributesSubcomponent.Factory")
      )
      customGraphExtensionFactoryAnnotations.add(anvilPackage.classId("MergeSubcomponent.Factory"))
      customGraphExtensionAnnotations.add(anvilPackage.classId("MergeSubcomponent"))
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

    public fun includeGuiceAnnotations() {
      enableGuiceRuntimeInterop = true
      // Unsupported Guice interop surfaces:
      //  Injector (members injector)
      //  ProvidesIntoOptional. Different than `@BindsOptionalOf`, provides a value

      customInjectAnnotations.add(guicePackage.classId("Inject"))
      customProvidesAnnotations.add(guicePackage.classId("Provides"))
      customProviderTypes.add(guicePackage.classId("Provider"))
      customAssistedAnnotations.add(guiceAssistedInjectPackage.classId("Assisted"))
      customAssistedInjectAnnotations.add(guiceAssistedInjectPackage.classId("AssistedInject"))
      // Guice has no AssistedFactory
      customQualifierAnnotations.add(guicePackage.classId("BindingAnnotation"))
      customScopeAnnotations.add(guicePackage.classId("ScopeAnnotation"))
      customMapKeyAnnotations.add(guiceMultibindingsPackage.classId("MapKey"))
      customIntoMapAnnotations.add(guiceMultibindingsPackage.classId("ProvidesIntoMap"))
      customIntoSetAnnotations.add(guiceMultibindingsPackage.classId("ProvidesIntoSet"))

      // Guice uses jakarta
      includeJakartaAnnotations()
    }

    public fun applyRawOptions(optionsByName: Map<String, String>) {
      for (option in MetroOption.entries) {
        optionsByName[option.raw.name]?.let { value ->
          applyOptionValue(option, option.raw.valueMapper(value))
        }
      }
    }

    public fun applyRawOption(optionName: String, value: String) {
      val option = MetroOption.entriesByOptionName[optionName] ?: return
      applyOptionValue(option, option.raw.valueMapper(value))
    }

    public fun applyOptionValue(option: MetroOption, value: Any) {
      when (option) {
        MetroOption.DEBUG -> debug = value.expectAs()
        MetroOption.ENABLED -> enabled = value.expectAs()
        MetroOption.REPORTS_DESTINATION ->
          reportsDestination = value.expectAs<String>().takeUnless(String::isBlank)?.let(Paths::get)
        MetroOption.TRACE_DESTINATION ->
          traceDestination = value.expectAs<String>().takeUnless(String::isBlank)?.let(Paths::get)
        MetroOption.GENERATE_ASSISTED_FACTORIES -> generateAssistedFactories = value.expectAs()
        MetroOption.ENABLE_TOP_LEVEL_FUNCTION_INJECTION ->
          enableTopLevelFunctionInjection = value.expectAs()
        MetroOption.GENERATE_CONTRIBUTION_HINTS -> generateContributionHints = value.expectAs()
        MetroOption.GENERATE_CONTRIBUTION_HINTS_IN_FIR ->
          generateContributionHintsInFir = value.expectAs()
        MetroOption.GENERATE_CLASSES_IN_IR -> generateClassesInIr = value.expectAs()
        MetroOption.SHRINK_UNUSED_BINDINGS -> shrinkUnusedBindings = value.expectAs()
        MetroOption.STATEMENTS_PER_INIT_FUN -> statementsPerInitFun = value.expectAs()
        MetroOption.ENABLE_GRAPH_SHARDING -> enableGraphSharding = value.expectAs()
        MetroOption.KEYS_PER_GRAPH_SHARD -> keysPerGraphShard = value.expectAs()
        MetroOption.MERGED_SUPERTYPE_CHUNK_SIZE -> mergedSupertypeChunkSize = value.expectAs()
        MetroOption.ENABLE_SWITCHING_PROVIDERS -> enableSwitchingProviders = value.expectAs()
        MetroOption.PUBLIC_SCOPED_PROVIDER_SEVERITY ->
          publicScopedProviderSeverity = value.diagnosticSeverity()
        MetroOption.NON_PUBLIC_CONTRIBUTION_SEVERITY ->
          nonPublicContributionSeverity = value.diagnosticSeverity()
        MetroOption.WARN_ON_INJECT_ANNOTATION_PLACEMENT ->
          warnOnInjectAnnotationPlacement = value.expectAs()
        MetroOption.INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY ->
          interopAnnotationsNamedArgSeverity = value.diagnosticSeverity()
        MetroOption.UNUSED_GRAPH_INPUTS_SEVERITY ->
          unusedGraphInputsSeverity = value.diagnosticSeverity()
        MetroOption.LOGGING -> enabledLoggers += value.expectAs<Set<MetroLogger.Type>>()
        MetroOption.ENABLE_DAGGER_RUNTIME_INTEROP -> enableDaggerRuntimeInterop = value.expectAs()
        MetroOption.ENABLE_GUICE_RUNTIME_INTEROP -> enableGuiceRuntimeInterop = value.expectAs()
        MetroOption.MAX_IR_ERRORS_COUNT -> maxIrErrorsCount = value.expectAs()
        MetroOption.CUSTOM_PROVIDER -> customProviderTypes.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_LAZY -> customLazyTypes.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_ASSISTED ->
          customAssistedAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_ASSISTED_FACTORY ->
          customAssistedFactoryAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_ASSISTED_INJECT ->
          customAssistedInjectAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_BINDS -> customBindsAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_CONTRIBUTES_TO ->
          customContributesToAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_CONTRIBUTES_BINDING ->
          customContributesBindingAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_GRAPH_EXTENSION ->
          customGraphExtensionAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_GRAPH_EXTENSION_FACTORY ->
          customGraphExtensionFactoryAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_ELEMENTS_INTO_SET ->
          customElementsIntoSetAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_DEPENDENCY_GRAPH ->
          customGraphAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_DEPENDENCY_GRAPH_FACTORY ->
          customGraphFactoryAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_INJECT -> customInjectAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_INTO_MAP ->
          customIntoMapAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_INTO_SET ->
          customIntoSetAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_MAP_KEY -> customMapKeyAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_MULTIBINDS ->
          customMultibindsAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_PROVIDES ->
          customProvidesAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_QUALIFIER ->
          customQualifierAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_SCOPE -> customScopeAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_BINDING_CONTAINER ->
          customBindingContainerAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.ENABLE_DAGGER_ANVIL_INTEROP -> enableDaggerAnvilInterop = value.expectAs()
        MetroOption.ENABLE_FULL_BINDING_GRAPH_VALIDATION ->
          enableFullBindingGraphValidation = value.expectAs()
        MetroOption.ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE ->
          enableGraphImplClassAsReturnType = value.expectAs()
        MetroOption.CUSTOM_ORIGIN -> customOriginAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.CUSTOM_OPTIONAL_BINDING ->
          customOptionalBindingAnnotations.addAll(value.expectAs<Set<ClassId>>())
        MetroOption.OPTIONAL_BINDING_BEHAVIOR ->
          optionalBindingBehavior =
            OptionalBindingBehavior.valueOf(value.expectAs<String>().uppercase(Locale.US))
        MetroOption.CONTRIBUTES_AS_INJECT -> contributesAsInject = value.expectAs()
        MetroOption.ENABLE_KLIB_PARAMS_CHECK -> enableKlibParamsCheck = value.expectAs()
        MetroOption.PATCH_KLIB_PARAMS -> patchKlibParams = value.expectAs()
        MetroOption.INTEROP_INCLUDE_JAVAX_ANNOTATIONS ->
          if (value.expectAs<Boolean>()) includeJavaxAnnotations()
        MetroOption.INTEROP_INCLUDE_JAKARTA_ANNOTATIONS ->
          if (value.expectAs<Boolean>()) includeJakartaAnnotations()
        MetroOption.INTEROP_INCLUDE_DAGGER_ANNOTATIONS ->
          if (value.expectAs<Boolean>()) includeDaggerAnnotations()
        MetroOption.INTEROP_INCLUDE_KOTLIN_INJECT_ANNOTATIONS ->
          if (value.expectAs<Boolean>()) includeKotlinInjectAnnotations()
        MetroOption.INTEROP_INCLUDE_ANVIL_ANNOTATIONS ->
          if (value.expectAs<Boolean>()) includeAnvilAnnotations()
        MetroOption.INTEROP_INCLUDE_KOTLIN_INJECT_ANVIL_ANNOTATIONS ->
          if (value.expectAs<Boolean>()) includeKotlinInjectAnvilAnnotations()
        MetroOption.INTEROP_INCLUDE_HILT_ANNOTATIONS ->
          if (value.expectAs<Boolean>()) includeHiltAnnotations()
        MetroOption.INTEROP_INCLUDE_GUICE_ANNOTATIONS ->
          if (value.expectAs<Boolean>()) includeGuiceAnnotations()
        MetroOption.FORCE_ENABLE_FIR_IN_IDE -> forceEnableFirInIde = value.expectAs()
        MetroOption.PLUGIN_ORDER_SET ->
          pluginOrderSet = value.expectAs<String>().takeUnless(String::isBlank)?.toBooleanStrict()
        MetroOption.COMPILER_VERSION ->
          compilerVersion = value.expectAs<String>().takeUnless(String::isBlank)
        MetroOption.COMPILER_VERSION_ALIASES -> compilerVersionAliases = value.expectAs()
        MetroOption.PARALLEL_THREADS -> parallelThreads = value.expectAs()
        MetroOption.BUFFERED_IC_TRACKING -> bufferedIcTracking = value.expectAs()
        MetroOption.ENABLE_PROVIDER_INLINING -> enableProviderInlining = value.expectAs()
        MetroOption.ENABLE_FUNCTION_PROVIDERS -> enableFunctionProviders = value.expectAs()
        MetroOption.DESUGARED_PROVIDER_SEVERITY ->
          desugaredProviderSeverity = value.diagnosticSeverity()
        MetroOption.ENABLE_KCLASS_TO_CLASS_INTEROP -> enableKClassToClassInterop = value.expectAs()
        MetroOption.GENERATE_CONTRIBUTION_PROVIDERS ->
          generateContributionProviders = value.expectAs()
        MetroOption.ENABLE_CIRCUIT_CODEGEN -> enableCircuitCodegen = value.expectAs()
        MetroOption.DIAGNOSTICS_RENDER_MODE ->
          diagnosticsRenderMode = DiagnosticsRenderMode.parse(value.expectAs<String>())
        MetroOption.GENERATE_STATIC_ANNOTATIONS -> generateStaticAnnotations = value.expectAs()
        MetroOption.ENABLE_RUNTIME_TRACING -> enableRuntimeTracing = value.expectAs()
        MetroOption.MEMBER_NAMING_STRATEGY ->
          memberNamingStrategy =
            MemberNamingStrategy.valueOf(value.expectAs<String>().uppercase(Locale.US))
        MetroOption.CUSTOM_CONTRIBUTES_INTO_SET ->
          customContributesIntoSetAnnotations.addAll(value.expectAs<Set<ClassId>>())
      }
    }

    public fun build(): MetroOptions {
      if (debug) {
        enabledLoggers += MetroLogger.Type.entries
      }
      return MetroOptions(
        debug = debug,
        enabled = enabled,
        rawReportsDestination = reportsDestination,
        rawTraceDestination = traceDestination,
        generateAssistedFactories = generateAssistedFactories,
        enableTopLevelFunctionInjection = enableTopLevelFunctionInjection,
        generateContributionHints = generateContributionHints,
        generateContributionHintsInFir = generateContributionHintsInFir,
        generateClassesInIr = generateClassesInIr,
        shrinkUnusedBindings = shrinkUnusedBindings,
        statementsPerInitFun = statementsPerInitFun,
        enableGraphSharding = enableGraphSharding,
        keysPerGraphShard = keysPerGraphShard,
        mergedSupertypeChunkSize = mergedSupertypeChunkSize,
        enableSwitchingProviders = enableSwitchingProviders,
        publicScopedProviderSeverity = publicScopedProviderSeverity,
        nonPublicContributionSeverity = nonPublicContributionSeverity,
        optionalBindingBehavior = optionalBindingBehavior,
        warnOnInjectAnnotationPlacement = warnOnInjectAnnotationPlacement,
        interopAnnotationsNamedArgSeverity = interopAnnotationsNamedArgSeverity,
        unusedGraphInputsSeverity = unusedGraphInputsSeverity,
        enabledLoggers = enabledLoggers,
        enableDaggerRuntimeInterop = enableDaggerRuntimeInterop,
        enableGuiceRuntimeInterop = enableGuiceRuntimeInterop,
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
        enableKlibParamsCheck = enableKlibParamsCheck,
        patchKlibParams = patchKlibParams,
        forceEnableFirInIde = forceEnableFirInIde,
        pluginOrderSet = pluginOrderSet,
        compilerVersion = compilerVersion,
        compilerVersionAliases = compilerVersionAliases,
        parallelThreads = parallelThreads,
        bufferedIcTracking = bufferedIcTracking,
        enableProviderInlining = enableProviderInlining,
        enableFunctionProviders = enableFunctionProviders,
        desugaredProviderSeverity =
          if (enableFunctionProviders) {
            desugaredProviderSeverity
          } else {
            DiagnosticSeverity.NONE
          },
        enableKClassToClassInterop = enableKClassToClassInterop,
        generateContributionProviders = generateContributionProviders,
        enableCircuitCodegen = enableCircuitCodegen,
        enableHiltInterop = enableHiltInterop,
        diagnosticsRenderMode = diagnosticsRenderMode,
        generateStaticAnnotations = generateStaticAnnotations,
        enableRuntimeTracing = enableRuntimeTracing,
        memberNamingStrategy = memberNamingStrategy,
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
      val guicePackage = FqName("com.google.inject")
      val guiceMultibindingsPackage = FqName("com.google.inject.multibindings")
      val guiceAssistedInjectPackage = FqName("com.google.inject.assistedinject")
      val guiceNamePackage = FqName("com.google.inject.name")
      val internalName = Name.identifier("internal")
    }
  }

  public object SystemProperties {
    public val SHORTEN_LOCATIONS: Boolean =
      System.getProperty("metro.shortLocations", "false").toBoolean()

    /** Overrides [MetroOptions.diagnosticsRenderMode] when set. */
    public val DIAGNOSTICS_RENDER_MODE: DiagnosticsRenderMode? =
      System.getProperty("metro.diagnosticsRenderMode")?.let(DiagnosticsRenderMode::parse)
  }

  public companion object {
    public fun builder(): Builder = Builder()

    public fun buildOptions(body: Builder.() -> Unit): MetroOptions {
      return Builder().apply(body).build()
    }
  }

  public enum class DiagnosticSeverity {
    NONE,
    WARN,
    ERROR,

    /**
     * Like [WARN], but only reports when Metro is running inside an IDE FirSession. CLI
     * compilations treat this as [NONE].
     *
     * Useful for diagnostics you only want to surface to readers in the IDE without emitting
     * compiler warnings in real (CLI) compilations.
     */
    IDE_WARN,

    /**
     * Like [ERROR], but only reports when Metro is running inside an IDE FirSession. CLI
     * compilations treat this as [NONE].
     *
     * Useful for diagnostics you only want to surface to readers in the IDE without failing real
     * (CLI) compilations.
     */
    IDE_ERROR;

    public val isEnabled: Boolean
      get() = this != NONE

    public val isIdeOnly: Boolean
      get() = this == IDE_ERROR || this == IDE_WARN

    /**
     * Resolves this severity against the current environment. [IDE_WARN] and [IDE_ERROR] resolve to
     * [WARN] or [ERROR] respectively only when [isIde] is true; otherwise they resolve to [NONE].
     * All other values resolve to themselves.
     */
    public fun resolve(isIde: Boolean): DiagnosticSeverity =
      when (this) {
        NONE,
        WARN,
        ERROR -> this
        IDE_WARN -> if (isIde) WARN else NONE
        IDE_ERROR -> if (isIde) ERROR else NONE
      }
  }
}

internal object ClassIdSerializer : KSerializer<ClassId> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("ClassId", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: ClassId) {
    encoder.encodeString(value.asString())
  }

  override fun deserialize(decoder: Decoder): ClassId {
    return ClassId.fromString(decoder.decodeString(), false)
  }
}

private inline fun <reified T : Any> Any.expectAs(): T {
  return this as? T ?: error("Expected $this to be of type ${T::class.qualifiedName}")
}

private fun Any.diagnosticSeverity(): MetroOptions.DiagnosticSeverity {
  return MetroOptions.DiagnosticSeverity.valueOf(expectAs<String>().uppercase(Locale.US))
}
