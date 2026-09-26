// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.validation

import dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi
import dev.zacsweers.metro.gradle.MetroPluginExtension
import dev.zacsweers.metro.gradle.capitalizeUS
import java.io.Serializable
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

/**
 * Wires validation to this compilation's dependency configurations. Runtime outputs of the consumer
 * itself are excluded so checks can still run when its graph won't compile.
 */
@OptIn(ExperimentalMetroGradleApi::class)
internal fun registerHiddenDependencyTasks(
  project: Project,
  extension: MetroPluginExtension,
  compilation: KotlinCompilation<*>,
) {
  val runtimeName = compilation.runtimeDependencyConfigurationName ?: return
  val segments = listOf(compilation.target.name, compilation.name).filter(String::isNotBlank)
  val taskQualifier = segments.joinToString("") { it.capitalizeUS() }
  val compilationPath = segments.joinToString("/")
  val isAndroid = compilation.platformType == KotlinPlatformType.androidJvm

  val compileConfiguration =
    project.configurations.named(compilation.compileDependencyConfigurationName)
  val runtimeConfiguration = project.configurations.named(runtimeName)

  // Metadata providers are flattened to strings before they become task inputs.
  val compileIds =
    compileConfiguration
      .flatMap { it.incoming.resolutionResult.rootComponent }
      .map { dependencyGraph(it).paths.keys }

  val runtimeGraph =
    runtimeConfiguration
      .flatMap { it.incoming.resolutionResult.rootComponent }
      .map(::dependencyGraph)

  project.tasks.register(
    "check${taskQualifier}MetroHiddenDependencies",
    CheckHiddenMetroDependenciesTask::class.java,
  ) { task ->
    val incoming = runtimeConfiguration.get().incoming
    val artifactViews = buildList {
      add(
        incoming.artifactView { view ->
          view.componentFilter { id ->
            val key = componentKey(id)
            val hidden = key !in compileIds.get()
            val androidVariant = isAndroid && key in runtimeGraph.get().androidComponents
            hidden && !androidVariant
          }
        }
      )
      if (isAndroid) {
        // Android components need a specific artifact type to disambiguate AGP's runtime outputs.
        add(
          incoming.artifactView { view ->
            view.attributes.attribute(
              ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
              "android-classes-jar",
            )
            view.componentFilter { id ->
              val key = componentKey(id)
              val hidden = key !in compileIds.get()
              val androidVariant = key in runtimeGraph.get().androidComponents
              hidden && androidVariant
            }
          }
        )
      }
    }

    task.description = "Checks dependencies with hidden Metro hints for $compilationPath"
    task.compileComponentIds.set(compileIds)
    task.runtimeDependencyPaths.set(runtimeGraph.map { it.paths })
    for (view in artifactViews) {
      val artifacts = view.artifacts
      task.runtimeArtifacts.addAll(
        artifacts.resolvedArtifacts.map { resolved ->
          resolved.map { artifact ->
            val id = artifact.id.componentIdentifier
            MetroValidationArtifact(componentKey(id), id.displayName, artifact.file)
          }
        }
      )
      // Keep the original file collections so Gradle sees each producer's task dependencies.
      task.runtimeFiles.from(artifacts.artifactFiles)
    }
    task.scopes.set(extension.aggregationScopes)
    val interop = extension.interop
    task.hintFormats.add(HintFormat.METRO)
    task.hintFormats.addAll(interop.includeAnvilAnnotations.formatIfEnabled(HintFormat.ANVIL))
    task.hintFormats.addAll(
      interop.includeKotlinInjectAnvilAnnotations.formatIfEnabled(HintFormat.KOTLIN_INJECT_ANVIL)
    )
    task.hintFormats.addAll(interop.includeHiltAnnotations.formatIfEnabled(HintFormat.HILT))
    task.reportFile.convention(
      project.layout.buildDirectory.file("reports/metro/$compilationPath/hidden-dependencies.txt")
    )
  }
}

/** Interop formats are checked only when Metro reads them. */
private fun Provider<Boolean>.formatIfEnabled(format: HintFormat): Provider<Set<HintFormat>> =
  map { enabled ->
    if (enabled) {
      setOf(format)
    } else {
      emptySet()
    }
  }

/** Plain values from resolution. Provider mappings expose only strings and sets to task inputs. */
private data class MetroDependencyGraph(
  val paths: Map<String, String>,
  val androidComponents: Set<String>,
) : Serializable

/**
 * Matches the same logical module across compile and runtime. A different resolved version does not
 * turn an already visible module into a hidden dependency. Project keys retain the build path.
 */
private fun componentKey(id: ComponentIdentifier): String =
  when (id) {
    is ModuleComponentIdentifier -> "module:${id.group}:${id.module}"
    is ProjectComponentIdentifier -> "project:${id.buildTreePath}"
    else -> id.displayName
  }

/**
 * Chooses one shortest resolved path per component. Cycles and diamond paths stop at first visit.
 * Android variants are identified from AGP's published attribute, which also covers non-KMP
 * modules.
 */
private fun dependencyGraph(root: ResolvedComponentResult): MetroDependencyGraph {
  val paths = mutableMapOf<String, String>()
  val androidComponents = mutableSetOf<String>()
  val pending = ArrayDeque<Pair<ResolvedComponentResult, String>>()
  pending.add(root to root.id.displayName)
  while (pending.isNotEmpty()) {
    val (component, path) = pending.removeFirst()
    val key = componentKey(component.id)
    if (paths.putIfAbsent(key, path) != null) {
      continue
    }
    for (dependency in component.dependencies) {
      if (dependency is ResolvedDependencyResult && !dependency.isConstraint) {
        val selected = dependency.selected
        val isAndroidVariant =
          dependency.resolvedVariant.attributes.keySet().any {
            it.name == "com.android.build.api.attributes.AgpVersionAttr"
          }
        if (isAndroidVariant) {
          androidComponents += componentKey(selected.id)
        }
        pending.add(selected to "$path -> ${selected.id.displayName}")
      }
    }
  }
  return MetroDependencyGraph(paths, androidComponents)
}
