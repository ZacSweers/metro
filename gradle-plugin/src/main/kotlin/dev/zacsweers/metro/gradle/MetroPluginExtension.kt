// Copyright (C) 2021 Zac Sweers
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
   * If enabled, the Metro compiler plugin will emit _extremely_ noisy debug logging.
   *
   * Optionally, you can specify a `metro.debug` gradle property to enable this globally.
   */
  public val debug: Property<Boolean> =
    objects
      .property(Boolean::class.javaObjectType)
      .convention(providers.gradleProperty("metro.debug").map { it.toBoolean() }.orElse(false))

  /**
   * Configures the Metro compiler plugin to warn, error, or do nothing when it encounters `public`
   * provider callables. See the kdoc on `Provides` for more details.
   */
  public val publicProviderSeverity: Property<DiagnosticSeverity> =
    objects.property(DiagnosticSeverity::class.javaObjectType).convention(DiagnosticSeverity.NONE)

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

  /** Enable/disable hint property generation in IR for contributed types. Enabled by default. */
  public val generateHintProperties: Property<Boolean> =
    objects.property(Boolean::class.javaObjectType).convention(true)

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
    public val contributesGraphExtension: SetProperty<String> =
      objects.setProperty(String::class.java)
    public val contributesGraphExtensionFactory: SetProperty<String> =
      objects.setProperty(String::class.java)
    public val elementsIntoSet: SetProperty<String> = objects.setProperty(String::class.java)
    public val graph: SetProperty<String> = objects.setProperty(String::class.java)
    public val graphFactory: SetProperty<String> = objects.setProperty(String::class.java)
    public val inject: SetProperty<String> = objects.setProperty(String::class.java)
    public val intoMap: SetProperty<String> = objects.setProperty(String::class.java)
    public val intoSet: SetProperty<String> = objects.setProperty(String::class.java)
    public val mapKey: SetProperty<String> = objects.setProperty(String::class.java)
    public val multibinds: SetProperty<String> = objects.setProperty(String::class.java)
    public val provides: SetProperty<String> = objects.setProperty(String::class.java)
    public val qualifier: SetProperty<String> = objects.setProperty(String::class.java)
    public val scope: SetProperty<String> = objects.setProperty(String::class.java)

    // Interop markers
    public val enableDaggerAnvilInterop: Property<Boolean> = objects.property(Boolean::class.java)

    /** Includes Javax annotations support. */
    public fun includeJavax() {
      provider.add("javax/inject/Provider")
      inject.add("javax/inject/Inject")
      qualifier.add("javax/inject/Qualifier")
      scope.add("javax/inject/Scope")
    }

    /** Includes Jakarta annotations support. */
    public fun includeJakarta() {
      provider.add("jakarta/inject/Provider")
      inject.add("jakarta/inject/Inject")
      qualifier.add("jakarta/inject/Qualifier")
      scope.add("jakarta/inject/Scope")
    }

    /** Includes Dagger annotations support. */
    @JvmOverloads
    public fun includeDagger(includeJavax: Boolean = true, includeJakarta: Boolean = true) {
      enableDaggerRuntimeInterop.set(true)

      assisted.add("dagger/assisted/Assisted")
      assistedFactory.add("dagger/assisted/AssistedFactory")
      assistedInject.add("dagger/assisted/AssistedInject")
      binds.add("dagger/Binds")
      elementsIntoSet.add("dagger/multibindings/ElementsIntoSet")
      graph.add("dagger/Component")
      graphFactory.add("dagger/Component.Factory")
      intoMap.add("dagger/multibindings/IntoMap")
      intoSet.add("dagger/multibindings/IntoSet")
      lazy.addAll("dagger/Lazy")
      mapKey.add("dagger/MapKey")
      multibinds.add("dagger/multibindings/Multibinds")
      provides.addAll("dagger/Provides", "dagger/BindsInstance")
      provider.add("dagger/internal/Provider")

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
      inject.add("me/tatarka/inject/annotations/Inject")
      qualifier.add("me/tatarka/inject/annotations/Qualifier")
      scope.add("me/tatarka/inject/annotations/Scope")
      assisted.add("me/tatarka/inject/annotations/Assisted")
      graph.add("me/tatarka/inject/annotations/Component")
      intoMap.add("me/tatarka/inject/annotations/IntoMap")
      intoSet.add("me/tatarka/inject/annotations/IntoSet")
      provides.add("me/tatarka/inject/annotations/Provides")
    }

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
        graph.add("com/squareup/anvil/annotations/MergeComponent")
        graphFactory.add("com/squareup/anvil/annotations/MergeComponent.Factory")
        contributesTo.add("com/squareup/anvil/annotations/ContributesTo")
        contributesBinding.add("com/squareup/anvil/annotations/ContributesBinding")
        contributesIntoSet.add("com/squareup/anvil/annotations/ContributesMultibinding")
        contributesGraphExtension.add("com/squareup/anvil/annotations/ContributesSubcomponent")
        // Anvil for Dagger doesn't have ContributesSubcomponent.Factory
      }
      if (includeKotlinInjectAnvil) {
        graph.add("software/amazon/lastmile/kotlin/inject/anvil/MergeComponent")
        contributesTo.add("software/amazon/lastmile/kotlin/inject/anvil/ContributesTo")
        contributesBinding.add("software/amazon/lastmile/kotlin/inject/anvil/ContributesBinding")
        contributesGraphExtension.add(
          "software/amazon/lastmile/kotlin/inject/anvil/ContributesSubcomponent"
        )
        contributesGraphExtensionFactory.add(
          "software/amazon/lastmile/kotlin/inject/anvil/ContributesSubcomponent.Factory"
        )
      }
    }
  }
}
