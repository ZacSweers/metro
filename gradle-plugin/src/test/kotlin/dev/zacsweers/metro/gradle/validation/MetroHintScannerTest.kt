// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.validation

import assertk.assertThat
import assertk.assertions.hasSize
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
        ),
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
        ),
      )
      .isEqualTo(
        setOf("classes.jar!/metro/hints/AppKt.class", "classes.jar!/metro/hints/OtherKt.class"),
      )
  }

  @Test
  fun `Hilt markers map built-in components to scopes and skip test and injector markers`() {
    val classes = compileHiltHints()
    val hilt = setOf(HintFormat.HILT)
    val singletonModule = "hilt_aggregated_deps/_com_example_SingletonModule.class"
    val featureEntryPoint = "hilt_aggregated_deps/_com_example_FeatureEntryPoint.class"

    assertThat(findHints(classes, emptySet(), hilt))
      .isEqualTo(setOf(singletonModule, featureEntryPoint))
    assertThat(findHints(classes, setOf("javax/inject/Singleton"), hilt))
      .isEqualTo(setOf(singletonModule))
    assertThat(findHints(classes, setOf("dagger/hilt/components/SingletonComponent"), hilt))
      .isEqualTo(setOf(singletonModule))
    // Custom components have no known scope here, so their own ClassId selects them.
    assertThat(findHints(classes, setOf("com/example/Outer.FeatureComponent"), hilt))
      .isEqualTo(setOf(featureEntryPoint))
    // The activity marker only has component entry points, which the compiler ignores.
    assertThat(findHints(classes, setOf("dagger/hilt/android/scopes/ActivityScoped"), hilt))
      .isEmpty()
  }

  @Test
  fun `interop hints are ignored unless their format is enabled`() {
    val classes = compileHiltHints()

    assertThat(findHints(classes, emptySet(), setOf(HintFormat.METRO))).isEmpty()
    assertThat(findHints(classes, emptySet(), HintFormat.entries.toSet())).hasSize(2)
  }

  /**
   * Use javac to exercise real class-file layouts, including the two-slot long and double
   * constants. Functional tests exercise the corresponding Metro-generated Kotlin classes.
   */
  private fun compileHints(): Path {
    return compile(
      "metro/hints/AppKt.java" to
        """
        package metro.hints;
        public final class AppKt {
          private static final long serialVersion = 10000L;
          private static final double scale = 2.5;
          public static final String other = "com_example_ConstantOnly";
          public static void com_example_Scopes_App(Object contribution) {}
        }
        """,
      "metro/hints/OtherKt.java" to
        """
        package metro.hints;
        public final class OtherKt {
          public static void com_example_Scopes_LongApp(Object contribution) {}
        }
        """,
      "com/example/Unrelated.java" to
        """
        package com.example;
        public final class Unrelated {
          public static void com_example_Scopes_App(Object contribution) {}
        }
        """,
    )
  }

  /** Mirrors the class files that Hilt generates. A stub annotation stands in for its runtime. */
  private fun compileHiltHints(): Path {
    return compile(
      "dagger/hilt/processor/internal/aggregateddeps/AggregatedDeps.java" to
        """
          package dagger.hilt.processor.internal.aggregateddeps;
          import java.lang.annotation.Retention;
          import java.lang.annotation.RetentionPolicy;
          @Retention(RetentionPolicy.CLASS)
          public @interface AggregatedDeps {
            String[] components();
            String test() default "";
            String[] replaces() default {};
            String[] modules() default {};
            String[] entryPoints() default {};
            String[] componentEntryPoints() default {};
          }
          """,
      "hilt_aggregated_deps/_com_example_SingletonModule.java" to
        """
          package hilt_aggregated_deps;
          import dagger.hilt.processor.internal.aggregateddeps.AggregatedDeps;
          @AggregatedDeps(
              components = "dagger.hilt.components.SingletonComponent",
              modules = "com.example.SingletonModule")
          public class _com_example_SingletonModule {}
          """,
      "hilt_aggregated_deps/_com_example_FeatureEntryPoint.java" to
        """
          package hilt_aggregated_deps;
          import dagger.hilt.processor.internal.aggregateddeps.AggregatedDeps;
          @AggregatedDeps(
              components = "com.example.Outer.FeatureComponent",
              entryPoints = "com.example.FeatureEntryPoint")
          public class _com_example_FeatureEntryPoint {}
          """,
      "hilt_aggregated_deps/_com_example_TestModule.java" to
        """
          package hilt_aggregated_deps;
          import dagger.hilt.processor.internal.aggregateddeps.AggregatedDeps;
          @AggregatedDeps(
              components = "dagger.hilt.components.SingletonComponent",
              test = "com.example.MyTest",
              modules = "com.example.TestModule")
          public class _com_example_TestModule {}
          """,
      "hilt_aggregated_deps/_com_example_MainActivity_GeneratedInjector.java" to
        """
          package hilt_aggregated_deps;
          import dagger.hilt.processor.internal.aggregateddeps.AggregatedDeps;
          @AggregatedDeps(
              components = "dagger.hilt.android.components.ActivityComponent",
              componentEntryPoints = "com.example.MainActivity_GeneratedInjector")
          public class _com_example_MainActivity_GeneratedInjector {}
          """,
    )
  }

  /**
   * Javac requires system paths. Copy the compiled output to the fake filesystem for every scan.
   * Source paths are relative to the source root.
   */
  private fun compile(vararg sources: Pair<String, String>): Path {
    val sourceDir = temporaryFolder.newFolder().toOkioPath()
    val compiledClasses = temporaryFolder.newFolder().toOkioPath()
    val sourceFiles = sources.map { (relativePath, content) ->
      val file = sourceDir / relativePath
      FileSystem.SYSTEM.createDirectories(file.parent!!)
      FileSystem.SYSTEM.write(file) { writeUtf8(content.trimIndent()) }
      file.toString()
    }
    val exitCode =
      ToolProvider.getSystemJavaCompiler()
        .run(null, null, null, "-d", compiledClasses.toString(), *sourceFiles.toTypedArray())
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
  private fun findHints(
    artifact: Path,
    scopes: Set<String>,
    formats: Set<HintFormat> = setOf(HintFormat.METRO),
  ): Set<String> {
    return MetroHintScanner.findHints(artifact.toFile(), scopes, formats, fileSystem)
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
