// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.validation

import java.io.File
import java.util.zip.ZipInputStream
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.source

/**
 * Reads hints from JVM classes directories, jars, and Android AARs. Other runtime artifacts have no
 * JVM hints and are skipped.
 */
internal object MetroHintScanner {
  private val archiveRoot = "/".toPath()

  /**
   * Returns artifact-relative entries suitable for a relocatable report. Only packages in [formats]
   * are searched. Scope IDs use Kotlin's ClassId spelling such as `com/example/Scopes.App`. All
   * paths are read from [fileSystem].
   */
  fun findHints(
    artifact: File,
    scopes: Set<String>,
    formats: Set<HintFormat>,
    fileSystem: FileSystem = FileSystem.SYSTEM,
  ): Set<String> {
    val scan = Scan(formats, HintMatcher(scopes))
    val path = artifact.toOkioPath()
    with(fileSystem) {
      val extension = path.name.substringAfterLast('.', "")
      when {
        metadataOrNull(path)?.isDirectory == true -> scanRoot(path, scan)
        extension.equals("jar", ignoreCase = true) -> {
          openZip(path).use { it.scanRoot(archiveRoot, scan) }
        }
        extension.equals("aar", ignoreCase = true) -> {
          openZip(path).use { archive ->
            archive.scanRoot(archiveRoot, scan)
            archive.scanAar(scan)
          }
        }
      }
    }
    return scan.result
  }

  /** One artifact's scan settings and matching entries. */
  private class Scan(val formats: Set<HintFormat>, val matcher: HintMatcher) {
    val result = mutableSetOf<String>()

    fun formatOf(entryName: String): HintFormat? {
      if (!entryName.endsWith(".class")) {
        return null
      }
      return formats.firstOrNull { entryName.startsWith("${it.packagePath}/") }
    }
  }

  /** Uses the same paths and matchers for on-disk classes and archives. */
  private fun FileSystem.scanRoot(root: Path, scan: Scan) {
    val classes = { entryName: String -> readClassOrNull(root / entryName) }
    for (format in scan.formats) {
      val hints = root / format.packagePath
      if (metadataOrNull(hints)?.isDirectory != true) {
        continue
      }
      for (path in listRecursively(hints)) {
        if (!path.name.endsWith(".class") || !metadata(path).isRegularFile) {
          continue
        }
        if (scan.matcher.matches(format, { read(path) { readByteArray() } }, classes)) {
          val relativeEntry = path.relativeTo(root).segments.joinToString("/")
          scan.result += relativeEntry
        }
      }
    }
  }

  private fun FileSystem.readClassOrNull(path: Path): ByteArray? {
    if (metadataOrNull(path)?.isRegularFile != true) {
      return null
    }
    return read(path) { readByteArray() }
  }

  /** AARs store application bytecode in classes.jar and may also include jars under libs/. */
  private fun FileSystem.scanAar(scan: Scan) {
    val classes = archiveRoot / "classes.jar"
    if (metadataOrNull(classes)?.isRegularFile == true) {
      scanAarJar(classes, scan)
    }
    val libs = archiveRoot / "libs"
    if (metadataOrNull(libs)?.isDirectory != true) {
      return
    }
    for (path in listRecursively(libs)) {
      if (!path.name.endsWith(".jar") || !metadata(path).isRegularFile) {
        continue
      }
      scanAarJar(path, scan)
    }
  }

  /**
   * Okio's ZIP filesystem can't open a nested jar for random access. Stream embedded jars from the
   * AAR filesystem. Each entry's source stays open until the owning ZIP stream advances.
   */
  private fun FileSystem.scanAarJar(path: Path, scan: Scan) {
    val jarName = path.relativeTo(archiveRoot).segments.joinToString("/")
    val classes = NestedJarClasses(this, path)
    read(path) {
      ZipInputStream(inputStream()).use { nested ->
        while (true) {
          val entry = nested.nextEntry ?: break
          // Directory entries end with a slash, so they never match a class name.
          val format = scan.formatOf(entry.name)
          if (format != null) {
            val hint = { nested.source().buffer().readByteArray() }
            if (scan.matcher.matches(format, hint, classes::read)) {
              scan.result += "$jarName!/${entry.name}"
            }
          }
          nested.closeEntry()
        }
      }
    }
  }

  /**
   * Some hints need other classes from their jar. A nested jar can only be streamed, so the first
   * lookup buffers all of its classes.
   */
  private class NestedJarClasses(private val fileSystem: FileSystem, private val path: Path) {
    private val classes: Map<String, ByteArray> by lazy {
      buildMap {
        fileSystem.read(path) {
          ZipInputStream(inputStream()).use { nested ->
            while (true) {
              val entry = nested.nextEntry ?: break
              if (!entry.isDirectory && entry.name.endsWith(".class")) {
                put(entry.name, nested.source().buffer().readByteArray())
              }
              nested.closeEntry()
            }
          }
        }
      }
    }

    fun read(entryName: String): ByteArray? = classes[entryName]
  }
}
