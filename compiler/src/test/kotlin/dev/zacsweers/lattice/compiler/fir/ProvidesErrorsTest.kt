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
package dev.zacsweers.lattice.compiler.fir

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.assertContainsAll
import org.junit.Test

class ProvidesErrorsTest : LatticeCompilerTest() {

  @Test
  fun `provides should be private - in interface`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides

            interface ExampleComponent {
              @Provides val provideCharSequence: String get() = "Hello"
              @Provides private fun provideString(): String = "Hello"
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.OK,
      )
    result.assertContainsAll(
      "ExampleClass.kt:6:17 `@Provides` declarations should be private.",
      "ExampleClass.kt:7:17 `@Provides` declarations should be private.",
    )
  }

  @Test
  fun `provides should be private - in abstract class`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides

            abstract class ExampleComponent {
              @Provides val provideInt: Int = 0
              @Provides val provideCharSequence: String get() = "Hello"
              @Provides private fun provideString(): String = "Hello"
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.OK,
      )
    result.assertContainsAll(
      "ExampleClass.kt:6:17 `@Provides` declarations should be private.",
      "ExampleClass.kt:7:17 `@Provides` declarations should be private.",
      "ExampleClass.kt:8:17 `@Provides` declarations should be private.",
    )
  }

  @Test
  fun `provides cannot have receivers - interface`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides

            interface ExampleComponent {
              @Provides private val Long.provideInt: Int get() = this.toInt()
              @Provides private fun CharSequence.provideString(): String = this.toString()
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleClass.kt:6:30 `@Provides` declarations may not have receiver parameters.",
      "ExampleClass.kt:7:38 `@Provides` declarations may not have receiver parameters.",
    )
  }

  @Test
  fun `provides cannot have receivers - abstract class`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides

            abstract class ExampleComponent {
              @Provides private val Long.provideInt: Int get() = this.toInt()
              @Provides private fun CharSequence.provideString(): String = "Hello"
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleClass.kt:6:30 `@Provides` declarations may not have receiver parameters.",
      "ExampleClass.kt:7:38 `@Provides` declarations may not have receiver parameters.",
    )
  }

  @Test
  fun `provides must have a body - interface`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides

            interface ExampleComponent {
              @Provides val provideInt: Int
              @Provides private fun provideString(): String
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleClass.kt:6:17 `@Provides` declarations must have bodies.",
      "ExampleClass.kt:7:17 `@Provides` declarations must have bodies.",
    )
  }

  @Test
  fun `provides must have a body - abstract class`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides

            abstract class ExampleComponent {
              @Provides abstract val provideInt: Int
              @Provides abstract fun provideString(): String
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleClass.kt:6:26 `@Provides` declarations must have bodies.",
      "ExampleClass.kt:7:26 `@Provides` declarations must have bodies.",
    )
  }
}
