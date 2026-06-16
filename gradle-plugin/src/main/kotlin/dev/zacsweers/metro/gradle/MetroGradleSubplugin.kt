// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import dev.zacsweers.metro.gradle.analysis.AnalyzeGraphTask
import dev.zacsweers.metro.gradle.analysis.GenerateGraphHtmlTask
import dev.zacsweers.metro.gradle.artifacts.GenerateGraphMetadataTask
import dev.zacsweers.metro.gradle.artifacts.MetroArtifactCopyTask
import javax.inject.Inject
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.problems.ProblemGroup
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.Problems
import org.gradle.api.problems.Severity
import org.gradle.api.provider.Provider
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.kotlinToolingVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.tooling.core.KotlinToolingVersion

@OptIn(ExperimentalMetroGradleApi::class)
public class MetroGradleSubplugin @Inject constructor(problems: Problems) :
  KotlinCompilerPluginSupportPlugin {

  private companion object {
    val minKotlinVersion by lazy {
      SUPPORTED_KOTLIN_VERSIONS.minOf { KotlinVersion.fromVersion(it.substringBeforeLast('.')) }
    }

    val PROBLEM_GROUP: ProblemGroup = ProblemGroup.create("metro-group", "Metro Problems")

    private const val COMPILER_VERSION_OVERRIDE = "metro.compilerVersionOverride"
    private const val COMPILER_VERSION_OVERRIDE_PROPERTY = "metroCompilerVersionOverride"
    private const val CIRCUIT_ANNOTATIONS_DEP =
      "com.slack.circuit:circuit-codegen-annotations:0.33.0"
  }

  private val problemReporter = problems.reporter

  @OptIn(ExperimentalBuildToolsApi::class, ExperimentalKotlinGradlePluginApi::class)
  override fun apply(target: Project) {
    val compilerVersionProvider =
      target.kotlinExtension.compilerVersion
        .map { KotlinToolingVersion(it) }
        .orElse(target.provider { target.kotlinToolingVersion })

    val extension =
      target.extensions.create(
        "metro",
        MetroPluginExtension::class.java,
        compilerVersionProvider,
        target.layout,
      )

    target.tasks.register(MetroEnvTask.AGGREGATE_NAME) { task ->
      task.group = "metro"
      task.description = "Generates Metro environment reports for all Kotlin compilations"
      task.dependsOn(
        target.tasks.named { candidate ->
          candidate != MetroEnvTask.AGGREGATE_NAME && candidate.endsWith("MetroEnv")
        }
      )
    }

    // Analysis tasks are registered, but skipped if reportsDestination isn't present.
    val graphMetadataTask =
      target.tasks.register(
        GenerateGraphMetadataTask.NAME,
        GenerateGraphMetadataTask::class.java,
      )
    graphMetadataTask.configure { task ->
      task.onlyIf("reportsDestination is present") { extension.reportsDestination.isPresent }
      task.description = "Generates Metro graph metadata for ${target.path}"
      task.projectPath.convention(target.path)
      task.outputFile.convention(
        target.layout.buildDirectory.file("reports/metro/graphMetadata.json")
      )
    }

    // Analysis task - comprehensive graph analysis
    val analyzeTask = target.tasks.register(AnalyzeGraphTask.NAME, AnalyzeGraphTask::class.java)
    analyzeTask.configure { task ->
      task.onlyIf("reportsDestination is present") { extension.reportsDestination.isPresent }
      task.description = "Analyzes Metro dependency graphs and produces a comprehensive report"
      task.inputFile.convention(graphMetadataTask.flatMap { it.outputFile })
      task.outputFile.convention(target.layout.buildDirectory.file("reports/metro/analysis.json"))
    }

    // HTML visualization task - interactive ECharts graphs
    val htmlTask =
      target.tasks.register(GenerateGraphHtmlTask.NAME, GenerateGraphHtmlTask::class.java)
    htmlTask.configure { task ->
      task.onlyIf("reportsDestination is present") { extension.reportsDestination.isPresent }
      task.description = "Generates interactive HTML visualizations of Metro dependency graphs"
      task.inputFile.convention(graphMetadataTask.flatMap { it.outputFile })
      task.analysisFile.convention(analyzeTask.flatMap { it.outputFile })
      task.outputDirectory.convention(target.layout.buildDirectory.dir("reports/metro/html"))
    }

    target.afterEvaluate {
      // Check version and show warning by default.
      val checkVersions =
        target.extensions
          .getByType(MetroPluginExtension::class.java)
          .enableKotlinVersionCompatibilityChecks
          .getOrElse(true)

      if (checkVersions) {
        val compilerVersion = compilerVersionProvider.get()
        val supportedVersions = SUPPORTED_KOTLIN_VERSIONS.map(::KotlinToolingVersion)
        val minSupported = supportedVersions.min()
        val maxSupported = supportedVersions.max()

        val isSupported = compilerVersion in minSupported..maxSupported
        if (!isSupported) {
          val compatibilityUrl = "https://zacsweers.github.io/metro/latest/compatibility"
          val disableSolution =
            "You can disable this warning via `metro.version.check=false` or setting the `metro.enableKotlinVersionCompatibilityChecks` DSL property"
          if (compilerVersion < minSupported) {
            val label =
              "Metro '$VERSION' requires Kotlin ${SUPPORTED_KOTLIN_VERSIONS.first()} or later, but this build uses '$compilerVersion'"
            val details =
              "Supported Kotlin versions: ${SUPPORTED_KOTLIN_VERSIONS.first()} - ${SUPPORTED_KOTLIN_VERSIONS.last()}"
            val solution =
              "Please upgrade Kotlin to at least '${SUPPORTED_KOTLIN_VERSIONS.first()}'"
            val problemId =
              ProblemId.create(
                "kotlin-version-too-old",
                "Kotlin version is too old for Metro",
                PROBLEM_GROUP,
              )
            problemReporter.report(problemId) { spec ->
              spec
                .severity(Severity.WARNING)
                .contextualLabel(label)
                .details(details)
                .solution(solution)
                .solution(disableSolution)
                .documentedAt(compatibilityUrl)
            }
            target.logger.warn(
              "$label. $solution.\n$details.\nDocs: $compatibilityUrl\n($disableSolution)"
            )
          } else {
            val label = "This build uses unrecognized Kotlin version '$compilerVersion'"
            val details =
              "Metro '$VERSION' supports the following Kotlin versions: $SUPPORTED_KOTLIN_VERSIONS"
            val solution =
              "If you have any issues, please upgrade Metro (if applicable) or use a supported Kotlin version. See $compatibilityUrl"
            val problemId =
              ProblemId.create(
                "kotlin-version-unrecognized",
                "Kotlin version is unrecognized by Metro",
                PROBLEM_GROUP,
              )
            problemReporter.report(problemId) { spec ->
              spec
                .severity(Severity.WARNING)
                .contextualLabel(label)
                .details(details)
                .solution(solution)
                .solution(disableSolution)
                .documentedAt(compatibilityUrl)
            }
            target.logger.warn(
              "$label. $details.\n$solution.\nDocs: $compatibilityUrl\n($disableSolution)"
            )
          }
        }
      }
    }
  }

  override fun getCompilerPluginId(): String = PLUGIN_ID

  override fun getPluginArtifact(): SubpluginArtifact {
    val version = System.getProperty(COMPILER_VERSION_OVERRIDE, VERSION)
    return SubpluginArtifact(
      groupId = "dev.zacsweers.metro",
      artifactId = "compiler",
      version = version,
    )
  }

  override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
    return true
  }

  @OptIn(
    DangerousMetroGradleApi::class,
    ExperimentalMetroGradleApi::class,
    RequiresIdeSupport::class,
  )
  override fun applyToCompilation(
    kotlinCompilation: KotlinCompilation<*>
  ): Provider<List<SubpluginOption>> {
    val project = kotlinCompilation.target.project
    val extension = project.extensions.getByType(MetroPluginExtension::class.java)

    val kotlinVersion = project.kotlinToolingVersion

    if (project.logVerbosely) {
      project.logger.lifecycle(
        "Supported platforms for ${kotlinCompilation.platformType} are: ${extension.supportedHintContributionPlatforms.get()}"
      )
    }

    val orderComposePlugin = kotlinVersion >= KotlinVersions.kotlin230
    kotlinCompilation.compileTaskProvider.configure { task ->
      if (orderComposePlugin) {
        // Order before compose-compiler
        task.compilerOptions.freeCompilerArgs.add(
          "-Xcompiler-plugin-order=${PLUGIN_ID}>androidx.compose.compiler.plugins.kotlin"
        )
      }
    }

    kotlinCompilation.compileTaskProvider.configure { task ->
      // Specify the compiler version
      task.inputs.property(
        COMPILER_VERSION_OVERRIDE_PROPERTY,
        project.providers.systemProperty(COMPILER_VERSION_OVERRIDE).orElse(VERSION),
      )

      // Ensure that the languageVersion is compatible
      task.doFirst { innerTask ->
        val compilerOptions = (innerTask as KotlinCompilationTask<*>).compilerOptions
        val languageVersion = compilerOptions.languageVersion.orNull ?: return@doFirst
        check(languageVersion >= minKotlinVersion) {
          "Compilation task '${innerTask.name}' targets language version '${languageVersion.version}' but Metro requires Kotlin '${minKotlinVersion.version}' or later."
        }
      }
    }

    val isJvmTarget =
      kotlinCompilation.target.platformType == KotlinPlatformType.jvm ||
        kotlinCompilation.target.platformType == KotlinPlatformType.androidJvm

    if (extension.automaticallyAddRuntimeDependencies.get()) {
      val implConfig = kotlinCompilation.defaultSourceSet.implementationConfigurationName
      val circuitEnabled = extension.enableCircuitCodegen.getOrElse(false)
      project.dependencies.add(implConfig, "dev.zacsweers.metro:runtime:$VERSION")
      if (circuitEnabled) {
        project.dependencies.add(implConfig, CIRCUIT_ANNOTATIONS_DEP)
      }

      if (implConfig == "metadataCompilationImplementation") {
        project.dependencies.add("commonMainImplementation", "dev.zacsweers.metro:runtime:$VERSION")
        if (circuitEnabled) {
          project.dependencies.add("commonMainImplementation", CIRCUIT_ANNOTATIONS_DEP)
        }
      }

      if (isJvmTarget) {
        if (extension.interop.enableDaggerRuntimeInterop.getOrElse(false)) {
          project.dependencies.add(implConfig, "dev.zacsweers.metro:interop-dagger:$VERSION")
        }
        if (extension.interop.enableGuiceRuntimeInterop.getOrElse(false)) {
          project.dependencies.add(implConfig, "dev.zacsweers.metro:interop-guice:$VERSION")
        }
      }
    }

    val reportsDir =
      extension.reportsDestination.map { baseDir ->
        // Include target name to avoid collisions in KMP projects where multiple targets
        // may have compilations with the same name (e.g., both jvm and android have "main")
        // Use chained dir() calls instead of joining with "/" to avoid Windows path issues
        listOf(kotlinCompilation.target.name, kotlinCompilation.name)
          .filter(String::isNotBlank)
          .fold(baseDir) { dir, segment -> dir.dir(segment) }
      }

    val traceDir =
      extension.traceDestination.map { baseDir ->
        listOf(kotlinCompilation.target.name, kotlinCompilation.name)
          .filter(String::isNotBlank)
          .fold(baseDir) { dir, segment -> dir.dir(segment) }
      }

    val artifactsTask = MetroArtifactCopyTask.register(project, reportsDir, kotlinCompilation)

    artifactsTask.configure { task ->
      task.onlyIf("reportsDestination is present") { extension.reportsDestination.isPresent }
    }

    project.tasks.withType(GenerateGraphMetadataTask::class.java).configureEach { task ->
      task.onlyIf("reportsDestination is present") { extension.reportsDestination.isPresent }
      task.projectPath.set(project.path)
      task.compilationName.set(kotlinCompilation.name)
      task.graphJsonFiles.from(
        artifactsTask
          .flatMap { it.reportsDir.dir("graph-metadata") }
          .map { it.asFileTree.matching { it.include("*.json") } }
      )
    }

    val metroOptions =
      project.metroCompilerPluginOptions(
        extension = extension,
        kotlinCompilation = kotlinCompilation,
        reportsDir = reportsDir,
        traceDir = traceDir,
        orderComposePlugin = orderComposePlugin,
        isJvmTarget = isJvmTarget,
      )

    project.tasks.register(
      "generate${kotlinCompilation.compileKotlinTaskName.capitalizeUS()}MetroEnv",
      MetroEnvTask::class.java,
    ) { task ->
      task.description =
        "Generates a Metro env report for ${project.path} ${kotlinCompilation.target.name}/${kotlinCompilation.name}"
      task.projectPath.set(project.path)
      task.targetName.set(kotlinCompilation.target.name)
      task.compilationName.set(kotlinCompilation.name)
      task.platformType.set(kotlinCompilation.platformType.name)
      task.compileTaskName.set(kotlinCompilation.compileKotlinTaskName)
      task.metroVersion.set(VERSION)
      task.metroCompilerArtifact.set(
        project.providers.systemProperty(COMPILER_VERSION_OVERRIDE).orElse(VERSION).map {
          "dev.zacsweers.metro:compiler:$it"
        }
      )
      task.kotlinVersion.set(kotlinVersion.toString())
      task.kotlinCompilerVersion.set(kotlinVersion.toString())
      task.gradleVersion.set(GradleVersion.current().version)
      task.javaVersion.set(JavaVersion.current().toString())
      task.os.set(
        "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})"
      )
      task.kotlinLanguageVersion.set(
        kotlinCompilation.compileTaskProvider.flatMap { compileTask ->
          compileTask.compilerOptions.languageVersion.map { it.version }
        }
      )
      task.kotlinApiVersion.set(
        kotlinCompilation.compileTaskProvider.flatMap { compileTask ->
          compileTask.compilerOptions.apiVersion.map { it.version }
        }
      )
      task.freeCompilerArgs.set(
        kotlinCompilation.compileTaskProvider.flatMap { compileTask ->
          compileTask.compilerOptions.freeCompilerArgs
        }
      )
      task.metroCompilerOptions.set(metroOptions.map { it.renderForReport() })
      task.outputFile.set(
        project.layout.buildDirectory.file(
          "reports/metro/env/${kotlinCompilation.target.name}/${kotlinCompilation.name}.txt"
        )
      )
    }

    return metroOptions
      .map { options -> options.map { it.toSubpluginOption() } }
      .also {
        if (project.logVerbosely) {
          project.logger.lifecycle(
            "Metro compiler plugin options for ${kotlinCompilation.platformType}:\n${it.get().joinToString("\n") { "- " + it.key + ": " + it.value }}"
          )
        }
      }
  }
}
