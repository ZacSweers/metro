// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

public class MetroGradleSubplugin : KotlinCompilerPluginSupportPlugin {

  override fun apply(target: Project) {
    target.extensions.create("metro", MetroPluginExtension::class.java)
  }

  override fun getCompilerPluginId(): String = PLUGIN_ID

  override fun getPluginArtifact(): SubpluginArtifact =
      SubpluginArtifact(groupId = "dev.zacsweers.metro", artifactId = "compiler", version = VERSION)

  override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
    val project = kotlinCompilation.target.project

    // Check version and show warning by default.
    val checkVersions =
        project.extensions
            .getByType(MetroPluginExtension::class.java)
            .enableKotlinVersionCompatibilityChecks
            .getOrElse(true)
    if (checkVersions) {
      val metroVersion = VersionNumber.parse(BASE_KOTLIN_VERSION)
      val kotlinVersion = VersionNumber.parse(project.getKotlinPluginVersion())
      if (metroVersion < kotlinVersion) {
        project.logger.warn(
            "Metro '$VERSION' is too old for Kotlin '$kotlinVersion'. " +
                "Please upgrade Metro or downgrade Kotlin to '$BASE_KOTLIN_VERSION'.")
      } else if (metroVersion > kotlinVersion) {
        project.logger.warn(
            "Metro '$VERSION' is too new for Kotlin '$kotlinVersion'. " +
                "Please upgrade Kotlin to '$BASE_KOTLIN_VERSION'.")
      }
    }

    return true
  }

  override fun applyToCompilation(
      kotlinCompilation: KotlinCompilation<*>
  ): Provider<List<SubpluginOption>> {
    val project = kotlinCompilation.target.project
    val extension = project.extensions.getByType(MetroPluginExtension::class.java)

    project.dependencies.add(
        kotlinCompilation.implementationConfigurationName,
        "dev.zacsweers.metro:runtime:$VERSION",
    )
    if (kotlinCompilation.implementationConfigurationName == "metadataCompilationImplementation") {
      project.dependencies.add("commonMainImplementation", "dev.zacsweers.metro:runtime:$VERSION")
    }

    val isJvmTarget =
        kotlinCompilation.target.platformType == KotlinPlatformType.jvm ||
            kotlinCompilation.target.platformType == KotlinPlatformType.androidJvm
    if (isJvmTarget && extension.interop.enableDaggerRuntimeInterop.getOrElse(false)) {
      project.dependencies.add(
          kotlinCompilation.implementationConfigurationName,
          "dev.zacsweers.metro:interop-dagger:$VERSION",
      )
    }

    return project.provider {
      buildList {
        add(lazyOption("enabled", extension.enabled))
        add(lazyOption("debug", extension.debug))
        add(lazyOption("public-provider-severity", extension.publicProviderSeverity))
        add(lazyOption("generate-assisted-factories", extension.generateAssistedFactories))
        add(lazyOption("generate-hint-properties", extension.generateHintProperties))
        add(
            lazyOption(
                "enable-top-level-function-injection",
                extension.enableTopLevelFunctionInjection,
            ))
        extension.reportsDestination.orNull
            ?.let { FilesSubpluginOption("reports-destination", listOf(it.asFile)) }
            ?.let(::add)

        if (isJvmTarget) {
          add(
              SubpluginOption(
                  "enable-dagger-runtime-interop",
                  extension.interop.enableDaggerRuntimeInterop.getOrElse(false).toString(),
              ))
        }

        with(extension.interop) {
          provider
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-provider", value = it.joinToString(":")) }
              ?.let(::add)
          lazy
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-lazy", value = it.joinToString(":")) }
              ?.let(::add)
          assisted
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-assisted", value = it.joinToString(":")) }
              ?.let(::add)
          assistedFactory
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-assisted-factory", value = it.joinToString(":")) }
              ?.let(::add)
          assistedInject
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-assisted-inject", value = it.joinToString(":")) }
              ?.let(::add)
          binds
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-binds", value = it.joinToString(":")) }
              ?.let(::add)
          contributesTo
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-contributes-to", value = it.joinToString(":")) }
              ?.let(::add)
          contributesBinding
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-contributes-binding", value = it.joinToString(":")) }
              ?.let(::add)
          contributesIntoSet
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-contributes-into-set", value = it.joinToString(":")) }
              ?.let(::add)
          elementsIntoSet
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-elements-into-set", value = it.joinToString(":")) }
              ?.let(::add)
          graph
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-graph", value = it.joinToString(":")) }
              ?.let(::add)
          graphFactory
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-graph-factory", value = it.joinToString(":")) }
              ?.let(::add)
          inject
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-inject", value = it.joinToString(":")) }
              ?.let(::add)
          intoMap
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-into-map", value = it.joinToString(":")) }
              ?.let(::add)
          intoSet
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-into-set", value = it.joinToString(":")) }
              ?.let(::add)
          mapKey
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-map-key", value = it.joinToString(":")) }
              ?.let(::add)
          multibinds
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-multibinds", value = it.joinToString(":")) }
              ?.let(::add)
          provides
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-provides", value = it.joinToString(":")) }
              ?.let(::add)
          qualifier
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-qualifier", value = it.joinToString(":")) }
              ?.let(::add)
          scope
              .getOrElse(emptySet())
              .takeUnless { it.isEmpty() }
              ?.let { SubpluginOption("custom-scope", value = it.joinToString(":")) }
              ?.let(::add)
        }
      }
    }
  }
}

@JvmName("booleanPluginOptionOf")
private fun lazyOption(key: String, value: Provider<Boolean>): SubpluginOption =
    lazyOption(key, value.map { it.toString() })

@JvmName("enumPluginOptionOf")
private fun <T : Enum<T>> lazyOption(key: String, value: Provider<T>): SubpluginOption =
    lazyOption(key, value.map { it.name })

private fun lazyOption(key: String, value: Provider<String>): SubpluginOption =
    SubpluginOption(key, lazy(LazyThreadSafetyMode.NONE) { value.get() })
