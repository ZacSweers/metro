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
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.assertContainsAll
import org.junit.Test

class ProvidesErrorsTest : LatticeCompilerTest() {

  @Test
  fun `provides should be private - in interface`() {
    val result =
      compile(
        source(
          """
            interface ExampleGraph {
              @Provides val provideCharSequence: String get() = "Hello"
              @Provides fun provideString(): String = "Hello"
            }
          """
            .trimIndent()
        )
      )
    result.assertContainsAll(
      "ExampleGraph.kt:9:17 `@Provides` declarations should be private.",
      "ExampleGraph.kt:10:17 `@Provides` declarations should be private.",
    )
  }

  @Test
  fun `provides should be private - in abstract class`() {
    val result =
      compile(
        source(
          """
            abstract class ExampleGraph {
              @Provides val provideInt: Int = 0
              @Provides val provideCharSequence: String get() = "Hello"
              @Provides fun provideString(): String = "Hello"
            }
          """
            .trimIndent()
        )
      )
    result.assertContainsAll(
      "ExampleGraph.kt:9:17 `@Provides` declarations should be private.",
      "ExampleGraph.kt:10:17 `@Provides` declarations should be private.",
      "ExampleGraph.kt:11:17 `@Provides` declarations should be private.",
    )
  }

  @Test
  fun `binds with bodies should be private - in interface`() {
    val result =
      compile(
        source(
          """
            interface ExampleGraph {
              @Binds val String.provideCharSequence: CharSequence get() = this
              @Binds fun Int.provideNumber(): Number = this
            }
          """
            .trimIndent()
        )
      )
    result.assertContainsAll(
      "ExampleGraph.kt:9:21 `@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private.",
      "ExampleGraph.kt:10:18 `@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private.",
    )
  }

  @Test
  fun `binds with bodies should be private - in abstract class`() {
    val result =
      compile(
        source(
          """
            abstract class ExampleGraph {
              @Binds val String.provideCharSequence: CharSequence get() = this
              @Binds fun Int.provideNumber(): Number = this
            }
          """
            .trimIndent()
        )
      )
    result.assertContainsAll(
      "ExampleGraph.kt:9:21 `@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private.",
      "ExampleGraph.kt:10:18 `@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private.",
    )
  }

  @Test
  fun `provides with extensions and non-this-returning bodies should error`() {
    val result =
      compile(
        source(
          """
            abstract class ExampleGraph {
              @Provides val String.provideCharSequence: CharSequence get() = "hello"
              @Provides fun Int.provideNumber(): Number = 3
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleGraph.kt:9:24 `@Provides` properties may not be extension properties. Use `@Binds` instead for these.",
      "ExampleGraph.kt:10:21 `@Provides` functions may not be extension functions. Use `@Binds` instead for these.",
    )
  }

  @Test
  fun `binds non-this-returning bodies should error - interface`() {
    val result =
      compile(
        source(
          """
            interface ExampleGraph {
              @Binds val String.provideCharSequence: CharSequence get() = "something else"
              @Binds fun Int.provideNumber(): Number = 3
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleGraph.kt:9:21 `@Binds` declarations with bodies should just return `this`.",
      "ExampleGraph.kt:10:18 `@Binds` declarations with bodies should just return `this`.",
    )
  }

  @Test
  fun `binds non-this-returning bodies should error - abstract class`() {
    val result =
      compile(
        source(
          """
            abstract class ExampleGraph {
              @Binds val String.provideCharSequence: CharSequence get() = "something else"
              @Binds fun Int.provideNumber(): Number = 3
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleGraph.kt:9:21 `@Binds` declarations with bodies should just return `this`.",
      "ExampleGraph.kt:10:18 `@Binds` declarations with bodies should just return `this`.",
    )
  }

  @Test
  fun `provides cannot have receivers - interface`() {
    val result =
      compile(
        source(
          """
            interface ExampleGraph {
              @Provides private val Long.provideInt: Int get() = this.toInt()
              @Provides private fun CharSequence.provideString(): String = this.toString()
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleGraph.kt:9:30 `@Provides` properties may not be extension properties. Use `@Binds` instead for these.",
      "ExampleGraph.kt:10:38 `@Provides` functions may not be extension functions. Use `@Binds` instead for these.",
    )
  }

  @Test
  fun `provides cannot have receivers - abstract class`() {
    val result =
      compile(
        source(
          """
            abstract class ExampleGraph {
              @Provides private val Long.provideInt: Int get() = this.toInt()
              @Provides private fun CharSequence.provideString(): String = "Hello"
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleGraph.kt:9:30 `@Provides` properties may not be extension properties. Use `@Binds` instead for these.",
      "ExampleGraph.kt:10:38 `@Provides` functions may not be extension functions. Use `@Binds` instead for these.",
    )
  }

  @Test
  fun `provides must have a body - interface`() {
    val result =
      compile(
        source(
          """
            @Suppress("PROVIDES_SHOULD_BE_PRIVATE")
            interface ExampleGraph {
              @Provides val provideInt: Int
              @Provides fun provideString(): String
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleGraph.kt:10:17 `@Provides` declarations must have bodies.",
      "ExampleGraph.kt:11:17 `@Provides` declarations must have bodies.",
    )
  }

  @Test
  fun `provides must have a body - abstract class`() {
    val result =
      compile(
        source(
          """
            abstract class ExampleGraph {
              @Provides abstract val provideInt: Int
              @Provides abstract fun provideString(): String
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleGraph.kt:9:26 `@Provides` declarations must have bodies.",
      "ExampleGraph.kt:10:26 `@Provides` declarations must have bodies.",
    )
  }

  @Test
  fun `binds - interface - ok case`() {
    compile(
      source(
        """
            interface ExampleGraph {
              @Binds val Int.bind: Number
              @Binds fun String.bind(): CharSequence
            }
          """
          .trimIndent()
      )
    )
  }

  @Test
  fun `binds - interface - should not have bodies`() {
    val result =
      compile(
        source(
          """
            interface ExampleGraph {
              @Binds val Int.bind: Number get() = this
              @Binds fun String.bind(): CharSequence = this
            }
          """
            .trimIndent()
        )
      )

    result.assertContainsAll(
      "ExampleGraph.kt:9:18 `@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private.",
      "ExampleGraph.kt:10:21 `@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private.",
    )
  }

  @Test
  fun `binds - interface - same types cannot have same qualifiers`() {
    val result =
      compile(
        source(
          """
            interface ExampleGraph {
              // Valid cases
              @Binds @Named("named") val Int.bindNamed: Int
              @Binds val @receiver:Named("named") Int.bindNamedReceiver: Int
              @Binds @Named("named") fun String.bindNamed(): String
              @Binds fun @receiver:Named("named") String.bindNamedReceiver(): String

              // Bad cases
              @Binds val Int.bindSelf: Int
              @Binds @Named("named") val @receiver:Named("named") Int.bindSameNamed: Int
              @Binds fun String.bindSelf(): String
              @Binds @Named("named") fun @receiver:Named("named") String.bindSameNamed(): String
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContainsAll(
      "ExampleGraph.kt:16:18 Binds receiver type `kotlin.Int` is the same type and qualifier as the bound type `kotlin.Int`.",
      "ExampleGraph.kt:17:59 Binds receiver type `@Named(\"named\") kotlin.Int` is the same type and qualifier as the bound type `@Named(\"named\") kotlin.Int`.",
      "ExampleGraph.kt:18:21 Binds receiver type `kotlin.String` is the same type and qualifier as the bound type `kotlin.String`.",
      "ExampleGraph.kt:19:62 Binds receiver type `@Named(\"named\") kotlin.String` is the same type and qualifier as the bound type `@Named(\"named\") kotlin.String`.",
    )
  }

  @Test
  fun `binds - interface - bound types must be subtypes`() {
    val result =
      compile(
        source(
          """
            interface ExampleGraph {
              // Valid cases
              @Binds fun String.bind(): CharSequence

              // Bad cases
              @Binds val Number.bind: Int
              @Binds fun CharSequence.bind(): String
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContainsAll(
      "ExampleGraph.kt:13:21 Binds receiver type `kotlin.Number` is not a subtype of bound type `kotlin.Int`.",
      "ExampleGraph.kt:14:27 Binds receiver type `kotlin.CharSequence` is not a subtype of bound type `kotlin.String`.",
    )
  }
}
