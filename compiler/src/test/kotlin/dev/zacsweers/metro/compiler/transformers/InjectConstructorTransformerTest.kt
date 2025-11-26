// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.compiler.ExampleClass
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertCallableFactory
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.assertNoArgCallableFactory
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphViaFactory
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.createNewInstanceAs
import dev.zacsweers.metro.compiler.generatedFactoryClass
import dev.zacsweers.metro.compiler.generatedImpl
import dev.zacsweers.metro.compiler.invokeCreateAsFactory
import dev.zacsweers.metro.compiler.invokeNewInstance
import dev.zacsweers.metro.provider
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class InjectConstructorTransformerTest : MetroCompilerTest() {

  @Test
  fun simple() {
    compile(
      source(
        """
        class ExampleClass @Inject constructor(private val value: String) : Callable<String> {
          override fun call(): String = value
        }
        """
          .trimIndent()
      )
    ) {
      assertCallableFactory("Hello, world!")
    }
  }

  @Test
  fun simpleGeneric() {
    compile(
      source(
        """
        class ExampleClass<T> @Inject constructor(private val value: T) : Callable<T> {
          override fun call(): T = value
        }

        """
          .trimIndent()
      )
    ) {
      assertCallableFactory("Hello, world!")
    }
  }

  @Test
  fun `class annotated with inject`() {
    compile(
      source(
        """
        @Inject
        class ExampleClass(private val value: String) : Callable<String> {
          override fun call(): String = value
        }

        """
          .trimIndent()
      )
    ) {
      assertCallableFactory("Hello, world!")
    }
  }

  @Test
  fun `class annotated with inject and no constructor or params`() {
    compile(
      source(
        """
        @Inject
        class ExampleClass : Callable<String> {
          override fun call(): String = "Hello, world!"
        }

        """
          .trimIndent()
      )
    ) {
      val factoryClass = ExampleClass.generatedFactoryClass()

      // Assert that the factory class is a singleton since there are no args
      val factory1 = factoryClass.invokeCreateAsFactory()
      val factory2 = factoryClass.invokeCreateAsFactory()
      assertThat(factory1).isSameInstanceAs(factory2)

      // Assert that newInstance still returns new instances
      assertThat(factoryClass.invokeNewInstance())
        .isNotSameInstanceAs(factoryClass.invokeNewInstance())

      // Last smoke test on functionality
      assertNoArgCallableFactory("Hello, world!")
    }
  }

  @Test
  fun `injected providers`() {
    compile(
      source(
        """
        @Inject
        class ExampleClass(private val value: Provider<String>) : Callable<String> {
          override fun call(): String = value()
        }

        """
          .trimIndent()
      )
    ) {
      val factory = ExampleClass.generatedFactoryClass()
      val counter = AtomicInteger()
      val provider = provider { "Hello World! - ${counter.andIncrement}" }
      val instance = factory.createNewInstanceAs<Callable<String>>(provider)
      // Calling multiple times calls the provider every time
      assertThat(instance.call()).isEqualTo("Hello World! - 0")
      assertThat(instance.call()).isEqualTo("Hello World! - 1")
      assertThat(counter.get()).isEqualTo(2)
    }
  }

  @Test
  fun `injected lazy`() {
    compile(
      source(
        """
        @Inject
        class ExampleClass(private val value: Lazy<String>) : Callable<String> {
          override fun call(): String = value.value
        }

        """
          .trimIndent()
      )
    ) {
      val factoryClass = ExampleClass.generatedFactoryClass()
      val counter = AtomicInteger()
      val provider = provider { "Hello World! - ${counter.andIncrement}" }
      val instance = factoryClass.createNewInstanceAs<Callable<String>>(provider)
      // Calling multiple times caches the lazy instance
      assertThat(instance.call()).isEqualTo("Hello World! - 0")
      assertThat(instance.call()).isEqualTo("Hello World! - 0")
      assertThat(counter.get()).isEqualTo(1)
    }
  }

  @Test
  fun `injected provider of lazy`() {
    compile(
      source(
        """
        @Inject
        class ExampleClass(private val value: Provider<Lazy<String>>) : Callable<Lazy<String>> {
          override fun call(): Lazy<String> = value()
        }

        """
          .trimIndent()
      )
    ) {
      val factoryClass = ExampleClass.generatedFactoryClass()
      val counter = AtomicInteger()
      val provider = provider { "Hello World! - ${counter.andIncrement}" }
      val instance = factoryClass.createNewInstanceAs<Callable<Lazy<String>>>(provider)
      // Every call creates a new Lazy instance
      // Calling multiple times caches the lazy instance
      val lazy = instance.call()
      assertThat(lazy.value).isEqualTo("Hello World! - 0")
      assertThat(lazy.value).isEqualTo("Hello World! - 0")
      val lazy2 = instance.call()
      assertThat(lazy2.value).isEqualTo("Hello World! - 1")
      assertThat(lazy2.value).isEqualTo("Hello World! - 1")
      assertThat(counter.get()).isEqualTo(2)
    }
  }

  @Test
  fun `an injected class is visible from another module`() {
    val otherModuleResult =
      compile(
        source(
          """
          @Inject
          class ExampleClass(private val value: Int) : Callable<Int> {
            override fun call(): Int = value
          }
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val exampleClass: ExampleClass

          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides int: Int): ExampleGraph
          }
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = otherModuleResult,
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphViaFactory(2)
      assertThat(graph.callProperty<Callable<Int>>("exampleClass").call()).isEqualTo(2)
    }
  }

  @Test
  fun `an injected class with a private constructor is visible from another module`() {
    val otherModuleResult =
      compile(
        source(
          """
          @Inject
          class ExampleClass private constructor(private val value: Int) : Callable<Int> {
            override fun call(): Int = value
          }
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val exampleClass: ExampleClass

          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides int: Int): ExampleGraph
          }
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = otherModuleResult,
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphViaFactory(2)
      assertThat(graph.callProperty<Callable<Int>>("exampleClass").call()).isEqualTo(2)
    }
  }

  @Test
  fun `a constructor can be injected with a parameter annotated with a qualifier with a class argument (satisfied by a provider)`() {
    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph {
          // This is fine
          @ForScope(AppScope::class)
          val int: Int

          val myClass: MyClass
        }

        @ContributesTo(AppScope::class)
        interface ContributedInterface {
          @Provides @ForScope(AppScope::class) fun provideInt(): Int = 2
        }

        class MyClass @Inject constructor(
          // This fails
          @ForScope(AppScope::class)
          val int: Int
        )
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(graph.callProperty<Int>("int")).isEqualTo(2)
      assertThat(graph.callProperty<Any>("myClass").callProperty<Int>("int")).isEqualTo(2)
    }
  }

  @Test
  fun `a constructor can be injected with a parameter annotated with a qualifier with a class argument (satisfied by a binding)`() {
    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          // This is fine
          @ForScope(AppScope::class)
          val contributedInterface: ContributedInterface

          val myClass: MyClass
        }

        interface ContributedInterface

        @ContributesBinding(AppScope::class, binding<@ForScope(AppScope::class) ContributedInterface>())
        @Inject
        class Impl : ContributedInterface

        class MyClass @Inject constructor(
          // This fails
          @ForScope(AppScope::class)
          val contributedInterface: ContributedInterface
        )
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl")

      val myClass = graph.callProperty<Any>("myClass")
      val myClassField = myClass.callProperty<Any>("contributedInterface")
      assertThat(myClass).isNotNull()
      assertThat(myClassField).isNotNull()
      assertThat(myClassField.javaClass.name).isEqualTo("test.Impl")
    }
  }

  @Test
  fun `dagger reusable is unsupported`() {
    compile(
      source(
        """
        import dagger.Reusable

        @Reusable
        @Inject
        class MyClass
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: MyClass.kt:8:1 Dagger's `@Reusable` is not supported in Metro. See https://zacsweers.github.io/metro/latest/faq#why-doesnt-metro-support-reusable for more information.
        """
          .trimIndent()
      )
    }
  }

  // https://github.com/ZacSweers/metro/issues/935
  // Covers a use-case where we would previously get a Kotlin internal error that read:
  // e: kotlin.NotImplementedError: An operation is not implemented: Unknown file
  @Test
  fun `when no factory is found for a Java class, the exception should still specify it in the error message`() {
    val previousResult =
      compile(
        sourceJava(
          """
          public class ExampleClass {
            @Inject public ExampleClass() {

            }
          }
          """
            .trimIndent()
        )
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
      expectedExitCode = ExitCode.COMPILATION_ERROR,
      options = metroOptions.copy(enableDaggerRuntimeInterop = true),
      previousCompilationResult = previousResult,
    ) {
      assertDiagnostics(
        """
        e: Could not find generated factory for 'test.ExampleClass' in the upstream module where it's defined. Run the Metro compiler over that module too (or Dagger if you're using its interop).

        e: ExampleGraph.kt:8:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: test.ExampleClass

            test.ExampleClass is requested at
                [test.ExampleGraph] test.ExampleGraph.exampleClass
        """
          .trimIndent()
      )
    }
  }
}
