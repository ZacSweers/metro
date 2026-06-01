// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import kotlin.io.path.writeText
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Diagnostic report for local bug reports")
public abstract class MetroEnvTask : DefaultTask() {

  @get:Input public abstract val projectPath: Property<String>

  @get:Input public abstract val targetName: Property<String>

  @get:Input public abstract val compilationName: Property<String>

  @get:Input public abstract val platformType: Property<String>

  @get:Input public abstract val compileTaskName: Property<String>

  @get:Input public abstract val metroVersion: Property<String>

  @get:Input public abstract val metroCompilerArtifact: Property<String>

  @get:Input public abstract val kotlinVersion: Property<String>

  @get:Input public abstract val kotlinCompilerVersion: Property<String>

  @get:Input @get:Optional public abstract val kotlinLanguageVersion: Property<String>

  @get:Input @get:Optional public abstract val kotlinApiVersion: Property<String>

  @get:Input public abstract val freeCompilerArgs: ListProperty<String>

  @get:Input public abstract val metroCompilerOptions: ListProperty<String>

  @get:Input public abstract val gradleVersion: Property<String>

  @get:Input public abstract val javaVersion: Property<String>

  @get:Input public abstract val os: Property<String>

  @get:OutputFile public abstract val outputFile: RegularFileProperty

  init {
    group = "metro"
    freeCompilerArgs.convention(emptyList())
    metroCompilerOptions.convention(emptyList())
  }

  @TaskAction
  public fun generate() {
    val output = outputFile.get().asFile.toPath()
    output.writeText(renderReport())
    logger.lifecycle("Generated Metro environment report at file://$output ")
  }

  private fun renderReport(): String = buildString {
    appendLine("Metro environment report")
    appendLine()

    appendSection("Project") {
      appendProperty("path", projectPath.get())
      appendProperty("target", targetName.get())
      appendProperty("compilation", compilationName.get())
      appendProperty("platform", platformType.get())
      appendProperty("compile task", compileTaskName.get())
    }

    appendSection("Versions") {
      appendProperty("Metro Gradle plugin", metroVersion.get())
      appendProperty("Metro compiler artifact", metroCompilerArtifact.get())
      appendProperty("Kotlin Gradle plugin", kotlinVersion.get())
      appendProperty("Kotlin compiler", kotlinCompilerVersion.get())
      appendProperty("Gradle", gradleVersion.get())
      appendProperty("Java", javaVersion.get())
      appendProperty("OS", os.get())
    }

    appendSection("Kotlin compiler options") {
      appendProperty("languageVersion", kotlinLanguageVersion.orNull ?: "<default>")
      appendProperty("apiVersion", kotlinApiVersion.orNull ?: "<default>")
      appendList("freeCompilerArgs", freeCompilerArgs.get())
    }

    appendSection("Metro compiler plugin options") {
      appendList("options", metroCompilerOptions.get())
    }
  }

  private inline fun StringBuilder.appendSection(
    title: String,
    body: StringBuilder.() -> Unit,
  ) {
    appendLine(title)
    body()
    appendLine()
  }

  private fun StringBuilder.appendProperty(name: String, value: String) {
    appendLine("  $name: $value")
  }

  private fun StringBuilder.appendList(name: String, values: List<String>) {
    appendLine("  $name:")
    if (values.isEmpty()) {
      appendLine("    <empty>")
    } else {
      for (value in values) {
        appendLine("    $value")
      }
    }
  }

  internal companion object {
    const val AGGREGATE_NAME = "metroEnv"
  }
}
