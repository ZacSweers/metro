// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.validation

import dev.zacsweers.metro.compiler.MetroHints
import dev.zacsweers.metro.compiler.mapToSet
import java.io.File
import java.util.zip.ZipInputStream
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.source
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Reads hints from JVM classes directories, jars, and Android AARs. Only filtered scans need to
 * read bytecode. Other runtime artifacts have no JVM hints and are skipped.
 */
internal object MetroHintScanner {
  private val hintPrefix = MetroHints.PACKAGE_NAME.replace('.', '/')
  private val archiveRoot = "/".toPath()

  /**
   * Returns artifact-relative entries suitable for a relocatable report. Scope IDs use Kotlin's
   * ClassId spelling such as `com/example/Scopes.App`. All paths are read from [fileSystem].
   */
  fun findHints(
    artifact: File,
    scopes: Set<String>,
    fileSystem: FileSystem = FileSystem.SYSTEM,
  ): Set<String> {
    // Keep this spelling aligned with MetroHints.hintFunctionName without loading compiler classes.
    val scopeFunctions = scopes.mapToSet { it.replace('/', '_').replace('.', '_') }
    val path = artifact.toOkioPath()
    val result = mutableSetOf<String>()
    with(fileSystem) {
      val extension = path.name.substringAfterLast('.', "")
      when {
        metadataOrNull(path)?.isDirectory == true -> scanDirectory(path, scopeFunctions, result)
        extension.equals("jar", ignoreCase = true) -> {
          openZip(path).use { it.scanDirectory(archiveRoot, scopeFunctions, result) }
        }
        extension.equals("aar", ignoreCase = true) -> {
          openZip(path).use { archive ->
            archive.scanDirectory(archiveRoot, scopeFunctions, result)
            archive.scanAar(scopeFunctions, result)
          }
        }
      }
    }
    return result
  }

  /** Uses the same paths and scope matcher for on-disk classes and archives. */
  private fun FileSystem.scanDirectory(
    root: Path,
    scopes: Set<String>,
    result: MutableSet<String>,
  ) {
    val hints = root / hintPrefix
    if (metadataOrNull(hints)?.isDirectory != true) {
      return
    }
    for (path in listRecursively(hints)) {
      if (!path.name.endsWith(".class") || !metadata(path).isRegularFile) {
        continue
      }
      val matches = scopes.isEmpty() || read(path) { matchesScope(readByteArray(), scopes) }
      if (matches) {
        val relativeEntry = path.relativeTo(root).segments.joinToString("/")
        result += relativeEntry
      }
    }
  }

  /** AARs store application bytecode in classes.jar and may also include jars under libs/. */
  private fun FileSystem.scanAar(scopes: Set<String>, result: MutableSet<String>) {
    val classes = archiveRoot / "classes.jar"
    if (metadataOrNull(classes)?.isRegularFile == true) {
      scanAarJar(classes, scopes, result)
    }
    val libs = archiveRoot / "libs"
    if (metadataOrNull(libs)?.isDirectory != true) {
      return
    }
    for (path in listRecursively(libs)) {
      if (!path.name.endsWith(".jar") || !metadata(path).isRegularFile) {
        continue
      }
      scanAarJar(path, scopes, result)
    }
  }

  /**
   * Okio's ZIP filesystem can't open a nested jar for random access. Stream embedded jars from the
   * AAR filesystem. Each entry's source stays open until the owning ZIP stream advances.
   */
  private fun FileSystem.scanAarJar(path: Path, scopes: Set<String>, result: MutableSet<String>) {
    val jarName = path.relativeTo(archiveRoot).segments.joinToString("/")
    read(path) {
      ZipInputStream(inputStream()).use { nested ->
        while (true) {
          val entry = nested.nextEntry ?: break
          val isHint =
            !entry.isDirectory &&
              entry.name.startsWith("$hintPrefix/") &&
              entry.name.endsWith(".class")
          if (isHint) {
            val matches =
              scopes.isEmpty() || matchesScope(nested.source().buffer().readByteArray(), scopes)
            if (matches) {
              result += "$jarName!/${entry.name}"
            }
          }
          nested.closeEntry()
        }
      }
    }
  }

  /**
   * Hint functions are static methods named after their scope. Inspecting method names avoids
   * confusing a scope mentioned in a constant or a longer scope name with a matching hint.
   */
  private fun matchesScope(bytecode: ByteArray, scopes: Set<String>): Boolean {
    var matches = false
    val visitor =
      object : ClassVisitor(Opcodes.ASM8) {
        override fun visitMethod(
          access: Int,
          name: String,
          descriptor: String,
          signature: String?,
          exceptions: Array<out String>?,
        ): MethodVisitor? {
          if (access and Opcodes.ACC_STATIC != 0 && name in scopes) {
            matches = true
          }
          return null
        }
      }
    ClassReader(bytecode)
      .accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    return matches
  }
}
