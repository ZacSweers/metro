// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.Source
import com.autonomousapps.kit.Source.Companion.kotlin
import com.autonomousapps.kit.SourceType
import java.net.URLClassLoader
import java.util.Locale
import kotlin.io.path.absolute
import kotlin.io.path.exists
import org.intellij.lang.annotations.Language

// TODO dedupe with MetroCompilerTest
private val CLASS_NAME_REGEX = Regex("(class|object|interface) (?<name>[a-zA-Z0-9_]+)")
private val FUNCTION_NAME_REGEX = Regex("fun( <[a-zA-Z0-9_]+>)? (?<name>[a-zA-Z0-9_]+)")
private val DEFAULT_IMPORTS =
  listOf(
    "dev.zacsweers.metro.*",
    // For Callable access
    "java.util.concurrent.*",
  )
private val FILE_PATH_REGEX = Regex("file://.*?/(?=[^/]+\\.kt)")

fun String.cleanOutputLine(): String = FILE_PATH_REGEX.replace(trimEnd(), "")

private val FILE_PATH_REGEX = Regex("file://.*?/(?=[^/]+\\.kt)")

fun String.cleanOutputLine(): String = FILE_PATH_REGEX.replace(trimEnd(), "")

fun GradleProject.classLoader(): ClassLoader {
  val classesDir = rootDir.toPath().resolve("build/classes/kotlin/main").absolute()

  check(classesDir.exists()) { "Classes dir not found: ${classesDir.toAbsolutePath()}" }

  return URLClassLoader(
    // Include the original classpaths and the output directory to be able to load classes from
    // dependencies.
    arrayOf(classesDir.toUri().toURL()),
    this::class.java.classLoader,
  )
}

/** Returns a [Source] representation of this [source]. This includes common imports from Metro. */
fun source(
  @Language("kotlin") source: String,
  fileNameWithoutExtension: String? = null,
  packageName: String = "test",
  vararg extraImports: String,
): Source {
  @Suppress("DEPRECATION")
  val fileName =
    fileNameWithoutExtension
      ?: CLASS_NAME_REGEX.find(source)?.groups?.get("name")?.value
      ?: FUNCTION_NAME_REGEX.find(source)?.groups?.get("name")?.value?.capitalize(Locale.US)
      ?: "source"
  return kotlin(
      buildString {
        // Package statement
        appendLine("package $packageName")

        // Imports
        for (import in DEFAULT_IMPORTS + extraImports) {
          appendLine("import $import")
        }

        appendLine()
        appendLine()
        appendLine(source.trimIndent())
      }
    )
    .withPath(packageName, fileName)
    .build()
}

fun Source.copy(@Language("Kotlin") newContent: String): Source {
  return when (sourceType) {
    SourceType.KOTLIN -> {
      source(newContent, fileNameWithoutExtension = name)
    }
    else -> error("Unsupported source: $sourceType")
  }
}
