// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.SetProperty

@MetroExtensionMarker
public abstract class MetroPluginExtension
@Inject
constructor(layout: ProjectLayout, objects: ObjectFactory, providers: ProviderFactory) {

  public val interop: InteropHandler = objects.newInstance(InteropHandler::class.java)

  /** Controls whether Metro's compiler plugin will be enabled on this project. */
  public val enabled: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(true)

  /**
   * Maximum number of IR errors to report before exiting IR processing. Default is 20, must be > 0.
   */
  public val maxIrErrors: Property<Int> = objects.property(Int::class.javaObjectType).convention(20)

  /**
   * If enabled, the Metro compiler plugin will emit _extremely_ noisy debug logging.
   *
   * Optionally, you can specify a `metro.debug` gradle property to enable this globally.
   */
  public val debug: Property<Boolean> =
    objects
      .property(Boolean::class.javaObjectType)
      .convention(providers.gradleProperty("metro.debug").map { it.toBoolean() }.orElse(false))

  /**
   * Enables whether the Metro compiler plugin will automatically generate assisted factories for
   * injected constructors with assisted parameters. See the kdoc on `AssistedFactory` for more
   * details.
   */
  public val generateAssistedFactories: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(false)

  /**
   * Enables whether the Metro compiler plugin can inject top-level functions. See the kdoc on
   * `Inject` for more details.
   *
   * Be extra careful with this API, as top-level function injection is not compatible with
   * incremental compilation!
   */
  public val enableTopLevelFunctionInjection: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(false)

  /**
   * Enable/disable contribution hint generation in IR for contributed types. Enabled by default.
   *
   * This does not have a convention default set here as it actually depends on the platform. You
   * can set a value to force it to one or the other, otherwise if unset it will default to the
   * default for each compilation's platform type.
   */
  public val generateContributionHints: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType)

  /**
   * Enable/disable contribution hint generation in FIR for JVM compilations types. Disabled by
   * default. Requires [generateContributionHints] to be true
   */
  public val generateJvmContributionHintsInFir: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(false)

  @Deprecated("This is deprecated and no longer does anything. It will be removed in the future.")
  public val enableScopedInjectClassHints: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(false)

  /**
   * Enable/disable full validation of bindings. If enabled, _all_ declared `@Provides` and `@Binds`
   * bindings will be validated even if they are not used by the graph. Disabled by default.
   *
   * This is equivalent to Dagger's `-Adagger.fullBindingGraphValidation` option, though there are
   * no controls for diagnostic severity.
   */
  public val enableFullBindingGraphValidation: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(false)

  /**
   * If true changes the return type of generated Graph Factories from the declared interface type
   * to the generated Metro graph type. This is helpful for Dagger/Anvil interop.
   */
  public val enableGraphImplClassAsReturnType: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(false)

  @Deprecated(
    "Use enableFullBindingGraphValidation",
    ReplaceWith("enableFullBindingGraphValidation"),
  )
  public val enableStrictValidation: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(false)

  /** Enable/disable shrinking of unused bindings. Enabled by default. */
  public val shrinkUnusedBindings: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(true)

  /** Enable/disable chunking of field initializers. Enabled by default. */
  public val chunkFieldInits: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(true)

  /**
   * Maximum number of statements per init function when chunking field initializers. Default is 25,
   * must be > 0.
   */
  public val statementsPerInitFun: Property<Int> =
    objects.property(Int::class.javaObjectType).convention(25)

  @Suppress("DEPRECATION")
  @Deprecated("Use optionalBindingBehavior instead", ReplaceWith("optionalBindingBehavior"))
  public val optionalDependencyBehavior: Property<OptionalDependencyBehavior> =
    objects
      .property(OptionalDependencyBehavior::class.java)
      .convention(OptionalDependencyBehavior.DEFAULT)

  /**
   * Controls the behavior of optional dependencies on a per-compilation basis. Default is
   * [OptionalBindingBehavior.DEFAULT] mode.
   */
  public val optionalBindingBehavior: Property<OptionalBindingBehavior> =
    objects
      .property(OptionalBindingBehavior::class.java)
      .convention(OptionalBindingBehavior.DEFAULT)

  /** Enable/disable automatic transformation of providers to be private. Enabled by default. */
  public val transformProvidersToPrivate: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(true)

  /**
   * Configures the Metro compiler plugin to warn, error, or do nothing when it encounters `public`
   * provider callables. See the kdoc on `Provides` for more details.
   */
  public val publicProviderSeverity: Property<DiagnosticSeverity> =
    objects.property(DiagnosticSeverity::class.javaObjectType).convention(DiagnosticSeverity.NONE)

  /**
   * Enable/disable Kotlin version compatibility checks. Defaults to true or the value of the
   * `metro.version.check` gradle property.
   */
  public val enableKotlinVersionCompatibilityChecks: Property<Boolean> =
    objects
      .property(Boolean::class.javaObjectType)
      .convention(
        providers.gradleProperty("metro.version.check").map { it.toBoolean() }.orElse(true)
      )

  /**
   * Enable/disable suggestion to lift @Inject to class when there is only one constructor. Enabled
   * by default.
   */
  public val warnOnInjectAnnotationPlacement: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(true)

  /**
   * Configures the Metro compiler plugin to warn, error, or do nothing when it encounters interop
   * annotations using positional arguments instead of named arguments.
   *
   * Disabled by default as this can be quite noisy in a codebase that uses a lot of interop.
   */
  public val interopAnnotationsNamedArgSeverity: Property<DiagnosticSeverity> =
    objects.property(DiagnosticSeverity::class.javaObjectType).convention(DiagnosticSeverity.NONE)

  /**
   * If enabled, treats `@Contributes*` annotations (except ContributesTo) as implicit `@Inject`
   * annotations.
   *
   * Disabled by default.
   */
  public val contributesAsInject: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(false)

  /**
   * If set, the Metro compiler will dump report diagnostics about resolved dependency graphs to the
   * given destination.
   *
   * This behaves similar to the compose-compiler's option of the same name.
   *
   * Optionally, you can specify a `metro.reportsDestination` gradle property whose value is a
   * _relative_ path from the project's **build** directory.
   */
  public val reportsDestination: DirectoryProperty =
    objects
      .directoryProperty()
      .convention(
        providers.gradleProperty("metro.reportsDestination").flatMap {
          layout.buildDirectory.dir(it)
        }
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
    @Deprecated("This is deprecated and no longer does anything. It will be removed in the future.")
    public val contributesGraphExtension: SetProperty<String> =
      objects.setProperty(String::class.java)
    @Deprecated("This is deprecated and no longer does anything. It will be removed in the future.")
    public val contributesGraphExtensionFactory: SetProperty<String> =
      objects.setProperty(String::class.java)
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

    @Deprecated("Use one of the more specific includeAnvil*() functions instead.")
    @JvmOverloads
    public fun includeAnvil(
      includeDaggerAnvil: Boolean = true,
      includeKotlinInjectAnvil: Boolean = true,
    ) {
      check(includeDaggerAnvil || includeKotlinInjectAnvil) {
        "At least one of includeDaggerAnvil or includeKotlinInjectAnvil must be true"
      }
      enableDaggerAnvilInterop.set(includeDaggerAnvil)
      if (includeDaggerAnvil) {
        includeDagger()
        includeAnvilAnnotations.set(true)
      }
      if (includeKotlinInjectAnvil) {
        includeKotlinInject()
        includeKotlinInjectAnvilAnnotations.set(true)
      }
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
}
