// Copyright (C) 2021 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import dev.zacsweers.metro.gradle.artifacts.GenerateGraphMetadataTask
import dev.zacsweers.metro.gradle.artifacts.MetroArtifactCopyTask
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

public class MetroGradleSubplugin : KotlinCompilerPluginSupportPlugin {
  private companion object {
    val gradleMetroKotlinVersion by
      lazy(LazyThreadSafetyMode.NONE) {
        KotlinVersion.fromVersion(BASE_KOTLIN_VERSION.substringBeforeLast('.'))
      }
  }

  override fun apply(target: Project) {
    target.extensions.create("metro", MetroPluginExtension::class.java, target.layout)

    val graphMetadataTask =
      target.tasks.register(GenerateGraphMetadataTask.NAME, GenerateGraphMetadataTask::class.java)
    graphMetadataTask.configure { task ->
      task.description = "Generates Metro graph metadata for ${target.path}"
      task.projectPath.convention(target.path)
      task.outputFile.convention(
        target.layout.buildDirectory.file("reports/metro/graphMetadata.json")
      )
    }
  }

  override fun getCompilerPluginId(): String = PLUGIN_ID

  override fun getPluginArtifact(): SubpluginArtifact {
    val version = System.getProperty("metro.compilerVersionOverride", VERSION)
    return SubpluginArtifact(
      groupId = "dev.zacsweers.metro",
      artifactId = "compiler",
      version = version,
    )
  }

  override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
    val project = kotlinCompilation.target.project

    // Check version and show warning by default.
    val checkVersions =
      project.extensions
        .getByType(MetroPluginExtension::class.java)
        .enableKotlinVersionCompatibilityChecks
        .getOrElse(true)
    if (checkVersions) {
      val kotlinVersionString = project.getKotlinPluginVersion()
      val kotlinVersion = VersionNumber.parse(kotlinVersionString)
      val supportedVersions = SUPPORTED_KOTLIN_VERSIONS.map(VersionNumber::parse)
      val minSupported = supportedVersions.min()
      val maxSupported = supportedVersions.max()

      val isSupported = kotlinVersion in minSupported..maxSupported
      if (!isSupported) {
        if (kotlinVersion < minSupported) {
          project.logger.lifecycle(
            """
              Metro '$VERSION' requires Kotlin ${SUPPORTED_KOTLIN_VERSIONS.first()} or later, but this build uses '$kotlinVersionString'.
              Please upgrade Kotlin to at least '${SUPPORTED_KOTLIN_VERSIONS.first()}'.
              Supported Kotlin versions: ${SUPPORTED_KOTLIN_VERSIONS.first()} - ${SUPPORTED_KOTLIN_VERSIONS.last()}
              You can also disable this warning via `metro.version.check=false` or setting the `metro.enableKotlinVersionCompatibilityChecks` DSL property.
            """
              .trimIndent()
          )
        } else {
          project.logger.lifecycle(
            """
              Metro '$VERSION' supports the following Kotlin versions: $SUPPORTED_KOTLIN_VERSIONS
              This build uses unrecognized version '$kotlinVersionString'.
              If you have any issues, please upgrade Metro (if applicable) or use a supported Kotlin version. See https://zacsweers.github.io/metro/latest/compatibility.
              You can also disable this warning via `metro.version.check=false` or setting the `metro.enableKotlinVersionCompatibilityChecks` DSL property.
            """
              .trimIndent()
          )
        }
      }
    }

    return true
  }

  override fun applyToCompilation(
    kotlinCompilation: KotlinCompilation<*>
  ): Provider<List<SubpluginOption>> {
    val project = kotlinCompilation.target.project
    val extension = project.extensions.getByType(MetroPluginExtension::class.java)

    val platformCanGenerateContributionHints =
      when (kotlinCompilation.platformType) {
        KotlinPlatformType.common,
        KotlinPlatformType.jvm,
        KotlinPlatformType.androidJvm -> true
        KotlinPlatformType.js,
        KotlinPlatformType.native,
        KotlinPlatformType.wasm -> false
      }

    // Ensure that the languageVersion is 2.x
    kotlinCompilation.compileTaskProvider.configure { task ->
      task.doFirst { innerTask ->
        val compilerOptions = (innerTask as KotlinCompilationTask<*>).compilerOptions
        val languageVersion = compilerOptions.languageVersion.orNull ?: return@doFirst
        check(languageVersion >= gradleMetroKotlinVersion) {
          "Compilation task '${innerTask.name}' targets language version '${languageVersion.version}' but Metro requires Kotlin '${gradleMetroKotlinVersion.version}' or later."
        }
      }
    }

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
    if (isJvmTarget) {
      if (extension.interop.enableDaggerRuntimeInterop.getOrElse(false)) {
        project.dependencies.add(
          kotlinCompilation.implementationConfigurationName,
          "dev.zacsweers.metro:interop-dagger:$VERSION",
        )
      }
      if (extension.interop.enableGuiceRuntimeInterop.getOrElse(false)) {
        project.dependencies.add(
          kotlinCompilation.implementationConfigurationName,
          "dev.zacsweers.metro:interop-guice:$VERSION",
        )
      }
    }

    val reportsDir = extension.reportsDestination.map { it.dir(kotlinCompilation.name) }

    if (extension.reportsDestination.isPresent) {
      val artifactsTask = MetroArtifactCopyTask.register(project, reportsDir, kotlinCompilation)

      project.tasks.withType(GenerateGraphMetadataTask::class.java).configureEach { task ->
        task.projectPath.set(project.path)
        task.compilationName.set(kotlinCompilation.name)
        task.graphJsonFiles.from(
          artifactsTask
            .flatMap { it.reportsDir.dir("graph-metadata") }
            .map { it.asFileTree.matching { it.include("*.json") } }
        )
      }
    }

    return project.provider {
      buildList {
        add(lazyOption("enabled", extension.enabled))
        add(lazyOption("max-ir-errors-count", extension.maxIrErrors))
        add(lazyOption("debug", extension.debug))
        add(lazyOption("generate-assisted-factories", extension.generateAssistedFactories))
        add(
          lazyOption(
            "generate-contribution-hints",
            extension.generateContributionHints.orElse(platformCanGenerateContributionHints),
          )
        )
        add(
          lazyOption(
            "generate-jvm-contribution-hints-in-fir",
            extension.generateJvmContributionHintsInFir,
          )
        )
        @Suppress("DEPRECATION")
        add(
          lazyOption(
            "enable-full-binding-graph-validation",
            extension.enableFullBindingGraphValidation.orElse(extension.enableStrictValidation),
          )
        )
        add(
          lazyOption(
            "enable-graph-impl-class-as-return-type",
            extension.enableGraphImplClassAsReturnType.orElse(false),
          )
        )
        add(lazyOption("transform-providers-to-private", extension.transformProvidersToPrivate))
        add(lazyOption("shrink-unused-bindings", extension.shrinkUnusedBindings))
        add(lazyOption("chunk-field-inits", extension.chunkFieldInits))
        add(lazyOption("statements-per-init-fun", extension.statementsPerInitFun))
        @Suppress("DEPRECATION")
        add(
          lazyOption(
            "optional-binding-behavior",
            extension.optionalBindingBehavior.orElse(
              extension.optionalDependencyBehavior.map { it.mapToOptionalBindingBehavior() }
            ),
          )
        )
        add(lazyOption("public-provider-severity", extension.publicProviderSeverity))
        add(
          lazyOption(
            "warn-on-inject-annotation-placement",
            extension.warnOnInjectAnnotationPlacement,
          )
        )
        add(
          lazyOption(
            "interop-annotations-named-arg-severity",
            extension.interopAnnotationsNamedArgSeverity,
          )
        )
        add(
          lazyOption(
            "enable-top-level-function-injection",
            extension.enableTopLevelFunctionInjection,
          )
        )
        add(lazyOption("contributes-as-inject", extension.contributesAsInject))
        reportsDir.orNull
          ?.let { FilesSubpluginOption("reports-destination", listOf(it.asFile)) }
          ?.let(::add)

        if (isJvmTarget) {
          add(
            SubpluginOption(
              "enable-dagger-runtime-interop",
              extension.interop.enableDaggerRuntimeInterop.getOrElse(false).toString(),
            )
          )
        }

        with(extension.interop) {
          provider
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-provider", value = it.joinToString(":")) }
            ?.let(::add)
          lazy
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-lazy", value = it.joinToString(":")) }
            ?.let(::add)
          assisted
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-assisted", value = it.joinToString(":")) }
            ?.let(::add)
          assistedFactory
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-assisted-factory", value = it.joinToString(":")) }
            ?.let(::add)
          assistedInject
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-assisted-inject", value = it.joinToString(":")) }
            ?.let(::add)
          binds
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-binds", value = it.joinToString(":")) }
            ?.let(::add)
          contributesTo
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-contributes-to", value = it.joinToString(":")) }
            ?.let(::add)
          contributesBinding
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-contributes-binding", value = it.joinToString(":")) }
            ?.let(::add)
          contributesIntoSet
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-contributes-into-set", value = it.joinToString(":")) }
            ?.let(::add)
          graphExtension
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-graph-extension", value = it.joinToString(":")) }
            ?.let(::add)
          graphExtensionFactory
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let {
              SubpluginOption("custom-graph-extension-factory", value = it.joinToString(":"))
            }
            ?.let(::add)
          elementsIntoSet
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-elements-into-set", value = it.joinToString(":")) }
            ?.let(::add)
          dependencyGraph
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-dependency-graph", value = it.joinToString(":")) }
            ?.let(::add)
          dependencyGraphFactory
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let {
              SubpluginOption("custom-dependency-graph-factory", value = it.joinToString(":"))
            }
            ?.let(::add)
          inject
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-inject", value = it.joinToString(":")) }
            ?.let(::add)
          intoMap
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-into-map", value = it.joinToString(":")) }
            ?.let(::add)
          intoSet
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-into-set", value = it.joinToString(":")) }
            ?.let(::add)
          mapKey
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-map-key", value = it.joinToString(":")) }
            ?.let(::add)
          multibinds
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-multibinds", value = it.joinToString(":")) }
            ?.let(::add)
          provides
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-provides", value = it.joinToString(":")) }
            ?.let(::add)
          qualifier
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-qualifier", value = it.joinToString(":")) }
            ?.let(::add)
          scope
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-scope", value = it.joinToString(":")) }
            ?.let(::add)
          bindingContainer
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-binding-container", value = it.joinToString(":")) }
            ?.let(::add)
          origin
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-origin", value = it.joinToString(":")) }
            ?.let(::add)
          optionalBinding
            .getOrElse(mutableSetOf())
            .takeUnless { it.isEmpty() }
            ?.let { SubpluginOption("custom-optional-binding", value = it.joinToString(":")) }
            ?.let(::add)
          add(lazyOption("interop-include-javax-annotations", includeJavaxAnnotations))
          add(lazyOption("interop-include-jakarta-annotations", includeJakartaAnnotations))
          add(lazyOption("interop-include-dagger-annotations", includeDaggerAnnotations))
          add(
            lazyOption("interop-include-kotlin-inject-annotations", includeKotlinInjectAnnotations)
          )
          add(lazyOption("interop-include-anvil-annotations", includeAnvilAnnotations))
          add(
            lazyOption(
              "interop-include-kotlin-inject-anvil-annotations",
              includeKotlinInjectAnvilAnnotations,
            )
          )
          add(
            SubpluginOption(
              "enable-dagger-anvil-interop",
              value = enableDaggerAnvilInterop.getOrElse(false).toString(),
            )
          )
          add(lazyOption("interop-include-guice-annotations", includeGuiceAnnotations))
          add(
            SubpluginOption(
              "enable-guice-runtime-interop",
              value = enableGuiceRuntimeInterop.getOrElse(false).toString(),
            )
          )
        }
      }
    }
  }
}

@JvmName("booleanPluginOptionOf")
private fun lazyOption(key: String, value: Provider<Boolean>): SubpluginOption =
  lazyOption(key, value.map { it.toString() })

@JvmName("intPluginOptionOf")
private fun lazyOption(key: String, value: Provider<Int>): SubpluginOption =
  lazyOption(key, value.map { it.toString() })

@JvmName("enumPluginOptionOf")
private fun <T : Enum<T>> lazyOption(key: String, value: Provider<T>): SubpluginOption =
  lazyOption(key, value.map { it.name })

private fun lazyOption(key: String, value: Provider<String>): SubpluginOption =
  SubpluginOption(key, lazy(LazyThreadSafetyMode.NONE) { value.get() })
