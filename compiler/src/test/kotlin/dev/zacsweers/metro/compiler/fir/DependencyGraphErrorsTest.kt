// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import org.junit.Ignore
import org.junit.Test

class DependencyGraphErrorsTest : MetroCompilerTest() {

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
      """
        .trimIndent()
    )
  }

  @Test
  fun `accessors cannot be scoped - property`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph(AppScope::class)
            interface ExampleGraph {
              @SingleIn(AppScope::class)
              val value: String
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:8:3 Graph accessor members cannot be scoped.")
  }

  @Test
  fun `accessors cannot be scoped - property - getter site`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph(AppScope::class)
            interface ExampleGraph {
              @get:SingleIn(AppScope::class)
              val value: String
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:8:3 Graph accessor members cannot be scoped.")
  }

  @Test
  fun `accessors cannot be Unit - property`() {
    val result =
      compile(
        source(
          """
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
      "e: ExampleGraph.kt:8:14 Graph accessor members must have a return type and cannot be Unit."
    )
  }

  @Suppress("RedundantUnitReturnType")
  @Test
  fun `accessors cannot be Unit - function - explicit`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              fun value(): Unit
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      "e: ExampleGraph.kt:8:16 Graph accessor members must have a return type and cannot be Unit."
    )
  }

  @Test
  fun `accessors cannot be Unit - function - implicit`() {
    val result =
      compile(
        source(
          """
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
      "e: ExampleGraph.kt:8:7 Graph accessor members must have a return type and cannot be Unit."
    )
  }

  @Test
  fun `accessors cannot be Nothing - property`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              val value: Nothing
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:8:7 Graph accessor members cannot return Nothing.")
  }

  @Test
  fun `accessors cannot be Nothing - function`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              fun value(): Nothing
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:8:7 Graph accessor members cannot return Nothing.")
  }

  @Test
  fun `injectors can only have one parameter`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              fun inject(target: Int, target2: Int)
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      "e: ExampleGraph.kt:8:7 Inject functions must have exactly one parameter."
    )
  }

  @Test
  fun `injectors cannot have return types`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              fun inject(target: Int): Int
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      "e: ExampleGraph.kt:8:28 Inject functions must not return anything other than Unit."
    )
  }

  @Test
  fun `injector targets must not be constructor injected - class annotation`() {
    val result =
      compile(
        source(
          """
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
      "e: ExampleGraph.kt:8:14 Injected type is constructor-injected and can be instantiated by Metro directly, so this inject function is unnecessary."
    )
  }

  @Test
  fun `injector targets must not be constructor injected - constructor annotation`() {
    val result =
      compile(
        source(
          """
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
      "e: ExampleGraph.kt:8:14 Injected type is constructor-injected and can be instantiated by Metro directly, so this inject function is unnecessary."
    )
  }

  @Test
  fun `injector targets must not be constructor injected - secondary constructor annotation`() {
    val result =
      compile(
        source(
          """
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
      "e: ExampleGraph.kt:8:14 Injected type is constructor-injected and can be instantiated by Metro directly, so this inject function is unnecessary."
    )
  }

  @Test
  fun `graph creators must return graphs - no return type`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @DependencyGraph.Factory
              fun interface Factory {
                fun create()
              }
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      "e: ExampleGraph.kt:9:17 DependencyGraph.Factory abstract function 'create' must return a dependency graph but found kotlin.Unit."
    )
  }

  @Test
  fun `graph creators must return graphs - invalid return type`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @DependencyGraph.Factory
              fun interface Factory {
                fun create(): Nothing
              }
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      "e: ExampleGraph.kt:10:19 DependencyGraph.Factory abstract function 'create' must return a dependency graph but found kotlin.Nothing."
    )
  }

  @Ignore("This isn't captured yet. May need to do it in the IR backend instead.")
  @Test
  fun `graph creators must return graphs - inherited invalid return type`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              interface BaseFactory<T> {
                fun create(): T
              }
              @DependencyGraph.Factory
              interface Factory : BaseFactory<Nothing>
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("sdaf")
  }
  
  @Test
  fun `all factory parameters must be annotated with Provides XOR Includes XOR Extends`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Provides value: String, value2: Int): ExampleGraph
              }
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("e: ExampleGraph.kt:10:41 DependencyGraph.Factory abstract function parameters must be annotated with exactly one @Includes, @Provides, or @Extends.")
  }

  @Test
  fun `Includes cannot be platform types enums or annotation classes`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              annotation class AnnotationClass
            
              @DependencyGraph.Factory
              fun interface Factory {
                fun create(
                  @Includes value: SomeEnum,
                  @Includes value2: Int,
                  @Includes value3: AnnotationClass,
                ): ExampleGraph
              }
            }

            enum class SomeEnum { VALUE1 }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:13:17 @Includes cannot be applied to enums, annotations, or platform types.
        e: ExampleGraph.kt:15:17 @Includes cannot be applied to enums, annotations, or platform types.
      """.trimIndent()
    )
  }

  @Test
  fun `Extends type must be a DependencyGraph-annotated type`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends value: String): ExampleGraph
              }
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      "e: ExampleGraph.kt:10:25 @Extends types must be annotated with @DependencyGraph."
    )
  }

  @Test
  fun `Extends type must be a DependencyGraph isExtendable`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends value: FinalClassGraph): ExampleGraph
              }
            }

            @DependencyGraph
            interface FinalClassGraph
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      "e: ExampleGraph.kt:10:25 @Extends graphs must be extendable (set DependencyGraph.isExtendable to true)."
    )
  }
}
