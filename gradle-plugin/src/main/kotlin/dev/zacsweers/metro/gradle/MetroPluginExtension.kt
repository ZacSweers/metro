/*
 * Copyright (C) 2021 Zac Sweers
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
package dev.zacsweers.metro.gradle

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

public abstract class MetroPluginExtension @Inject constructor(objects: ObjectFactory) {

  public val customAnnotations: CustomAnnotations =
      objects.newInstance(CustomAnnotations::class.java)

  public val enabled: Property<Boolean> =
      objects.property(Boolean::class.javaObjectType).convention(true)

  public val debug: Property<Boolean> =
      objects.property(Boolean::class.javaObjectType).convention(false)

  public val publicProviderSeverity: Property<DiagnosticSeverity> =
      objects.property(DiagnosticSeverity::class.javaObjectType).convention(DiagnosticSeverity.NONE)

  public val generateAssistedFactories: Property<Boolean> =
      objects.property(Boolean::class.javaObjectType).convention(false)

  public val enableTopLevelFunctionInjection: Property<Boolean> =
      objects.property(Boolean::class.javaObjectType).convention(false)

  public abstract val reportsDestination: DirectoryProperty

  /**
   * Configures custom annotations to support in generated code, usually from another DI framework.
   *
   * Note that the format of the annotation should be in the Kotlin compiler `ClassId` format, e.g.
   * `kotlin/Map.Entry`.
   */
  public fun customAnnotations(action: Action<CustomAnnotations>) {
    action.execute(customAnnotations)
  }

  public abstract class CustomAnnotations @Inject constructor(objects: ObjectFactory) {
    public val assisted: SetProperty<String> = objects.setProperty(String::class.java)
    public val assistedFactory: SetProperty<String> = objects.setProperty(String::class.java)
    public val assistedInject: SetProperty<String> = objects.setProperty(String::class.java)
    public val binds: SetProperty<String> = objects.setProperty(String::class.java)
    public val contributesTo: SetProperty<String> = objects.setProperty(String::class.java)
    public val contributesBinding: SetProperty<String> = objects.setProperty(String::class.java)
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

    /** Includes Dagger annotations support. */
    @JvmOverloads
    public fun includeDagger(includeJavax: Boolean = true, includeJakarta: Boolean = true) {
      if (!includeJavax && !includeJakarta) {
        System.err.println(
            "At least one of metro.customAnnotations.includeDagger.includeJavax or metro.customAnnotations.includeDagger.includeJakarta should be true")
      }
      assisted.add("dagger/assisted/Assisted")
      assistedFactory.add("dagger/assisted/AssistedFactory")
      assistedInject.add("dagger/assisted/AssistedInject")
      binds.add("dagger/Binds")
      elementsIntoSet.add("dagger/multibindings/ElementsIntoSet")
      graph.add("dagger/Component")
      graphFactory.add("dagger/Component.Factory")
      intoMap.add("dagger/multibindings/IntoMap")
      intoSet.add("dagger/multibindings/IntoSet")
      mapKey.add("dagger/multibindings/MapKey")
      multibinds.add("dagger/multibindings/Multibinds")
      provides.addAll("dagger/Provides", "dagger/BindsInstance")
      if (includeJavax) {
        inject.add("javax/inject/Inject")
        qualifier.add("javax/inject/Qualifier")
        scope.add("javax/inject/Scope")
      }
      if (includeJakarta) {
        inject.add("jakarta/inject/Inject")
        qualifier.add("jakarta/inject/Qualifier")
        scope.add("jakarta/inject/Scope")
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
      if (includeDaggerAnvil) {
        graph.add("com/squareup/anvil/annotations/MergeComponent")
        graphFactory.add("com/squareup/anvil/annotations/MergeComponent.Factory")
        contributesTo.add("com/squareup/anvil/annotations/ContributesTo")
        contributesBinding.add("com/squareup/anvil/annotations/ContributesBinding")
      }
      if (includeKotlinInjectAnvil) {
        graph.add("software/amazon/lastmile/kotlin/inject/anvil/MergeComponent")
        contributesTo.add("software/amazon/lastmile/kotlin/inject/anvil/ContributesTo")
        contributesBinding.add("software/amazon/lastmile/kotlin/inject/anvil/ContributesBinding")
      }
    }
  }
}
