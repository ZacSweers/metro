/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.compiler.transformers

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.lattice.compiler.ExampleClass
import dev.zacsweers.lattice.compiler.ExampleGraph
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.assertCallableFactory
import dev.zacsweers.lattice.compiler.assertNoArgCallableFactory
import dev.zacsweers.lattice.compiler.callProperty
import dev.zacsweers.lattice.compiler.createGraphViaFactory
import dev.zacsweers.lattice.compiler.createNewInstanceAs
import dev.zacsweers.lattice.compiler.generatedFactoryClass
import dev.zacsweers.lattice.compiler.generatedLatticeGraphClass
import dev.zacsweers.lattice.compiler.invokeCreateAsFactory
import dev.zacsweers.lattice.compiler.invokeNewInstance
import dev.zacsweers.lattice.provider
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Ignore
import org.junit.Test

class InjectConstructorTransformerTest : LatticeCompilerTest() {

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
              fun create(@BindsInstance int: Int): ExampleGraph
            }
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = otherModuleResult,
    ) {
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphViaFactory(2)
      assertThat(graph.callProperty<Callable<Int>>("exampleClass").call()).isEqualTo(2)
    }
  }

  @Ignore("Enable once we support private inject constructors")
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
              fun create(@BindsInstance int: Int): ExampleGraph
            }
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = otherModuleResult,
    ) {
      val graph = ExampleGraph.generatedLatticeGraphClass().createGraphViaFactory(2)
      assertThat(graph.callProperty<Callable<Int>>("exampleClass").call()).isEqualTo(2)
    }
  }
}
