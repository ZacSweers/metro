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
              @Provides fun provideString(): String = "Hello"
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
              @Provides fun provideString(): String = "Hello"
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
      "ExampleClass.kt:6:30 `@Provides` declarations may not have receiver parameters unless they are binds providers.",
      "ExampleClass.kt:7:38 `@Provides` declarations may not have receiver parameters unless they are binds providers.",
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
      "ExampleClass.kt:6:30 `@Provides` declarations may not have receiver parameters unless they are binds providers.",
      "ExampleClass.kt:7:38 `@Provides` declarations may not have receiver parameters unless they are binds providers.",
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

            @Suppress("PROVIDES_SHOULD_BE_PRIVATE")
            interface ExampleComponent {
              @Provides val provideInt: Int
              @Provides fun provideString(): String
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleClass.kt:7:17 `@Provides` declarations must have bodies.",
      "ExampleClass.kt:8:17 `@Provides` declarations must have bodies.",
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

  @Test
  fun `binds providers - interface - ok case`() {
    compile(
      kotlin(
        "ExampleClass.kt",
        """
            package test

            import dev.zacsweers.lattice.annotations.Provides

            interface ExampleComponent {
              @Provides val Int.bind: Number
              @Provides fun String.bind(): CharSequence
            }
          """
          .trimIndent(),
      ),
      expectedExitCode = ExitCode.OK,
    )
  }

  @Test
  fun `binds providers - interface - must not have bodies`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides

            interface ExampleComponent {
              @Provides val Int.bind: Number get() = 9
              @Provides fun String.bind(): CharSequence = "Hello"
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContainsAll(
      "ExampleClass.kt:6:21 `@Provides` declarations may not have receiver parameters unless they are binds providers.",
      "ExampleClass.kt:7:24 `@Provides` declarations may not have receiver parameters unless they are binds providers.",
    )
  }

  @Test
  fun `binds providers - interface - same types cannot have same qualifiers`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Named

            interface ExampleComponent {
              // Valid cases
              @Provides @Named("named") val Int.bindNamed: Int
              @Provides val @receiver:Named("named") Int.bindNamedReceiver: Int
              @Provides @Named("named") fun String.bindNamed(): String
              @Provides fun @receiver:Named("named") String.bindNamedReceiver(): String

              // Bad cases
              @Provides val Int.bindSelf: Int
              @Provides @Named("named") val @receiver:Named("named") Int.bindSameNamed: Int
              @Provides fun String.bindSelf(): String
              @Provides @Named("named") fun @receiver:Named("named") String.bindSameNamed(): String
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContainsAll(
      "ExampleClass.kt:14:21 Binds receiver type `kotlin.Int` is the same type and qualifier as the bound type `kotlin.Int`.",
      "ExampleClass.kt:15:62 Binds receiver type `@Named(\"named\") kotlin.Int` is the same type and qualifier as the bound type `@Named(\"named\") kotlin.Int`.",
      "ExampleClass.kt:16:24 Binds receiver type `kotlin.String` is the same type and qualifier as the bound type `kotlin.String`.",
      "ExampleClass.kt:17:65 Binds receiver type `@Named(\"named\") kotlin.String` is the same type and qualifier as the bound type `@Named(\"named\") kotlin.String`.",
    )
  }

  @Test
  fun `binds providers - interface - bound types must be subtypes`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Named

            interface ExampleComponent {
              // Valid cases
              @Provides fun String.bind(): CharSequence

              // Bad cases
              @Provides val Number.bind: Int
              @Provides fun CharSequence.bind(): String
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContainsAll(
      "ExampleClass.kt:11:24 Binds receiver type `kotlin.Number` is not a subtype of bound type `kotlin.Int`.",
      "ExampleClass.kt:12:30 Binds receiver type `kotlin.CharSequence` is not a subtype of bound type `kotlin.String`.",
    )
  }
}
