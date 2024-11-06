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
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import dev.zacsweers.lattice.compiler.ExampleClass
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.assertCallableFactory
import dev.zacsweers.lattice.compiler.assertNoArgCallableFactory
import dev.zacsweers.lattice.compiler.generatedFactoryClass
import dev.zacsweers.lattice.compiler.invokeCreate
import dev.zacsweers.lattice.compiler.invokeNewInstance
import org.junit.Test

class InjectConstructorTransformerTest : LatticeCompilerTest() {

  @Test
  fun simple() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject
            import java.util.concurrent.Callable

            class ExampleClass @Inject constructor(private val value: String) : Callable<String> {
              override fun call(): String = value
            }

          """
            .trimIndent(),
        ),
        debug = true,
      )
    result.assertCallableFactory("Hello, world!")
  }

  @Test
  fun simpleGeneric() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject
            import java.util.concurrent.Callable

            class ExampleClass<T> @Inject constructor(private val value: T) : Callable<T> {
              override fun call(): T = value
            }

          """
            .trimIndent(),
        ),
        debug = true,
      )
    result.assertCallableFactory("Hello, world!")
  }

  @Test
  fun `class annotated with inject`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject
            import java.util.concurrent.Callable

            @Inject 
            class ExampleClass(private val value: String) : Callable<String> {
              override fun call(): String = value
            }

          """
            .trimIndent(),
        ),
        debug = true,
      )
    result.assertCallableFactory("Hello, world!")
  }

  @Test
  fun `class annotated with inject and no constructor or params`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject
            import java.util.concurrent.Callable

            @Inject 
            class ExampleClass : Callable<String> {
              override fun call(): String = "Hello, world!"
            }

          """
            .trimIndent(),
        ),
        debug = true,
      )

    val factoryClass = result.ExampleClass.generatedFactoryClass()

    // Assert that the factory class is a singleton since there are no args
    val factory1 = factoryClass.invokeCreate()
    val factory2 = factoryClass.invokeCreate()
    assertThat(factory1).isSameInstanceAs(factory2)

    // Assert that newInstance still returns new instances
    assertThat(factoryClass.invokeNewInstance())
      .isNotSameInstanceAs(factoryClass.invokeNewInstance())

    // Last smoke test on functionality
    result.assertNoArgCallableFactory("Hello, world!")
  }
}
