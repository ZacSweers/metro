// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.allSupertypes
import dev.zacsweers.metro.compiler.assertDiagnostics
import kotlin.test.fail
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
      e: graphs.kt:11:24 DependencyGraph declarations should be non-sealed abstract classes or interfaces.
      e: graphs.kt:12:29 DependencyGraph declarations should be non-sealed abstract classes or interfaces.
      e: graphs.kt:13:35 DependencyGraph declarations should be non-sealed abstract classes or interfaces.
      e: graphs.kt:14:29 DependencyGraph declarations should be non-sealed abstract classes or interfaces.
      e: graphs.kt:15:31 DependencyGraph declarations should be non-sealed abstract classes or interfaces.
      e: graphs.kt:16:35 DependencyGraph declarations should be non-sealed abstract classes or interfaces.
      e: graphs.kt:17:18 DependencyGraph declarations must be public or internal.
      e: graphs.kt:18:57 DependencyGraph declarations' primary constructor must be public or internal.
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
      "e: ExampleGraph.kt:8:14 Injected class 'test.ExampleClass' is constructor-injected and can be instantiated by Metro directly, so this inject function is unnecessary."
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
      "e: ExampleGraph.kt:8:14 Injected class 'test.ExampleClass' is constructor-injected and can be instantiated by Metro directly, so this inject function is unnecessary."
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
      "e: ExampleGraph.kt:8:14 Injected class 'test.ExampleClass' is constructor-injected and can be instantiated by Metro directly, so this inject function is unnecessary."
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
      "e: ExampleGraph.kt:9:17 @DependencyGraph.Factory abstract function 'create' must return a dependency graph but found kotlin.Unit."
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
      "e: ExampleGraph.kt:10:19 @DependencyGraph.Factory abstract function 'create' must return a dependency graph but found kotlin.Nothing."
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
  fun `all factory parameters must be annotated with Provides XOR Includes`() {
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
    result.assertDiagnostics(
      "e: ExampleGraph.kt:10:41 DependencyGraph.Factory abstract function parameters must be annotated with exactly one @Includes or @Provides."
    )
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
      e: ExampleGraph.kt:14:17 @Includes cannot be applied to enums, annotations, or platform types.
      e: ExampleGraph.kt:15:17 @Includes cannot be applied to enums, annotations, or platform types.
      """
        .trimIndent()
    )
  }

  @Test
  fun `target graph type cannot be a factory parameter`() {
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph {
            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Provides graph: ExampleGraph): ExampleGraph
            }
          }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics(
      "e: ExampleGraph.kt:10:33 DependencyGraph.Factory declarations cannot have their target graph type as parameters."
    )
  }

  @Test
  fun `a primary scope should be defined before additionalScopes`() {
    compile(
      source(
        """
        @DependencyGraph(additionalScopes = [Unit::class])
        interface ExampleGraph
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleGraph.kt:6:37 @DependencyGraph should have a primary `scope` defined if `additionalScopes` are defined."
      )
    }
  }

  @Test
  fun `public graphs cannot merge internal contributions - simple`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph

        @ContributesTo(Unit::class)
        internal interface ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleGraph.kt:7:11 DependencyGraph declarations may not extend declarations with narrower visibility. Contributed supertype 'test.ContributedInterface' is internal but graph declaration 'test.ExampleGraph' is public."
      )
    }
  }

  @Test
  fun `excluded incompatible internal contributions work`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class, excludes = [ContributedInterface::class])
        interface ExampleGraph

        @ContributesTo(Unit::class)
        internal interface ContributedInterface
        """
          .trimIndent()
      )
    ) {
      assertThat(ExampleGraph.allSupertypes().map { it.simpleName })
        .doesNotContain("ContributedInterface")
    }
  }

  @Test
  fun `internal graphs cannot merge file-private contributions`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        internal interface ExampleGraph

        @ContributesTo(Unit::class)
        private interface ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleGraph.kt:7:20 DependencyGraph declarations may not extend declarations with narrower visibility. Contributed supertype 'test.ContributedInterface' is private but graph declaration 'test.ExampleGraph' is internal."
      )
    }
  }

  @Test
  fun `effectively-internal graphs cannot merge file-private contributions`() {
    compile(
      source(
        """
        internal class Parent {
          @DependencyGraph(Unit::class)
          interface ExampleGraph
        }

        @ContributesTo(Unit::class)
        private interface ContributedInterface
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: Parent.kt:8:13 DependencyGraph declarations may not extend declarations with narrower visibility. Contributed supertype 'test.ContributedInterface' is private but graph declaration 'test.Parent.ExampleGraph' is effectively internal."
      )
    }
  }

  @Test
  fun `public graphs cannot merge effectively-private contributions`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph

        private class Parent {
          @ContributesTo(Unit::class)
          interface ContributedInterface
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleGraph.kt:7:11 DependencyGraph declarations may not extend declarations with narrower visibility. Contributed supertype 'test.Parent.ContributedInterface' is effectively private but graph declaration 'test.ExampleGraph' is public."
      )
    }
  }

  @Test
  fun `contributed graph factories may not be narrower visibility`() {
    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph

        @GraphExtension(Unit::class)
        internal interface ContributedInterface {
          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun create(): ContributedInterface
          }
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleGraph.kt:7:11 DependencyGraph declarations may not extend declarations with narrower visibility. Contributed supertype 'test.ContributedInterface.Factory' is effectively internal but graph declaration 'test.ExampleGraph' is public."
      )
    }
  }

  @Test
  fun `non-public contributions do not have hints and are not merged`() {
    val firstResult =
      compile(
        source(
          """
          @ContributesTo(AppScope::class)
          internal interface ContributedInterface
          """
            .trimIndent()
        )
      ) {
        // Assert no hint is generated
        // metro/hints/MetroHintsContributedInterfaceAppScopeKt.class
        try {
          classLoader.loadClass("metro.hints.MetroHintsContributedInterfaceAppScopeKt")
          fail()
        } catch (_: ClassNotFoundException) {}
      }

    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      previousCompilationResult = firstResult,
    ) {
      assertThat(ExampleGraph.allSupertypes().map { it.simpleName })
        .doesNotContain("ContributedInterface")
    }
  }
}
