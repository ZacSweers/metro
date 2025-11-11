// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import java.time.Instant
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
public abstract class GenerateGraphMetadataTask : DefaultTask() {

  @get:Input public abstract val projectPath: Property<String>

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val metadataFiles: ConfigurableFileCollection

  @get:OutputFile public abstract val outputFile: RegularFileProperty

  private val json = Json { prettyPrint = true; encodeDefaults = true }

  @TaskAction
  public fun generate() {
    val graphJsonElements =
      metadataFiles
        .files
        .filter { it.isFile && it.extension == "json" }
        .sortedBy { it.absolutePath }
        .mapNotNull { file ->
          runCatching { json.parseToJsonElement(file.readText()) }.getOrElse { throwable ->
            logger.warn("Failed to parse Metro graph metadata file ${file.absolutePath}", throwable)
            null
          }
        }

    val result =
      buildJsonObject {
        put("projectPath", JsonPrimitive(projectPath.get()))
        put("generatedAt", JsonPrimitive(Instant.now().toString()))
        put("graphCount", JsonPrimitive(graphJsonElements.size))
        put("graphs", JsonArray(graphJsonElements))
      }

    val output = outputFile.get().asFile.toPath()
    output.parent?.createDirectories()
    output.deleteIfExists()
    output.bufferedWriter().use { writer ->
      writer.write(json.encodeToString(JsonObject.serializer(), result))
    }
  }
}
