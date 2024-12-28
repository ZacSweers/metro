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
import dev.zacsweers.lattice.compiler.assertDiagnostics
import org.junit.Test

class DependencyGraphErrorsTest : LatticeCompilerTest() {

  @Test
  fun `graphs must be well-formed`() {
    val result =
      compile(
        source(
          fileNameWithoutExtension = "graphs",
          source =
            """
            // Ok
            @DependencyGraph interface InterfaceGraph
            @DependencyGraph abstract class AbstractClassGraph

            // Not ok
            @DependencyGraph class FinalClassGraph
            @DependencyGraph open class OpenClassGraph
            @DependencyGraph annotation class AnnotationClassGraph
            @DependencyGraph enum class EnumClassGraph
            @DependencyGraph sealed class SealedClassGraph
            @DependencyGraph sealed interface SealedInterfaceGraph
            @DependencyGraph private interface PrivateGraph
            @DependencyGraph abstract class PrivateConstructorGraph private constructor()
          """
              .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      """
        e: graphs.kt:11:24 DependencyGraph classes should be non-sealed abstract classes or interfaces.
        e: graphs.kt:12:29 DependencyGraph classes should be non-sealed abstract classes or interfaces.
        e: graphs.kt:13:35 DependencyGraph classes should be non-sealed abstract classes or interfaces.
        e: graphs.kt:14:29 DependencyGraph classes should be non-sealed abstract classes or interfaces.
        e: graphs.kt:15:31 DependencyGraph classes should be non-sealed abstract classes or interfaces.
        e: graphs.kt:16:35 DependencyGraph classes should be non-sealed abstract classes or interfaces.
        e: graphs.kt:17:18 DependencyGraph must be public or internal.
        e: graphs.kt:18:57 DependencyGraph classes' primary constructor must be public or internal.
      """.trimIndent()
    )
  }

  @Test
  fun `multibinds must be abstract - property`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @Multibinds val values: Map<String, String> get() = emptyMap()
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:8:19 @Multibinds members must be abstract.")
  }

  @Test
  fun `multibinds must be abstract - function`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @Multibinds fun values(): Map<String, String> = emptyMap()
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:8:19 @Multibinds members must be abstract.")
  }

  @Test
  fun `multibinds cannot be scoped`() {
    val result =
      compile(
        source(
          """
            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              @Multibinds @Singleton fun values(): Map<String, String>
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:9:15 @Multibinds members cannot be scoped.")
  }

  @Test
  fun `accessors cannot be scoped - property`() {
    val result =
      compile(
        source(
          """
            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              @Singleton
              val value: String
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:9:3 Graph accessor members cannot be scoped.")
  }

  @Test
  fun `accessors cannot be scoped - property - getter site`() {
    val result =
      compile(
        source(
          """
            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              @get:Singleton
              val value: String
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:9:3 Graph accessor members cannot be scoped.")
  }

  @Test
  fun `accessors cannot be Unit - property`() {
    val result =
      compile(
        source(
          """
            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              val value: Unit
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:9:14 Graph accessor members must have a return type and cannot be Unit.
      """.trimIndent()
    )
  }

  @Suppress("RedundantUnitReturnType")
  @Test
  fun `accessors cannot be Unit - function - explicit`() {
    val result =
      compile(
        source(
          """
            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              fun value(): Unit
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:9:16 Graph accessor members must have a return type and cannot be Unit.")
  }

  @Test
  fun `accessors cannot be Unit - function - implicit`() {
    val result =
      compile(
        source(
          """
            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              fun value()
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:9:7 Graph accessor members must have a return type and cannot be Unit.
      """.trimIndent()
    )
  }

  @Test
  fun `accessors cannot be Nothing - property`() {
    val result =
      compile(
        source(
          """
            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              val value: Nothing
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:9:7 Graph accessor members cannot return Nothing.")
  }

  @Test
  fun `accessors cannot be Nothing - function`() {
    val result =
      compile(
        source(
          """
            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              fun value(): Nothing
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:9:7 Graph accessor members cannot return Nothing.")
  }

  @Test
  fun `injectors can only have one parameter`() {
    val result =
      compile(
        source(
          """
            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              fun inject(target: Int, target2: Int)
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:9:7 Inject functions must have exactly one parameter.")
  }

  @Test
  fun `injectors cannot have return types`() {
    val result =
      compile(
        source(
          """
            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              fun inject(target: Int): Int
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:9:28 Inject functions must not return anything other than Unit.")
  }

  @Test
  fun `injector targets must not be constructor injected - class annotation`() {
    val result =
      compile(
        source(
          """
            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              fun inject(target: ExampleClass)
            }

            @Inject class ExampleClass
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:9:14 Injected type is constructor-injected and can be instantiated by Lattice directly, so this inject function is unnecessary.
      """.trimIndent()
    )
  }

  @Test
  fun `injector targets must not be constructor injected - constructor annotation`() {
    val result =
      compile(
        source(
          """
            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              fun inject(target: ExampleClass)
            }

            class ExampleClass @Inject constructor()
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:9:14 Injected type is constructor-injected and can be instantiated by Lattice directly, so this inject function is unnecessary.
      """.trimIndent()
    )
  }

  @Test
  fun `injector targets must not be constructor injected - secondary constructor annotation`() {
    val result =
      compile(
        source(
          """
            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              fun inject(target: ExampleClass)
            }

            class ExampleClass(value: Int) {
              @Inject constructor() : this(0)
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:9:14 Injected type is constructor-injected and can be instantiated by Lattice directly, so this inject function is unnecessary.
      """.trimIndent()
    )
  }
}
