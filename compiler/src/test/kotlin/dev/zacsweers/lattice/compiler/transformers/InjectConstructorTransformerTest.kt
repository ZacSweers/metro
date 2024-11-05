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

import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.assertCallableFactory
import org.junit.Test

class InjectConstructorTransformerTest : LatticeCompilerTest() {

  @Test
  fun simple() {
    val result =
      compile(
        kotlin(
          "TestClass.kt",
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
          "TestClass.kt",
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
}
