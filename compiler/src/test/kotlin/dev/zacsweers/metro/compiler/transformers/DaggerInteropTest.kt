// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import dagger.internal.codegen.KspComponentProcessor
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.compiler.ExampleClass
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedMetroGraphClass
import dev.zacsweers.metro.compiler.invokeInstanceMethod
import dev.zacsweers.metro.compiler.newInstanceStrict
import kotlin.test.assertNotNull
import org.jetbrains.kotlin.name.ClassId
import org.junit.Test

class DaggerInteropTest : MetroCompilerTest() {

  override val metroOptions: MetroOptions
    get() =
      MetroOptions(
        enableDaggerRuntimeInterop = true,
        customInjectAnnotations =
          setOf(
            ClassId.fromString("javax/inject/Inject"),
            ClassId.fromString("jakarta/inject/Inject"),
          ),
        customProviderTypes =
          setOf(
            ClassId.fromString("javax/inject/Provider"),
            ClassId.fromString("jakarta/inject/Provider"),
            ClassId.fromString("dagger/internal/Provider"),
          ),
        customLazyTypes = setOf(ClassId.fromString("dagger/Lazy")),
        customAssistedAnnotations = setOf(ClassId.fromString("dagger/assisted/Assisted")),
        customAssistedFactoryAnnotations =
          setOf(ClassId.fromString("dagger/assisted/AssistedFactory")),
        customAssistedInjectAnnotations =
          setOf(ClassId.fromString("dagger/assisted/AssistedInject")),
      )

  @Test
  fun `dagger factory class can be loaded`() {
    val firstCompilation =
      compile(
        SourceFile.java(
          "ExampleClass.java",
          """
          package test;

          import javax.inject.Inject;

          public class ExampleClass {
            @Inject public ExampleClass() {

            }
          }
        """
            .trimIndent(),
        ),
        compilationBlock = {
          configureKsp(true) { symbolProcessorProviders += KspComponentProcessor.Provider() }
        },
      )

    compile(
      source(
        """
          @DependencyGraph
          interface ExampleGraph {
            val exampleClass: ExampleClass
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      assertNotNull(graph.callProperty("exampleClass"))
    }
  }

  @Test
  fun `dagger factory class can be loaded - jakarta`() {
    val firstCompilation =
      compile(
        SourceFile.java(
          "ExampleClass.java",
          """
          package test;

          import jakarta.inject.Inject;

          public class ExampleClass {
            @Inject public ExampleClass() {

            }
          }
        """
            .trimIndent(),
        ),
        compilationBlock = {
          configureKsp(true) { symbolProcessorProviders += KspComponentProcessor.Provider() }
        },
      )

    compile(
      source(
        """
          @DependencyGraph
          interface ExampleGraph {
            val exampleClass: ExampleClass
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      assertNotNull(graph.callProperty("exampleClass"))
    }
  }

  @Test
  fun `kotlin dagger factory class can be loaded`() {
    val firstCompilation =
      compile(
        SourceFile.kotlin(
          "ExampleClass.kt",
          """
          package test

          import javax.inject.Inject

          class ExampleClass @Inject constructor()
        """
            .trimIndent(),
        ),
        SourceFile.kotlin(
          "ExampleClass_Factory.kt",
          """
          package test

          import dagger.internal.Factory

          class ExampleClass_Factory : Factory<ExampleClass> {
            override fun get(): ExampleClass = newInstance()

            companion object {
              @JvmStatic
              fun create(): ExampleClass_Factory = ExampleClass_Factory()

              @JvmStatic
              fun newInstance(): ExampleClass = ExampleClass()
            }
          }
        """
            .trimIndent(),
        ),
      )

    compile(
      source(
        """
          @DependencyGraph
          interface ExampleGraph {
            val exampleClass: ExampleClass
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      assertNotNull(graph.callProperty("exampleClass"))
    }
  }

  // Anvil may generate objects
  @Test
  fun `kotlin dagger object factory class can be loaded`() {
    val firstCompilation =
      compile(
        SourceFile.kotlin(
          "ExampleClass.kt",
          """
          package test

          import javax.inject.Inject

          class ExampleClass @Inject constructor()
        """
            .trimIndent(),
        ),
        SourceFile.kotlin(
          "ExampleClass_Factory.kt",
          """
          package test

          import dagger.internal.Factory

          object ExampleClass_Factory : Factory<ExampleClass> {
            override fun get(): ExampleClass = newInstance()

            @JvmStatic
            fun create(): ExampleClass_Factory = ExampleClass_Factory

            @JvmStatic
            fun newInstance(): ExampleClass = ExampleClass()
          }
        """
            .trimIndent(),
        ),
      )

    compile(
      source(
        """
          @DependencyGraph
          interface ExampleGraph {
            val exampleClass: ExampleClass
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      assertNotNull(graph.callProperty("exampleClass"))
    }
  }

  @Test
  fun `dagger factory class with different inputs`() {
    val firstCompilation =
      compile(
        SourceFile.java(
          "ExampleClass.java",
          """
          package test;

          import javax.inject.Inject;
          import javax.inject.Provider;
          import dagger.Lazy;

          public class ExampleClass {
            @Inject public ExampleClass(String value, Provider<String> provider, Lazy<String> lazy) {

            }
          }
        """
            .trimIndent(),
        ),
        compilationBlock = {
          configureKsp(true) { symbolProcessorProviders += KspComponentProcessor.Provider() }
        },
      )

    compile(
      source(
        """
          @DependencyGraph
          interface ExampleGraph {
            val exampleClass: ExampleClass

            @Provides fun provideString(): String = "hello"
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      assertNotNull(graph.callProperty("exampleClass"))
    }
  }

  @Test
  fun `assisted dagger factory class`() {
    val firstCompilation =
      compile(
        SourceFile.java(
          "ExampleClass.java",
          """
          package test;

          import javax.inject.Inject;
          import javax.inject.Provider;
          import dagger.Lazy;
          import dagger.assisted.Assisted;
          import dagger.assisted.AssistedInject;
          import dagger.assisted.AssistedFactory;

          public class ExampleClass {
            @AssistedInject public ExampleClass(
              @Assisted int intValue
            ) {
            }
            @AssistedFactory
            public interface Factory {
              ExampleClass create(int intValue);
            }
          }
        """
            .trimIndent(),
        ),
        compilationBlock = {
          configureKsp(true) { symbolProcessorProviders += KspComponentProcessor.Provider() }
        },
      )

    compile(
      source(
        """
          @DependencyGraph
          interface ExampleGraph {
            val exampleClassFactory: ExampleClass.Factory
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val factory = assertNotNull(graph.callProperty("exampleClassFactory"))
      assertNotNull(factory.invokeInstanceMethod("create", 1))
    }
  }

  @Test
  fun `assisted dagger factory class with different inputs`() {
    val firstCompilation =
      compile(
        SourceFile.java(
          "ExampleClass.java",
          """
          package test;

          import javax.inject.Inject;
          import javax.inject.Provider;
          import dagger.Lazy;
          import dagger.assisted.Assisted;
          import dagger.assisted.AssistedInject;
          import dagger.assisted.AssistedFactory;

          public class ExampleClass {
            @AssistedInject public ExampleClass(
              @Assisted int intValue,
              String value,
              Provider<String> provider,
              Lazy<String> lazy
            ) {
            }
            @AssistedFactory
            public interface Factory {
              ExampleClass create(int intValue);
            }
          }
        """
            .trimIndent(),
        ),
        compilationBlock = {
          configureKsp(true) { symbolProcessorProviders += KspComponentProcessor.Provider() }
        },
      )

    compile(
      source(
        """
          @DependencyGraph
          interface ExampleGraph {
            val exampleClassFactory: ExampleClass.Factory

            @Provides fun provideString(): String = "hello"
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val factory = assertNotNull(graph.callProperty("exampleClassFactory"))
      assertNotNull(factory.invokeInstanceMethod("create", 1))
    }
  }

  @Test
  fun `dagger members injector can be loaded`() {
    val firstCompilation =
      compile(
        SourceFile.java(
          "ExampleClass.java",
          """
          package test;

          import javax.inject.Inject;

          public class ExampleClass {
            @Inject public String string;

            public ExampleClass() {

            }
          }
        """
            .trimIndent(),
        ),
        compilationBlock = {
          configureKsp(true) { symbolProcessorProviders += KspComponentProcessor.Provider() }
        },
      )

    compile(
      source(
        """
          @DependencyGraph
          interface ExampleGraph {
            val exampleClassInjector: MembersInjector<ExampleClass>

            fun inject(exampleClass: ExampleClass)

            @Provides fun provideString(): String = "hello"
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val exampleClass1 = ExampleClass.newInstanceStrict()
      val injector = graph.callProperty<MembersInjector<Any>>("exampleClassInjector")
      injector.injectMembers(exampleClass1)
      assertThat(exampleClass1.javaClass.getDeclaredField("string").get(exampleClass1))
        .isEqualTo("hello")
      // TODO inject() function
    }
  }
}
