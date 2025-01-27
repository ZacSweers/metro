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
package dev.zacsweers.metro.compiler.fir

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.assertNoWarningsOrErrors
import org.junit.Test

class ProvidesErrorsTest : MetroCompilerTest() {

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
        ),
        options = MetroOptions(publicProviderSeverity = MetroOptions.DiagnosticSeverity.WARN),
      )
    result.assertDiagnostics(
      """
        w: ExampleGraph.kt:7:17 `@Provides` declarations should be private.
        w: ExampleGraph.kt:8:17 `@Provides` declarations should be private.
      """
        .trimIndent()
    )
  }

  @Test
  fun `private provider option - none`() {
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
        ),
        options = MetroOptions(publicProviderSeverity = MetroOptions.DiagnosticSeverity.NONE),
      )
    result.assertNoWarningsOrErrors()
  }

  @Test
  fun `private provider option - error`() {
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
        ),
        options = MetroOptions(publicProviderSeverity = MetroOptions.DiagnosticSeverity.ERROR),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:7:17 `@Provides` declarations should be private.
        e: ExampleGraph.kt:8:17 `@Provides` declarations should be private.
      """
        .trimIndent()
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
        ),
        options = MetroOptions(publicProviderSeverity = MetroOptions.DiagnosticSeverity.WARN),
      )
    result.assertDiagnostics(
      """
        w: ExampleGraph.kt:7:17 `@Provides` declarations should be private.
        w: ExampleGraph.kt:8:17 `@Provides` declarations should be private.
        w: ExampleGraph.kt:9:17 `@Provides` declarations should be private.
      """
        .trimIndent()
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
        ),
        options = MetroOptions(publicProviderSeverity = MetroOptions.DiagnosticSeverity.WARN),
      )
    result.assertDiagnostics(
      """
        w: ExampleGraph.kt:7:21 `@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private.
        w: ExampleGraph.kt:8:18 `@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private.
      """
        .trimIndent()
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
        ),
        options = MetroOptions(publicProviderSeverity = MetroOptions.DiagnosticSeverity.WARN),
      )
    result.assertDiagnostics(
      """
        w: ExampleGraph.kt:7:21 `@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private.
        w: ExampleGraph.kt:8:18 `@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private.
      """
        .trimIndent()
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
    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:7:24 `@Provides` properties may not be extension properties. Use `@Binds` instead for these.
        e: ExampleGraph.kt:8:21 `@Provides` functions may not be extension functions. Use `@Binds` instead for these.
      """
        .trimIndent()
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
    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:7:21 `@Binds` declarations with bodies should just return `this`.
        e: ExampleGraph.kt:8:18 `@Binds` declarations with bodies should just return `this`.
      """
        .trimIndent()
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
    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:7:21 `@Binds` declarations with bodies should just return `this`.
        e: ExampleGraph.kt:8:18 `@Binds` declarations with bodies should just return `this`.
      """
        .trimIndent()
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

    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:7:30 `@Provides` properties may not be extension properties. Use `@Binds` instead for these.
        e: ExampleGraph.kt:8:38 `@Provides` functions may not be extension functions. Use `@Binds` instead for these.
      """
        .trimIndent()
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
    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:7:30 `@Provides` properties may not be extension properties. Use `@Binds` instead for these.
        e: ExampleGraph.kt:8:38 `@Provides` functions may not be extension functions. Use `@Binds` instead for these.
      """
        .trimIndent()
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
    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:8:17 `@Provides` declarations must have bodies.
        e: ExampleGraph.kt:9:17 `@Provides` declarations must have bodies.
      """
        .trimIndent()
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
    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:7:26 `@Provides` declarations must have bodies.
        e: ExampleGraph.kt:8:26 `@Provides` declarations must have bodies.
      """
        .trimIndent()
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
        ),
        options = MetroOptions(publicProviderSeverity = MetroOptions.DiagnosticSeverity.WARN),
      )

    result.assertDiagnostics(
      """
        w: ExampleGraph.kt:7:18 `@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private.
        w: ExampleGraph.kt:8:21 `@Binds` declarations rarely need to have bodies unless they are also private. Consider removing the body or making this private.
      """
        .trimIndent()
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

    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:14:18 Binds receiver type `kotlin.Int` is the same type and qualifier as the bound type `kotlin.Int`.
        e: ExampleGraph.kt:15:59 Binds receiver type `@Named("named") kotlin.Int` is the same type and qualifier as the bound type `@Named("named") kotlin.Int`.
        e: ExampleGraph.kt:16:21 Binds receiver type `kotlin.String` is the same type and qualifier as the bound type `kotlin.String`.
        e: ExampleGraph.kt:17:62 Binds receiver type `@Named("named") kotlin.String` is the same type and qualifier as the bound type `@Named("named") kotlin.String`.
      """
        .trimIndent()
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

    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:11:21 Binds receiver type `kotlin.Number` is not a subtype of bound type `kotlin.Int`.
        e: ExampleGraph.kt:12:27 Binds receiver type `kotlin.CharSequence` is not a subtype of bound type `kotlin.String`.
      """
        .trimIndent()
    )
  }

  @Test
  fun `provides - interface - may not have type parameters`() {
    val result =
      compile(
        source(
          """
            interface ExampleGraph {
              @Provides
              fun <T> provideString(): String = "Hello"

              companion object {
                @Provides
                fun <T> provideInt(): Int = 0
              }
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:8:7 `@Provides` declarations may not have type parameters.
        e: ExampleGraph.kt:12:9 `@Provides` declarations may not have type parameters.
      """
        .trimIndent()
    )
  }

  @Test
  fun `provides - abstract class - may not have type parameters`() {
    val result =
      compile(
        source(
          """
            abstract class ExampleGraph {
              @Provides
              fun <T> provideString(): String = "Hello"

              companion object {
                @Provides
                fun <T> provideInt(): Int = 0
              }
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:8:7 `@Provides` declarations may not have type parameters.
        e: ExampleGraph.kt:12:9 `@Provides` declarations may not have type parameters.
      """
        .trimIndent()
    )
  }

  @Test
  fun `provides must have explicit return types`() {
    val result =
      compile(
        source(
          """
            abstract class ExampleGraph {
              @Provides
              fun provideString() = "Hello"

              companion object {
                @Provides
                fun provideInt() = 0
              }
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:8:7 Implicit return types are not allowed for `@Provides` declarations. Specify the return type explicitly.
        e: ExampleGraph.kt:12:9 Implicit return types are not allowed for `@Provides` declarations. Specify the return type explicitly.
      """
        .trimIndent()
    )
  }
}
