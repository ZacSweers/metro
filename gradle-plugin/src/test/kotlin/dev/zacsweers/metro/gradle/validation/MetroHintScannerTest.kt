// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.validation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MetroHintScannerTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  private val fileSystem = FakeFileSystem()

  /** Assert every scanner-owned file handle and ZIP source was closed. */
  @After
  fun tearDown() {
    fileSystem.checkNoOpenFiles()
    fileSystem.close()
  }

  @Test
  fun `directories report every hint by default and select exact nested scope methods`() {
    val classes = compileHints()

    assertThat(findHints(classes, emptySet()))
      .isEqualTo(setOf("metro/hints/AppKt.class", "metro/hints/OtherKt.class"))
    assertThat(findHints(classes, setOf("com/example/Scopes.App")))
      .isEqualTo(setOf("metro/hints/AppKt.class"))
    assertThat(findHints(classes, setOf("com/example/Scopes.LongApp")))
      .isEqualTo(setOf("metro/hints/OtherKt.class"))
  }

  @Test
  fun `jar scopes ignore name suffixes and unrelated UTF constants`() {
    val classes = compileHints()
    val jar = "/hints.jar".toPath()
    writeZip(jar, classEntries(classes))

    assertThat(findHints(jar, setOf("com/example/Scopes.App")))
      .isEqualTo(setOf("metro/hints/AppKt.class"))
    assertThat(findHints(jar, setOf("com/example/ConstantOnly"))).isEmpty()
    assertThat(findHints(jar, setOf("com/example/App"))).isEmpty()
    assertThat(findHints(jar, emptySet()))
      .isEqualTo(setOf("metro/hints/AppKt.class", "metro/hints/OtherKt.class"))
  }

  @Test
  fun `AAR searches classes and library jars and leaves asset archives alone`() {
    val classes = compileHints()
    val app = "/app.jar".toPath()
    val other = "/other.jar".toPath()
    val entries = classEntries(classes)
    writeZip(app, entries.filterKeys { it.endsWith("/AppKt.class") })
    writeZip(other, entries.filterKeys { it.endsWith("/OtherKt.class") })
    val aar = "/hints.aar".toPath()
    writeZip(
      aar,
      mapOf(
        "classes.jar" to fileSystem.read(other) { readByteArray() },
        "libs/contributions.jar" to fileSystem.read(app) { readByteArray() },
        "assets/unrelated.jar" to fileSystem.read(app) { readByteArray() },
      ),
    )

    assertThat(findHints(aar, setOf("com/example/Scopes.App")))
      .isEqualTo(setOf("libs/contributions.jar!/metro/hints/AppKt.class"))
    assertThat(findHints(aar, emptySet()))
      .isEqualTo(
        setOf(
          "classes.jar!/metro/hints/OtherKt.class",
          "libs/contributions.jar!/metro/hints/AppKt.class",
        )
      )
  }

  @Test
  fun `scanning two scoped classes in a nested jar keeps the archive open`() {
    val classes = compileHints()
    val jar = "/both.jar".toPath()
    writeZip(jar, classEntries(classes))
    val aar = "/both.aar".toPath()
    writeZip(aar, mapOf("classes.jar" to fileSystem.read(jar) { readByteArray() }))

    assertThat(
        findHints(
          aar,
          setOf("com/example/Scopes.App", "com/example/Scopes.LongApp"),
        )
      )
      .isEqualTo(
        setOf("classes.jar!/metro/hints/AppKt.class", "classes.jar!/metro/hints/OtherKt.class")
      )
  }

  /**
   * Use javac to exercise real class-file layouts, including the two-slot long and double
   * constants. Javac requires system paths. Copy the compiled output to the fake filesystem for
   * every scan. Functional tests exercise the corresponding Metro-generated Kotlin classes.
   */
  private fun compileHints(): Path {
    val sourceDir = temporaryFolder.newFolder().toOkioPath()
    val compiledClasses = temporaryFolder.newFolder().toOkioPath()
    val app = sourceDir / "AppKt.java"
    FileSystem.SYSTEM.write(app) {
      writeUtf8(
        """
        package metro.hints;
        public final class AppKt {
          private static final long serialVersion = 10000L;
          private static final double scale = 2.5;
          public static final String other = "com_example_ConstantOnly";
          public static void com_example_Scopes_App(Object contribution) {}
        }
        """
          .trimIndent()
      )
    }
    val other = sourceDir / "OtherKt.java"
    FileSystem.SYSTEM.write(other) {
      writeUtf8(
        """
        package metro.hints;
        public final class OtherKt {
          public static void com_example_Scopes_LongApp(Object contribution) {}
        }
        """
          .trimIndent()
      )
    }
    val ignored = sourceDir / "Unrelated.java"
    FileSystem.SYSTEM.write(ignored) {
      writeUtf8(
        """
        package com.example;
        public final class Unrelated {
          public static void com_example_Scopes_App(Object contribution) {}
        }
        """
          .trimIndent()
      )
    }
    val exitCode =
      ToolProvider.getSystemJavaCompiler()
        .run(
          null,
          null,
          null,
          "-d",
          compiledClasses.toString(),
          app.toString(),
          other.toString(),
          ignored.toString(),
        )
    assertThat(exitCode).isEqualTo(0)
    val classes = "/classes".toPath()
    for (path in FileSystem.SYSTEM.listRecursively(compiledClasses)) {
      val destination = classes / path.relativeTo(compiledClasses)
      if (FileSystem.SYSTEM.metadata(path).isDirectory) {
        fileSystem.createDirectories(destination)
      } else {
        fileSystem.write(destination) {
          FileSystem.SYSTEM.source(path).use { writeAll(it) }
        }
      }
    }
    return classes
  }

  /** Simulates Gradle's File boundary; all scanner reads must resolve in the fake filesystem. */
  private fun findHints(artifact: Path, scopes: Set<String>): Set<String> {
    return MetroHintScanner.findHints(artifact.toFile(), scopes, fileSystem)
  }

  /** Collects the fake compiler output into portable ZIP entry names. */
  private fun classEntries(classes: Path): Map<String, ByteArray> {
    return fileSystem
      .listRecursively(classes)
      .filter { fileSystem.metadata(it).isRegularFile }
      .associate {
        it.relativeTo(classes).segments.joinToString("/") to fileSystem.read(it) { readByteArray() }
      }
  }

  /** ZIP encoding streams into Okio's sink, so archives exist only in the fake filesystem. */
  private fun writeZip(destination: Path, entries: Map<String, ByteArray>) {
    fileSystem.write(destination) {
      ZipOutputStream(outputStream()).use { output ->
        for ((name, bytes) in entries) {
          output.putNextEntry(ZipEntry(name))
          output.write(bytes)
          output.closeEntry()
        }
      }
    }
  }
}
