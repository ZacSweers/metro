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

  @Test
  fun `Anvil hints select scope properties and accept older unnumbered scopes`() {
    val classes = compileInteropHints()
    val anvil = setOf(HintFormat.ANVIL)
    val foo = "anvil/hint/Com_example_FooKt.class"
    val legacy = "anvil/hint/merge/Com_example_LegacyKt.class"

    assertThat(findHints(classes, emptySet(), anvil)).isEqualTo(setOf(foo, legacy))
    assertThat(findHints(classes, setOf("com/example/Scopes.App"), anvil)).isEqualTo(setOf(foo))
    assertThat(findHints(classes, setOf("com/example/AppScope"), anvil)).isEqualTo(setOf(legacy))
    // Reference properties name the contributed class. Outer classes don't select nested scopes.
    assertThat(findHints(classes, setOf("com/example/Foo"), anvil)).isEmpty()
    assertThat(findHints(classes, setOf("com/example/Scopes"), anvil)).isEmpty()
  }

  @Test
  fun `kotlin-inject-anvil lookups follow origin chains to their scopes`() {
    val classes = compileInteropHints()
    val kotlinInjectAnvil = setOf(HintFormat.KOTLIN_INJECT_ANVIL)
    val bindings = "amazon/lastmile/inject/ComExampleBindings.class"
    val generated = "amazon/lastmile/inject/ComExampleGenerated.class"
    val missing = "amazon/lastmile/inject/ComExampleMissing.class"

    // DefaultImpls has no @Origin, so it isn't a lookup.
    assertThat(findHints(classes, emptySet(), kotlinInjectAnvil))
      .isEqualTo(setOf(bindings, generated, missing))
    // A lookup whose origin isn't in the artifact is always reported.
    assertThat(findHints(classes, setOf("com/example/AppScope"), kotlinInjectAnvil))
      .isEqualTo(setOf(bindings, missing))
    assertThat(findHints(classes, setOf("com/example/Scopes.App"), kotlinInjectAnvil))
      .isEqualTo(setOf(generated, missing))
  }

  @Test
  fun `kotlin-inject-anvil origins resolve inside jars and nested AAR jars`() {
    val classes = compileInteropHints()
    val jar = "/interop.jar".toPath()
    writeZip(jar, classEntries(classes))
    val aar = "/interop.aar".toPath()
    writeZip(aar, mapOf("classes.jar" to fileSystem.read(jar) { readByteArray() }))
    val kotlinInjectAnvil = setOf(HintFormat.KOTLIN_INJECT_ANVIL)
    val scopes = setOf("com/example/Scopes.App")
    val generated = "amazon/lastmile/inject/ComExampleGenerated.class"
    val missing = "amazon/lastmile/inject/ComExampleMissing.class"

    assertThat(findHints(jar, scopes, kotlinInjectAnvil)).isEqualTo(setOf(generated, missing))
    assertThat(findHints(aar, scopes, kotlinInjectAnvil))
      .isEqualTo(setOf("classes.jar!/$generated", "classes.jar!/$missing"))
  }

  @Test
  fun `Hilt markers map built-in components to scopes and skip test and injector markers`() {
    val classes = compileInteropHints()
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
    val classes = compileInteropHints()

    assertThat(findHints(classes, emptySet(), setOf(HintFormat.METRO))).isEmpty()
    assertThat(findHints(classes, emptySet(), HintFormat.entries.toSet())).hasSize(7)
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

  /**
   * Mirrors the class files that Anvil, kotlin-inject-anvil, and Hilt generate. Stub annotations
   * and a stub `KClass` stand in for their runtimes. `com/example/Missing.class` is removed after
   * compiling so one lookup's origin can't be found.
   */
  private fun compileInteropHints(): Path {
    val classes =
      compile(
        "kotlin/reflect/KClass.java" to "package kotlin.reflect; public interface KClass<T> {}",
        "com/example/AppScope.java" to "package com.example; public final class AppScope {}",
        "com/example/Scopes.java" to
          "package com.example; public final class Scopes { public static final class App {} }",
        "com/example/Foo.java" to "package com.example; public interface Foo {}",
        "com/example/Legacy.java" to "package com.example; public interface Legacy {}",
        "anvil/hint/Com_example_FooKt.java" to
          """
          package anvil.hint;
          import com.example.Foo;
          import com.example.Scopes;
          import kotlin.reflect.KClass;
          public final class Com_example_FooKt {
            private static final KClass<Foo> com_example_Foo_reference = null;
            private static final KClass<Scopes.App> com_example_Foo_scope0 = null;
          }
          """,
        "anvil/hint/merge/Com_example_LegacyKt.java" to
          """
          package anvil.hint.merge;
          import com.example.AppScope;
          import com.example.Legacy;
          import kotlin.reflect.KClass;
          public final class Com_example_LegacyKt {
            private static final KClass<Legacy> com_example_Legacy_reference = null;
            private static final KClass<AppScope> com_example_Legacy_scope = null;
          }
          """,
        "software/amazon/lastmile/kotlin/inject/anvil/internal/Origin.java" to
          """
          package software.amazon.lastmile.kotlin.inject.anvil.internal;
          import java.lang.annotation.Retention;
          import java.lang.annotation.RetentionPolicy;
          @Retention(RetentionPolicy.RUNTIME)
          public @interface Origin { Class<?> value(); }
          """,
        "com/example/ContributesTo.java" to
          """
          package com.example;
          import java.lang.annotation.Retention;
          import java.lang.annotation.RetentionPolicy;
          @Retention(RetentionPolicy.RUNTIME)
          public @interface ContributesTo { Class<?> scope(); }
          """,
        "com/example/Bindings.java" to
          """
          package com.example;
          @ContributesTo(scope = AppScope.class)
          public interface Bindings {}
          """,
        "com/example/OtherBindings.java" to
          """
          package com.example;
          @ContributesTo(scope = Scopes.App.class)
          public interface OtherBindings {}
          """,
        "com/example/Generated.java" to
          """
          package com.example;
          import software.amazon.lastmile.kotlin.inject.anvil.internal.Origin;
          @Origin(OtherBindings.class)
          public interface Generated {}
          """,
        "com/example/Missing.java" to
          """
          package com.example;
          @ContributesTo(scope = Scopes.App.class)
          public interface Missing {}
          """,
        "amazon/lastmile/inject/ComExampleBindings.java" to
          """
          package amazon.lastmile.inject;
          import software.amazon.lastmile.kotlin.inject.anvil.internal.Origin;
          @Origin(com.example.Bindings.class)
          public interface ComExampleBindings extends com.example.Bindings {
            final class DefaultImpls {}
          }
          """,
        "amazon/lastmile/inject/ComExampleGenerated.java" to
          """
          package amazon.lastmile.inject;
          import software.amazon.lastmile.kotlin.inject.anvil.internal.Origin;
          @Origin(com.example.Generated.class)
          public interface ComExampleGenerated {}
          """,
        "amazon/lastmile/inject/ComExampleMissing.java" to
          """
          package amazon.lastmile.inject;
          import software.amazon.lastmile.kotlin.inject.anvil.internal.Origin;
          @Origin(com.example.Missing.class)
          public interface ComExampleMissing {}
          """,
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
    fileSystem.delete(classes / "com/example/Missing.class")
    return classes
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
