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

  /** Controls whether Metro's compiler plugin will be enabled on this project. */
  public val enabled: Property<Boolean> = objects.booleanProperty("metro.enabled", true)

  /** Controls whether Metro's runtime artifact dependencies should be automatically added. */
  public val automaticallyAddRuntimeDependencies: Property<Boolean> =
    objects.booleanProperty("metro.automaticallyAddRuntimeDependencies", true)

  /**
   * Maximum number of IR errors to report before exiting IR processing. Default is 20, must be > 0.
   */
  public val maxIrErrors: Property<Int> = objects.intProperty("metro.maxIrErrors", 20)

  /**
   * If enabled, the Metro compiler plugin will emit _extremely_ noisy debug logging.
   *
   * Optionally, you can specify a `metro.debug` gradle property to enable this globally.
   */
  public val debug: Property<Boolean> = objects.booleanProperty("metro.debug", false)

  /**
   * Enables whether the Metro compiler plugin will automatically generate assisted factories for
   * injected constructors with assisted parameters. See the kdoc on `AssistedFactory` for more
   * details.
   */
  public val generateAssistedFactories: Property<Boolean> =
    objects.booleanProperty("metro.generateAssistedFactories", false)

  @Deprecated("This does not do anything anymore and will be removed")
  public abstract val generateThrowsAnnotations: Property<Boolean>

  /**
   * Enables whether the Metro compiler plugin can inject top-level functions. See the kdoc on
   * `Inject` for more details.
   *
   * **Warnings**
   * - Prior to Kotlin 2.3.20-Beta1, top-level function injection is only compatible with
   *   jvm/android targets.
   * - Prior to Kotlin 2.3.20-Beta1, top-level function injection is not yet compatible with
   *   incremental compilation on any platform
   * - Kotlin/JS does not support this with incremental compilation enabled. See
   *   https://youtrack.jetbrains.com/issue/KT-82395
   */
  @DelicateMetroGradleApi(
    "Top-level function injection is experimental and does not work yet in all cases. See the kdoc."
  )
  public val enableTopLevelFunctionInjection: Property<Boolean> =
    objects
      .booleanProperty()
      .convention(
        compilerVersion.map {
          // Kotlin 2.3.20-Beta1, top-level declaration gen is fully supported on all platforms are
          // supported except JS
          // https://youtrack.jetbrains.com/issue/KT-82395
          // https://youtrack.jetbrains.com/issue/KT-82989
          KotlinVersions.supportsTopLevelFirGen(it)
        }
      )

  /**
   * Enable/disable contribution hint generation in IR for contributed types. Enabled by default.
   *
   * This does not have a convention default set here as it actually depends on the platform. You
   * can set a value to force it to one or the other, otherwise if unset it will default to the
   * default for each compilation's platform type.
   */
  public val generateContributionHints: Property<Boolean> = objects.booleanProperty()

  /**
   * Enable/disable contribution hint generation in FIR. Disabled by default as this is still
   * experimental. Requires [generateContributionHints] to be true.
   *
   * **Warnings**
   * - Prior to Kotlin 2.3.20-Beta1, FIR contribution hint gen is only compatible with jvm/android
   *   targets.
   * - Prior to Kotlin 2.3.20-Beta1, FIR contribution hint gen is not yet compatible with
   *   incremental compilation on any platform
   * - Kotlin/JS does not support this with incremental compilation enabled. See
   *   https://youtrack.jetbrains.com/issue/KT-82395
   */
  @DelicateMetroGradleApi(
    "FIR contribution hint gen is experimental and does not work yet in all cases. See the kdoc."
  )
  public val generateContributionHintsInFir: Property<Boolean> =
    objects
      .booleanProperty()
      .convention(
        compilerVersion.map {
          // Kotlin 2.3.20-Beta1, FIR hint gen is fully supported on all platforms.
          // JS is further gated on incremental compilation being disabled in MetroGradleSubplugin.
          // https://youtrack.jetbrains.com/issue/KT-82395
          // https://youtrack.jetbrains.com/issue/KT-82989
          KotlinVersions.supportsTopLevelFirGen(it)
        }
      )

  /**
   * Sets the platforms for which contribution hints will be generated. If not set, defaults are
   * computed per-platform and per Kotlin version based on known compatible combinations.
   *
   * **Warnings** Prior to Kotlin 2.3.20, contribution hint gen is
   * - ...only compatible with jvm/android targets.
   * - ...does not support incremental compilation on any targets.
   *
   * Kotlin/JS does not support this with incremental compilation enabled. See
   * https://youtrack.jetbrains.com/issue/KT-82395
   */
  @DelicateMetroGradleApi(
    "Contribution hint gen does not work yet in all platforms on all Kotlin versions. See the kdoc."
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
   * Enable/disable full validation of bindings. If enabled, _all_ declared `@Provides` and `@Binds`
   * bindings will be validated even if they are not used by the graph. Disabled by default.
   *
   * This is equivalent to Dagger's `-Adagger.fullBindingGraphValidation` option, though there are
   * no controls for diagnostic severity.
   */
  public val enableFullBindingGraphValidation: Property<Boolean> =
    objects.booleanProperty("metro.enableFullBindingGraphValidation", false)

  /**
   * If true changes the return type of generated Graph Factories from the declared interface type
   * to the generated Metro graph type. This is helpful for Dagger/Anvil interop.
   */
  public val enableGraphImplClassAsReturnType: Property<Boolean> =
    objects.booleanProperty("metro.enableGraphImplClassAsReturnType", false)

  /** Enable/disable shrinking of unused bindings. Enabled by default. */
  public val shrinkUnusedBindings: Property<Boolean> =
    objects.booleanProperty("metro.shrinkUnusedBindings", true)

  /** Enable/disable chunking of field initializers. Enabled by default. */
  public val chunkFieldInits: Property<Boolean> =
    objects.booleanProperty("metro.chunkFieldInits", true)

  /**
   * Maximum number of statements per init function when chunking field initializers. Default is 25,
   * must be > 0.
   */
  public val statementsPerInitFun: Property<Int> =
    objects.intProperty("metro.statementsPerInitFun", 25)

  /** Enable/disable graph sharding of binding graphs. Disabled by default. */
  @DelicateMetroGradleApi("Sharding is an experimental feature")
  public val enableGraphSharding: Property<Boolean> =
    objects.booleanProperty("metro.enableGraphSharding", false)

  /**
   * Maximum number of binding keys per graph shard when sharding is enabled. Default is 2000, must
   * be > 0.
   */
  @DelicateMetroGradleApi("Sharding is an experimental feature")
  public val keysPerGraphShard: Property<Int> = objects.intProperty("metro.keysPerGraphShard", 2000)

  /**
   * Enables switching providers for deferred class loading. This reduces graph initialization time
   * by deferring bindings' class init until it's actually requested.
   *
   * This is analogous to Dagger's `fastInit` option.
   *
   * You should really only use this if you've benchmarked it and measured a meaningful difference,
   * as it comes with the same tradeoffs (always holding a graph instance ref, etc.)
   *
   * Disabled by default.
   */
  @DelicateMetroGradleApi("Switching providers are an experimental feature")
  public val enableSwitchingProviders: Property<Boolean> =
    objects.booleanProperty("metro.enableSwitchingProviders", false)

  /**
   * Controls the behavior of optional dependencies on a per-compilation basis. Default is
   * [OptionalBindingBehavior.DEFAULT] mode.
   */
  public val optionalBindingBehavior: Property<OptionalBindingBehavior> =
    objects
      .property(OptionalBindingBehavior::class.java)
      .convention(OptionalBindingBehavior.DEFAULT)

  /** Enable/disable automatic transformation of providers to be private. Enabled by default. */
  @Deprecated(
    "Transforming providers to private is deprecated as it results in less efficient code generation"
  )
  public val transformProvidersToPrivate: Property<Boolean> =
    objects.booleanProperty("metro.transformProvidersToPrivate", false)

  /**
   * Configures the Metro compiler plugin to warn, error, or do nothing when it encounters `public`
   * provider callables. See the kdoc on `Provides` for more details.
   */
  public val publicProviderSeverity: Property<DiagnosticSeverity> =
    objects.property(DiagnosticSeverity::class.javaObjectType).convention(DiagnosticSeverity.NONE)

  /**
   * Configures the Metro compiler plugin to warn, error, or do nothing when it encounters
   * `@Contributes*`-annotated declarations that are non-public (`internal`, `private`, `protected`,
   * nested in non-public classes, etc.)
   *
   * Note that if the scope argument to the annotation is itself a non-public class, the check will
   * not report, regardless of severity, since the contribution is assumed to be intentionally
   * internal.
   *
   * Disabled by default.
   */
  public val nonPublicContributionSeverity: Property<DiagnosticSeverity> =
    objects.property(DiagnosticSeverity::class.javaObjectType).convention(DiagnosticSeverity.NONE)

  /**
   * Enable/disable Kotlin version compatibility checks. Defaults to true or the value of the
   * `metro.version.check` gradle property.
   */
  public val enableKotlinVersionCompatibilityChecks: Property<Boolean> =
    objects.booleanProperty("metro.version.check", true)

  /**
   * Enable/disable suggestion to lift @Inject to class when there is only one constructor. Enabled
   * by default.
   */
  public val warnOnInjectAnnotationPlacement: Property<Boolean> =
    objects.booleanProperty("metro.warnOnInjectAnnotationPlacement", true)

  /**
   * Configures the Metro compiler plugin to warn, error, or do nothing when it encounters interop
   * annotations using positional arguments instead of named arguments.
   *
   * Disabled by default as this can be quite noisy in a codebase that uses a lot of interop.
   */
  public val interopAnnotationsNamedArgSeverity: Property<DiagnosticSeverity> =
    objects.property(DiagnosticSeverity::class.javaObjectType).convention(DiagnosticSeverity.NONE)

  /**
   * Configures the Metro compiler plugin to warn, error, or do nothing when it encounters unused
   * graph inputs (graph factory parameters or directly included binding containers that are not
   * used by the graph).
   *
   * Disabled by default.
   */
  public val unusedGraphInputsSeverity: Property<DiagnosticSeverity> =
    objects.property(DiagnosticSeverity::class.javaObjectType).convention(DiagnosticSeverity.NONE)

  /**
   * If enabled, treats `@Contributes*` annotations (except ContributesTo) as implicit `@Inject`
   * annotations.
   *
   * Enabled by default.
   */
  public val contributesAsInject: Property<Boolean> =
    objects.booleanProperty("metro.contributesAsInject", true)

  /**
   * Enable/disable deduplication of injected parameters with the same type key in generated
   * factories. When enabled, if multiple constructor or member-inject parameters share the same type
   * key, only one parameter is generated in the factory.
   *
   * Disabled by default.
   */
  public val deduplicateInjectedParams: Property<Boolean> =
    objects.booleanProperty("metro.deduplicateInjectedParams", false)

  /**
   * Enable/disable klib parameter qualifier checking.
   *
   * This is automatically enabled for Kotlin versions `[2.3.0, 2.3.20-Beta2)` and disabled
   * otherwise.
   *
   * See https://github.com/ZacSweers/metro/issues/1556 for more information.
   */
  public val enableKlibParamsCheck: Property<Boolean> =
    objects
      .booleanProperty()
      .convention(
        compilerVersion.map {
          it >= KotlinVersions.kotlin230 && it < KotlinVersions.kotlin2320Beta2
        }
      )

  /**
   * Enable/disable patching of klib parameter qualifiers to work around a kotlinc bug. Only applies
   * when [enableKlibParamsCheck] is also enabled.
   *
   * When enabled, Metro will patch the affected parameter qualifiers at compile time and emit a
   * warning instead of an error.
   *
   * See https://github.com/ZacSweers/metro/issues/1556 for more information.
   */
  public val patchKlibParams: Property<Boolean> =
    objects.booleanProperty("metro.patchKlibParams", true)

  /**
   * Force enable Metro's FIR extensions in IDE even if the compat layer cannot be determined.
   *
   * This is useful when working with IDE versions where Metro cannot automatically detect the
   * correct compatibility layer.
   *
   * Disabled by default.
   */
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
   * Optionally, you can specify a `metro.reportsDestination` gradle property whose value is a
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
   * Optionally, you can specify a `metro.traceDestination` gradle property whose value is a
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

    /** Includes Javax annotations support. */
    public fun includeJavax() {
      includeJavaxAnnotations.set(true)
    }

    /** Includes Jakarta annotations support. */
    public fun includeJakarta() {
      includeJakartaAnnotations.set(true)
    }

    /** Includes Dagger annotations support. */
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

    /** Includes kotlin-inject annotations support. */
    public fun includeKotlinInject() {
      includeKotlinInjectAnnotations.set(true)
    }

    /** Includes Anvil annotations support for Dagger. */
    @JvmOverloads
    public fun includeAnvilForDagger(includeJavax: Boolean = true, includeJakarta: Boolean = true) {
      enableDaggerAnvilInterop.set(true)
      includeAnvilAnnotations.set(true)
      includeDagger(includeJavax, includeJakarta)
    }

    /** Includes Anvil annotations support for kotlin-inject. */
    public fun includeAnvilForKotlinInject() {
      includeKotlinInject()
      includeKotlinInjectAnvilAnnotations.set(true)
    }

    /** Includes Guice annotations support. */
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
