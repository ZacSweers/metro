// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.SetProperty
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.tooling.core.KotlinToolingVersion

@MetroExtensionMarker
public abstract class MetroPluginExtension
@Inject
constructor(
  compilerVersion: Provider<KotlinToolingVersion>,
  layout: ProjectLayout,
  objects: ObjectFactory,
  private val providers: ProviderFactory,
) {

  public val interop: InteropHandler = objects.newInstance(InteropHandler::class.java)

  public val compilerOptions: CompilerOptionsHandler =
    objects.newInstance(CompilerOptionsHandler::class.java)

  /** Enables Metro for this project. */
  public val enabled: Property<Boolean> = objects.booleanProperty("metro.enabled", true)

  /** Automatically adds Metro runtime artifact dependencies. */
  public val automaticallyAddRuntimeDependencies: Property<Boolean> =
    objects.booleanProperty("metro.automaticallyAddRuntimeDependencies", true)

  /** Maximum errors to report before exiting IR processing. Default is 20. Must be > 0. */
  public val maxIrErrors: Property<Int> = objects.intProperty("metro.maxIrErrors", 20)

  /**
   * Enables debug logging for this project.
   *
   * Optionally, you can specify a `metro.debug` Gradle property to enable this globally.
   */
  public val debug: Property<Boolean> = objects.booleanProperty("metro.debug", false)

  /**
   * Generates assisted factories automatically for injected constructors with assisted parameters.
   * See the kdoc on `AssistedFactory` for more details.
   */
  @RequiresIdeSupport
  public val generateAssistedFactories: Property<Boolean> =
    objects.booleanProperty("metro.generateAssistedFactories", false)

  /**
   * Enables injection for top-level functions. See the kdoc on `Inject` for more details.
   *
   * **Warnings**
   * - Prior to Kotlin 2.3.20-Beta1, top-level function injection is only compatible with
   *   jvm/android targets.
   * - Prior to Kotlin 2.3.20-Beta1, top-level function injection is not yet compatible with
   *   incremental compilation on any platform
   * - Kotlin/JS does not support this with incremental compilation enabled. See
   *   https://youtrack.jetbrains.com/issue/KT-82395
   */
  @RequiresIdeSupport
  @DelicateMetroGradleApi(
    "Top-level function injection is experimental and does not work yet in all cases. See the kdoc."
  )
  public val enableTopLevelFunctionInjection: Property<Boolean> =
    objects
      .booleanProperty()
      .convention(
        compilerVersion.map {
          // Kotlin 2.3.20-Beta1, top-level declaration generation is supported on all platforms
          // except JS.
          // https://youtrack.jetbrains.com/issue/KT-82395
          // https://youtrack.jetbrains.com/issue/KT-82989
          KotlinVersions.supportsTopLevelFirGen(it)
        }
      )

  /**
   * Generates contribution hints in IR.
   *
   * This does not have a convention default set here as it actually depends on the platform. You
   * can set a value to force it to one or the other, otherwise if unset it will default to the
   * default for each compilation's platform type.
   */
  public val generateContributionHints: Property<Boolean> = objects.booleanProperty()

  /**
   * Generates contribution hints in FIR. Requires [generateContributionHints] to be true.
   *
   * **Warnings**
   * - Prior to Kotlin 2.3.20-Beta1, FIR contribution hint generation is only compatible with
   *   jvm/android targets.
   * - Prior to Kotlin 2.3.20-Beta1, FIR contribution hint generation is not yet compatible with
   *   incremental compilation on any platform
   * - Kotlin/JS does not support this with incremental compilation enabled. See
   *   https://youtrack.jetbrains.com/issue/KT-82395
   */
  @ExperimentalMetroGradleApi // Will eventually be the default and removed
  @DelicateMetroGradleApi(
    "FIR contribution hint generation is experimental and does not work yet in all cases. See the kdoc."
  )
  public val generateContributionHintsInFir: Property<Boolean> =
    objects
      .booleanProperty()
      .convention(
        compilerVersion.map {
          // Kotlin 2.3.20-Beta1, FIR hint generation is fully supported on all platforms.
          // JS is further gated on incremental compilation being disabled in MetroGradleSubplugin.
          // https://youtrack.jetbrains.com/issue/KT-82395
          // https://youtrack.jetbrains.com/issue/KT-82989
          KotlinVersions.supportsTopLevelFirGen(it)
        }
      )

  /**
   * Generates metadata-visible hidden classes in IR instead of FIR when supported by the Kotlin
   * compiler.
   */
  @ExperimentalMetroGradleApi
  public val generateClassesInIr: Property<Boolean> =
    objects
      .booleanProperty("metro.generateClassesInIr", false)
      .convention(compilerVersion.map { KotlinVersions.supportsIrClassGeneration(it) })

  /**
   * Sets the platforms for which contribution hints will be generated. If not set, defaults are
   * computed per-platform and per Kotlin version based on known compatible combinations.
   *
   * **Warnings** Prior to Kotlin 2.3.20, contribution hint generation is
   * - ...only compatible with jvm/android targets.
   * - ...does not support incremental compilation on any targets.
   *
   * Kotlin/JS does not support this with incremental compilation enabled. See
   * https://youtrack.jetbrains.com/issue/KT-82395
   */
  @ExperimentalMetroGradleApi // This may eventually be removed
  @DelicateMetroGradleApi(
    "Contribution hint generation does not work yet in all platforms on all Kotlin versions. See the kdoc."
  )
  public val supportedHintContributionPlatforms: SetProperty<KotlinPlatformType> =
    objects
      .setProperty(KotlinPlatformType::class.javaObjectType)
      .convention(
        compilerVersion.map { version ->
          if (KotlinVersions.supportsTopLevelFirGen(version)) {
            // Kotlin 2.3.20, all platforms are supported. JS is further gated on
            // incremental compilation being disabled in MetroGradleSubplugin.
            // https://youtrack.jetbrains.com/issue/KT-82395
            KotlinPlatformType.entries.toSet()
          } else {
            // Only jvm/android work prior to Kotlin 2.3.20
            setOf(KotlinPlatformType.common, KotlinPlatformType.jvm, KotlinPlatformType.androidJvm)
          }
        }
      )

  /**
   * Maximum statements per init method when chunking field initializers. Default is 25. Must
   * be > 0.
   */
  public val statementsPerInitFun: Property<Int> =
    objects.intProperty("metro.statementsPerInitFun", 25)

  /** Shards generated binding graphs. Enabled by default. */
  public val enableGraphSharding: Property<Boolean> =
    objects.booleanProperty("metro.enableGraphSharding", true)

  /**
   * Maximum binding keys per graph shard when sharding is enabled. Default is 2000. Must be > 0.
   */
  public val keysPerGraphShard: Property<Int> = objects.intProperty("metro.keysPerGraphShard", 2000)

  /**
   * Uses SwitchingProviders for deferred class loading. This reduces graph initialization time by
   * deferring bindings' class init until the binding is requested.
   *
   * This is analogous to Dagger's `fastInit` option.
   *
   * Only use this after benchmarking a meaningful improvement, as it comes with the same tradeoffs
   * (always holding a graph instance ref, etc.).
   *
   * Disabled by default.
   */
  public val enableSwitchingProviders: Property<Boolean> =
    objects.booleanProperty("metro.enableSwitchingProviders", false)

  /** Optional binding behavior. Default is [OptionalBindingBehavior.DEFAULT]. */
  public val optionalBindingBehavior: Property<OptionalBindingBehavior> =
    objects
      .property(OptionalBindingBehavior::class.java)
      .convention(OptionalBindingBehavior.DEFAULT)

  /**
   * Severity for public scoped-provider diagnostics. See the kdoc on `Provides` for more details.
   */
  public val publicScopedProviderSeverity: Property<DiagnosticSeverity> =
    objects.enumProperty<DiagnosticSeverity>(
      "publicScopedProviderSeverity",
      DiagnosticSeverity.NONE,
    )

  /**
   * Severity for non-public `@Contributes*` declaration diagnostics. This includes declarations
   * that are `internal`, `private`, `protected`, nested in non-public classes, etc.
   *
   * Note that if the scope argument to the annotation is itself a non-public class, the check will
   * not report, regardless of severity, since the contribution is assumed to be intentionally
   * internal.
   *
   * Disabled by default.
   */
  public val nonPublicContributionSeverity: Property<DiagnosticSeverity> =
    objects.enumProperty<DiagnosticSeverity>(
      "nonPublicContributionSeverity",
      DiagnosticSeverity.NONE,
    )

  /**
   * Enables Kotlin version compatibility checks. Defaults to true or the value of the
   * `metro.version.check` Gradle property.
   */
  public val enableKotlinVersionCompatibilityChecks: Property<Boolean> =
    objects.booleanProperty("metro.version.check", true)

  /**
   * Suggests moving `@Inject`/`@AssistedInject` to the class when it has only one constructor.
   * Enabled by default.
   */
  public val warnOnInjectAnnotationPlacement: Property<Boolean> =
    objects.booleanProperty("metro.warnOnInjectAnnotationPlacement", true)

  /**
   * Severity for interop annotations that use positional arguments instead of named arguments.
   *
   * Disabled by default as this can be quite noisy in a codebase that uses a lot of interop.
   */
  public val interopAnnotationsNamedArgSeverity: Property<DiagnosticSeverity> =
    objects.enumProperty<DiagnosticSeverity>(
      "interopAnnotationsNamedArgSeverity",
      DiagnosticSeverity.NONE,
    )

  /**
   * Severity for unused graph inputs, such as factory parameters and directly included binding
   * containers that are not used by the graph.
   *
   * WARN by default.
   *
   * Note: [DiagnosticSeverity.IDE_WARN] and [DiagnosticSeverity.IDE_ERROR] are **not** supported
   * here because unused-input detection only runs during IR (a CLI-only phase). Attempting to set
   * an IDE-only severity will fail the build with a validation error.
   */
  public val unusedGraphInputsSeverity: Property<DiagnosticSeverity> =
    objects.enumProperty<DiagnosticSeverity>("unusedGraphInputsSeverity", DiagnosticSeverity.WARN)

  /**
   * Treats `@Contributes*` annotations, except `@ContributesTo`, as implicit `@Inject` annotations.
   *
   * Enabled by default.
   */
  public val contributesAsInject: Property<Boolean> =
    objects.booleanProperty("metro.contributesAsInject", true)

  /**
   * Checks klib parameter qualifiers.
   *
   * This is automatically enabled for Kotlin versions `[2.3.0, 2.3.20-Beta2)` and disabled
   * otherwise.
   *
   * See https://github.com/ZacSweers/metro/issues/1556 for more information.
   */
  @ExperimentalMetroGradleApi // Will eventually be removed after 2.3.20
  public val enableKlibParamsCheck: Property<Boolean> =
    objects
      .booleanProperty()
      .convention(
        compilerVersion.map {
          it >= KotlinVersions.kotlin230 && it < KotlinVersions.kotlin2320Beta2
        }
      )

  /**
   * Patches klib parameter qualifiers to work around a kotlinc bug. Only applies when
   * [enableKlibParamsCheck] is also enabled.
   *
   * When enabled, Metro will patch the affected parameter qualifiers at compile time and emit a
   * warning instead of an error.
   *
   * See https://github.com/ZacSweers/metro/issues/1556 for more information.
   */
  @ExperimentalMetroGradleApi // Will eventually be removed after 2.3.20
  public val patchKlibParams: Property<Boolean> =
    objects.booleanProperty("metro.patchKlibParams", true)

  /**
   * Enables Metro FIR extensions in the IDE even when the compat layer cannot be determined.
   *
   * This is useful when working with IDE versions where Metro cannot automatically detect the
   * correct compatibility layer.
   *
   * Disabled by default.
   */
  @DangerousMetroGradleApi("This could break analysis in your IDE if you force-enable!")
  public val forceEnableFirInIde: Property<Boolean> =
    objects.booleanProperty("metro.forceEnableFirInIde", false)

  /**
   * Override the Kotlin compiler version Metro operates with.
   *
   * If set, Metro will behave as if running in this Kotlin environment (e.g., "2.3.20-dev-1234").
   * This is useful for testing or working around version detection issues.
   *
   * Null by default (uses the detected runtime Kotlin version).
   */
  @DangerousMetroGradleApi("This could break Metro's compatibility layer!")
  public val compilerVersion: Property<String> = objects.metroProperty("metro.compilerVersion", "")

  /**
   * Compiler version aliases mapping fake IDE versions to their real compiler versions.
   *
   * This is useful for IDE builds (e.g., Android Studio canary) that report a fake Kotlin compiler
   * version. When Metro detects a compiler version that matches an alias key, it will use the
   * corresponding value as the real version.
   *
   * User-defined aliases take priority over built-in aliases.
   *
   * Empty by default.
   */
  public val compilerVersionAliases: MapProperty<String, String> =
    objects.mapProperty(String::class.java, String::class.java).convention(emptyMap())

  /**
   * Treats `() -> T` as a provider type.
   *
   * When enabled, `() -> T` can defer dependency retrieval. Existing `Provider<T>` values also work
   * on JVM, Native, and Wasm because `Provider<T>` implements `() -> T` on those platforms.
   *
   * Note: On JS, `Provider<T>` does not implement `() -> T`, so an ad-hoc wrapping lambda is
   * generated.
   *
   * Enabled by default.
   */
  public val enableFunctionProviders: Property<Boolean> =
    objects.booleanProperty("metro.enableFunctionProviders", true)

  /**
   * Severity for desugared `Provider<T>` function types. Prefer `() -> T`.
   *
   * Only applies when [enableFunctionProviders] is enabled; treated as [DiagnosticSeverity.NONE]
   * otherwise.
   *
   * `WARN` by default.
   */
  public val desugaredProviderSeverity: Property<DiagnosticSeverity> =
    objects.enumProperty<DiagnosticSeverity>("desugaredProviderSeverity", DiagnosticSeverity.WARN)

  /**
   * Build-output rendering mode for diagnostics. See the docs on [DiagnosticsRenderMode] for
   * details.
   *
   * The compiler only ever receives a concrete `PLAIN`/`RICH` value, [DiagnosticsRenderMode.AUTO]
   * resolves to one of them.
   */
  @ExperimentalMetroGradleApi
  public val diagnosticsRenderMode: Property<DiagnosticsRenderMode> =
    objects.enumProperty<DiagnosticsRenderMode>("diagnosticsRenderMode", DiagnosticsRenderMode.AUTO)

  /**
   * Treats `java.lang.Class` and `kotlin.reflect.KClass` as interchangeable in multibinding map key
   * types, matching Kotlin's own annotation compilation behavior. This only applies to map keys
   * because these are the only scenario where annotation arguments are materialized into
   * non-annotation code (i.e. `@ClassKey(Foo::class) -> Map<Class<*>, V>`).
   *
   * Disabled by default because this is purely for annotations interop and potentially comes at
   * some runtime overhead cost to interop since `KClass` types are still used under the hood and
   * must be mapped in some cases. It's recommended to migrate these to `KClass` and call `.java`
   * where necessary if possible.
   */
  public val enableKClassToClassMapKeyInterop: Property<Boolean> =
    objects.booleanProperty("metro.enableKClassToClassMapKeyInterop", false)

  /**
   * Generates top-level contribution provider classes with `@Provides` functions instead of nested
   * binding containers with `@Binds` callables for `@ContributesBinding`, `@ContributesIntoSet`,
   * and `@ContributesIntoMap`.
   *
   * This works by wholly encapsulating the injected class behind a `@Provides` declaration, which
   * hides it from the graph and only exposes the bound type.
   *
   * A side benefit of this is that this allows implementation classes to remain `internal` since
   * the generated provider directly constructs them (which in turn allows for finer-grained IC).
   *
   * Disabled by default.
   */
  public val generateContributionProviders: Property<Boolean> =
    objects.booleanProperty("metro.generateContributionProviders", false)

  /**
   * Generates Metro-native Circuit bindings for `@CircuitInject` and `@SubCircuitInject` classes
   * and functions. Metro will generate `Ui.Factory`, `Presenter.Factory`, `SubUiFactory`, and
   * `SubPresenterFactory` implementations for annotated declarations.
   *
   * Note this will eventually move to a separate plugin.
   *
   * Disabled by default.
   */
  @ExperimentalMetroGradleApi
  public val enableCircuitCodegen: Property<Boolean> =
    objects.booleanProperty("metro.enableCircuitCodegen", false)

  /**
   * Enables bytecode/IR tracing for binding injections using androidx.tracing.
   *
   * Disabled by default.
   */
  @ExperimentalMetroGradleApi
  public val enableRuntimeTracing: Property<Boolean> =
    objects.booleanProperty("metro.enableRuntimeTracing", false)

  /**
   * Configures Metro options for misc compiler options that don't necessarily warrant dedicated API
   * controls.
   */
  public fun compilerOptions(action: Action<CompilerOptionsHandler>) {
    action.execute(compilerOptions)
  }

  @MetroExtensionMarker
  public abstract class CompilerOptionsHandler @Inject constructor(objects: ObjectFactory) {
    public val rawOptions: MapProperty<String, String> =
      objects.mapProperty(String::class.java, String::class.java)

    /** Puts a given [key] with [value] in [rawOptions]. */
    public fun put(key: String, value: String) {
      rawOptions.put(key, value)
    }

    /** Puts a given [key] with [value] in [rawOptions]. */
    public fun put(key: String, value: Provider<String>) {
      rawOptions.put(key, value)
    }

    /** Puts a given [key] with [value] in [rawOptions]. */
    public fun put(key: String, value: Boolean) {
      rawOptions.put(key, value.toString())
    }

    /** Enables a given [key] as a boolean flag in [rawOptions] */
    public fun enable(key: String) {
      rawOptions.put(key, "true")
    }

    /** Enables a given [key] as a boolean flag in [rawOptions] */
    public fun disable(key: String) {
      rawOptions.put(key, "false")
    }

    /** Puts a given diagnostic option [key] with [severity] in [rawOptions]. */
    public fun put(key: String, severity: DiagnosticSeverity) {
      rawOptions.put(key, severity.name)
    }
  }

  /**
   * If set, the Metro compiler will dump verbose report diagnostics about resolved dependency
   * graphs to the given destination. Outputs are per-compilation granularity (i.e.
   * `build/metro/main/...`).
   *
   * This behaves similar to the compose-compiler's option of the same name.
   *
   * This also enables the `generateMetroGraphMetadata` task, which will dump JSON representations
   * of all graphs per compilation in this project.
   *
   * This enables a nontrivial amount of logging and overhead and should only be used for debugging.
   *
   * Optionally, you can specify a `metro.reportsDestination` Gradle property whose value is a
   * _relative_ path from the project's **build** directory.
   */
  @DelicateMetroGradleApi(
    "This should only be used for debugging purposes and is not intended to be always enabled."
  )
  public val reportsDestination: DirectoryProperty =
    objects
      .directoryProperty()
      .convention(
        providers.gradleProperty("metro.reportsDestination").flatMap {
          layout.buildDirectory.dir(it)
        }
      )

  /**
   * If set, the Metro compiler will dump compiler trace information to the given destination.
   * Outputs are per-compilation granularity (i.e. `build/metro-traces/main/...`).
   *
   * Unlike [reportsDestination], this is designed for low-overhead performance tracing and can be
   * used in realistic scenarios without significantly impacting compilation performance.
   *
   * Optionally, you can specify a `metro.traceDestination` Gradle property whose value is a
   * _relative_ path from the project's **build** directory.
   */
  public val traceDestination: DirectoryProperty =
    objects
      .directoryProperty()
      .convention(
        providers.gradleProperty("metro.traceDestination").flatMap { layout.buildDirectory.dir(it) }
      )

  /**
   * Configures interop to support in generated code, usually from another DI framework.
   *
   * This is primarily for supplying custom annotations and custom runtime intrinsic types (i.e.
   * `Provider`).
   *
   * Note that the format of the class IDs should be in the Kotlin compiler `ClassId` format, e.g.
   * `kotlin/Map.Entry`.
   */
  public fun interop(action: Action<InteropHandler>) {
    action.execute(interop)
  }

  @MetroExtensionMarker
  public abstract class InteropHandler @Inject constructor(objects: ObjectFactory) {
    public abstract val enableDaggerRuntimeInterop: Property<Boolean>
    public abstract val enableGuiceRuntimeInterop: Property<Boolean>

    // Interop mode flags
    public val includeJavaxAnnotations: Property<Boolean> =
      objects.property(Boolean::class.java).convention(false)
    public val includeJakartaAnnotations: Property<Boolean> =
      objects.property(Boolean::class.java).convention(false)
    public val includeDaggerAnnotations: Property<Boolean> =
      objects.property(Boolean::class.java).convention(false)
    public val includeKotlinInjectAnnotations: Property<Boolean> =
      objects.property(Boolean::class.java).convention(false)
    public val includeAnvilAnnotations: Property<Boolean> =
      objects.property(Boolean::class.java).convention(false)
    public val includeKotlinInjectAnvilAnnotations: Property<Boolean> =
      objects.property(Boolean::class.java).convention(false)
    public val includeGuiceAnnotations: Property<Boolean> =
      objects.property(Boolean::class.java).convention(false)
    @ExperimentalMetroGradleApi
    public val includeHiltAnnotations: Property<Boolean> =
      objects.property(Boolean::class.java).convention(false)

    // Intrinsics
    public val provider: SetProperty<String> = objects.setProperty(String::class.java)
    public val lazy: SetProperty<String> = objects.setProperty(String::class.java)

    // Annotations
    public val assisted: SetProperty<String> = objects.setProperty(String::class.java)
    public val assistedFactory: SetProperty<String> = objects.setProperty(String::class.java)
    public val assistedInject: SetProperty<String> = objects.setProperty(String::class.java)
    public val binds: SetProperty<String> = objects.setProperty(String::class.java)
    public val contributesTo: SetProperty<String> = objects.setProperty(String::class.java)
    public val contributesBinding: SetProperty<String> = objects.setProperty(String::class.java)
    public val contributesIntoSet: SetProperty<String> = objects.setProperty(String::class.java)
    public val elementsIntoSet: SetProperty<String> = objects.setProperty(String::class.java)
    public val dependencyGraph: SetProperty<String> = objects.setProperty(String::class.java)
    public val dependencyGraphFactory: SetProperty<String> = objects.setProperty(String::class.java)
    public val graphExtension: SetProperty<String> = objects.setProperty(String::class.java)
    public val graphExtensionFactory: SetProperty<String> = objects.setProperty(String::class.java)
    public val inject: SetProperty<String> = objects.setProperty(String::class.java)
    public val intoMap: SetProperty<String> = objects.setProperty(String::class.java)
    public val intoSet: SetProperty<String> = objects.setProperty(String::class.java)
    public val mapKey: SetProperty<String> = objects.setProperty(String::class.java)
    public val multibinds: SetProperty<String> = objects.setProperty(String::class.java)
    public val provides: SetProperty<String> = objects.setProperty(String::class.java)
    public val qualifier: SetProperty<String> = objects.setProperty(String::class.java)
    public val scope: SetProperty<String> = objects.setProperty(String::class.java)
    public val bindingContainer: SetProperty<String> = objects.setProperty(String::class.java)
    public val origin: SetProperty<String> = objects.setProperty(String::class.java)
    public val optionalBinding: SetProperty<String> = objects.setProperty(String::class.java)

    // Interop markers
    public val enableDaggerAnvilInterop: Property<Boolean> = objects.property(Boolean::class.java)

    /** Recognizes javax.inject annotations. */
    public fun includeJavax() {
      includeJavaxAnnotations.set(true)
    }

    /** Recognizes jakarta.inject annotations. */
    public fun includeJakarta() {
      includeJakartaAnnotations.set(true)
    }

    /** Recognizes Dagger annotations. */
    public fun includeDagger(includeJavax: Boolean = true, includeJakarta: Boolean = true) {
      enableDaggerRuntimeInterop.set(true)
      includeDaggerAnnotations.set(true)
      if (!includeJavax && !includeJakarta) {
        System.err.println(
          "At least one of metro.interop.includeDagger.includeJavax or metro.interop.includeDagger.includeJakarta should be true"
        )
      }
      if (includeJavax) {
        includeJavax()
      }
      if (includeJakarta) {
        includeJakarta()
      }
    }

    /** Recognizes kotlin-inject annotations. */
    public fun includeKotlinInject() {
      includeKotlinInjectAnnotations.set(true)
    }

    /** Recognizes Anvil annotations for Dagger. */
    @JvmOverloads
    public fun includeAnvilForDagger(includeJavax: Boolean = true, includeJakarta: Boolean = true) {
      enableDaggerAnvilInterop.set(true)
      includeAnvilAnnotations.set(true)
      includeDagger(includeJavax, includeJakarta)
    }

    /** Recognizes Anvil annotations for kotlin-inject. */
    public fun includeAnvilForKotlinInject() {
      includeKotlinInject()
      includeKotlinInjectAnvilAnnotations.set(true)
    }

    /**
     * Recognizes Hilt `@InstallIn` / `@EntryPoint` interop. Hilt `@Module`s are also Dagger
     * `@Module`s, so this implicitly enables Dagger annotation interop.
     */
    @ExperimentalMetroGradleApi
    @JvmOverloads
    public fun includeHilt(includeJavax: Boolean = true, includeJakarta: Boolean = true) {
      includeHiltAnnotations.set(true)
      includeDagger(includeJavax, includeJakarta)
    }

    /** Recognizes Guice annotations. */
    public fun includeGuice() {
      enableGuiceRuntimeInterop.set(true)
      includeGuiceAnnotations.set(true)
    }
  }

  private fun ObjectFactory.booleanProperty(): Property<Boolean> {
    return property(Boolean::class.java)
  }

  private fun ObjectFactory.booleanProperty(
    name: String,
    defaultValue: Boolean,
  ): Property<Boolean> {
    return booleanProperty().propertyNameConventionImpl(name, defaultValue, String::toBoolean)
  }

  private fun ObjectFactory.intProperty(name: String, defaultValue: Int): Property<Int> {
    return property(Int::class.java).propertyNameConventionImpl(name, defaultValue, String::toInt)
  }

  private inline fun <reified T : Enum<T>> ObjectFactory.enumProperty(
    name: String,
    defaultValue: T,
  ): Property<T> {
    return property(T::class.java).propertyNameConventionImpl(name, defaultValue) { value ->
      enumValues<T>().find { it.name.equals(value, ignoreCase = true) }
        ?: error(
          "Value '$value' is not a valid input for metro.$name. Allowed values: ${enumValues<T>().joinToString { it.name }}"
        )
    }
  }

  private fun ObjectFactory.metroProperty(name: String, defaultValue: String): Property<String> {
    return property(String::class.java)
      .convention(
        providers.gradleProperty(name).orElse(providers.systemProperty(name)).orElse(defaultValue)
      )
  }

  private fun <T : Any> Property<T>.propertyNameConventionImpl(
    propertyName: String,
    defaultValue: T,
    mapper: (String) -> T,
  ): Property<T> {
    return convention(
      providers
        .gradleProperty(propertyName)
        .orElse(providers.systemProperty(propertyName))
        .map(mapper)
        .orElse(defaultValue)
    )
  }
}
