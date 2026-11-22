// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callFunction
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.companionObjectInstance
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedImpl
import dev.zacsweers.metro.compiler.invokeMain
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.internal.MapFactory
import dev.zacsweers.metro.internal.MapProviderFactory
import kotlin.test.Ignore
import kotlin.test.assertNotNull
import org.junit.Test

class DependencyGraphTransformerTest : MetroCompilerTest() {

  @Test
  fun `a new compilation clears only compiler reports`() {
    val destination = temporaryFolder.newFolder()
    val options = metroOptions.toBuilder().reportsDestination(destination.toPath()).build()
    val notes = destination.resolve("notes.txt").apply { writeText("keep") }

    compile(source("@DependencyGraph interface OldGraph"), options = options)
    val metadata = destination.resolve("graph-metadata")
    val oldGraph = metadata.resolve("graph-test-OldGraph.json")
    assertThat(oldGraph.isFile).isTrue()

    val unrelated = metadata.resolve("other.json").apply { writeText("{}") }
    compile(source("@DependencyGraph interface NewGraph"), options = options)
    assertThat(oldGraph.exists()).isFalse()
    assertThat(metadata.resolve("graph-test-NewGraph.json").isFile).isTrue()
    assertThat(unrelated.readText()).isEqualTo("{}")
    assertThat(notes.readText()).isEqualTo("keep")
  }

  // Won't work until we no longer look for the factory SAM function in interfaces
  // during nested callable name generation
  @Ignore
  @Test
  fun `graph factory function is generated onto existing companion objects`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val int: Int

          @DependencyGraph.Factory
          fun interface Factory {
            operator fun invoke(@Provides int: Int): ExampleGraph
          }

          companion object
        }
        """
          .trimIndent(),
      ),
    ) {
      val instance = ExampleGraph.companionObjectInstance.callFunction<Any>("invoke", 3)
      assertThat(instance).isNotNull()
      assertThat(instance.callProperty<Int>("int")).isEqualTo(3)
    }
  }

  @Test
  fun `graph impls are visible from other modules`() {
    val firstResult =
      compile(
        source(
          """
          @DependencyGraph
          interface IntGraph {
            val int: Int

            @DependencyGraph.Factory
            fun interface Factory {
              operator fun invoke(@Provides int: Int): IntGraph
            }
          }
          """
            .trimIndent(),
        ),
      )

    compile(
      source(
        """
        fun main(int: Int) = IntGraph(int)
        """
          .trimIndent(),
      ),
      metroEnabled = false,
      previousCompilationResult = firstResult,
    ) {
      val graph = invokeMain<Any>(3)
      assertThat(graph).isNotNull()
      assertThat(graph.callProperty<Int>("int")).isEqualTo(3)
    }
  }

  // Won't work until we no longer look for the factory SAM function in interfaces
  // during nested callable name generation
  @Ignore
  @Test
  fun `graph impls are usable from graphs in other modules`() {
    val firstResult =
      compile(
        source(
          """
          @DependencyGraph
          interface IntGraph {
            val int: Int

            @DependencyGraph.Factory
            fun interface Factory {
              operator fun invoke(@Provides int: Int): IntGraph
            }
          }
          """
            .trimIndent(),
        ),
      )

    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val int: Int

          @DependencyGraph.Factory
          fun interface Factory {
            operator fun invoke(upstream: IntGraph): ExampleGraph
          }

          companion object {
            fun createDefault(int: Int): ExampleGraph = ExampleGraph(IntGraph(int))
          }
        }
        """
          .trimIndent(),
      ),
      previousCompilationResult = firstResult,
    ) {
      val graph = ExampleGraph.companionObjectInstance.callFunction<Any>("createDefault", 3)
      assertThat(graph).isNotNull()
      assertThat(graph.callProperty<Int>("int")).isEqualTo(3)
    }
  }

  /**
   * This tests that an implicit multibinding with an explicit one do not conflict as duplicate
   * bindings
   */
  /**
   * We used to track binds providers in a map, which would fail on cases where the same callable ID
   * was used. This ensures we support that case.
   */
  @Test
  fun `the fully qualified contributing class names are reported when there are duplicated bindings but both are missing location info`() {
    val otherResult =
      compile(
        source(
          """
          interface OtherClass

          @ContributesBinding(AppScope::class)
          @Inject
          class ExampleClass : OtherClass

          @ContributesBinding(AppScope::class)
          @Inject
          class ExampleClass2 : OtherClass
          """
            .trimIndent(),
          packageName = "other",
        ),
      )

    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph {
          // Accessor to use the binding - duplicate errors are only reported for used bindings
          val otherClass: OtherClass
        }
        """
          .trimIndent(),
        extraImports = arrayOf("other.OtherClass"),
      ),
      previousCompilationResult = otherResult,
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:11
        [Metro/DuplicateBinding] Multiple bindings found for OtherClass

              No source location available - declared at other.ExampleClass
                other.ExampleClass contributes a binding of OtherClass
                ~~~~~~~~~~~~~~~~~~                          ~~~~~~~~~~

              No source location available - declared at other.ExampleClass2
                other.ExampleClass2 contributes a binding of OtherClass
                ~~~~~~~~~~~~~~~~~~~                          ~~~~~~~~~~

          help: remove or disambiguate the duplicate bindings (e.g. with distinct qualifiers), or use
                @IntoSet/@IntoMap if you intended a multibinding
          docs: https://zacsweers.github.io/metro/latest/diagnostics/#duplicatebinding
        """
          .trimIndent(),
      )
    }
  }

  @Test
  fun `the fully qualified contributing class name is reported when there are duplicated bindings but one is missing location info`() {
    val otherResult =
      compile(
        source(
          """
          interface OtherClass

          @ContributesBinding(AppScope::class)
          @Inject
          class ExampleClass : OtherClass
          """
            .trimIndent(),
          packageName = "other",
        ),
      )

    compile(
      source(
        """
        @ContributesBinding(AppScope::class)
        @Inject
        class ExampleClass2 : OtherClass
        """
          .trimIndent(),
        extraImports = arrayOf("other.OtherClass"),
      ),
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph {
          // Accessor to use the binding - duplicate errors are only reported for used bindings
          val otherClass: OtherClass
        }
        """
          .trimIndent(),
        extraImports = arrayOf("other.OtherClass"),
      ),
      previousCompilationResult = otherResult,
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:11 [Metro/DuplicateBinding] Multiple bindings found for OtherClass

              No source location available - declared at other.ExampleClass
                other.ExampleClass contributes a binding of OtherClass
                ~~~~~~~~~~~~~~~~~~                          ~~~~~~~~~~

              ExampleClass2.kt:7:1
                test.ExampleClass2 contributes a binding of OtherClass
                ~~~~~~~~~~~~~~~~~~                          ~~~~~~~~~~

          help: remove or disambiguate the duplicate bindings (e.g. with distinct qualifiers), or use
                @IntoSet/@IntoMap if you intended a multibinding
          docs: https://zacsweers.github.io/metro/latest/diagnostics/#duplicatebinding
        """
          .trimIndent(),
      )
    }
  }

  // Regression test for https://github.com/ZacSweers/metro/issues/250
  @Test
  fun `instantiating graphs is possible from separate compilations`() {
    val firstCompilation =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph
          """
            .trimIndent(),
        ),
      )

    compile(
      source(
        """
        fun main() = createGraph<ExampleGraph>()
        """
          .trimIndent(),
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val graph = invokeMain<Any>()
      assertNotNull(graph)
      assertThat(graph.javaClass.simpleName).isEqualTo(Symbols.StringNames.IMPL)
    }
  }

  // Regression test for https://github.com/ZacSweers/metro/issues/250
  @Test
  fun `instantiating graphs is possible from separate compilations - custom factory`() {
    val firstCompilation =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph {
            @DependencyGraph.Factory
            fun interface Factory {
              fun createGraph(): ExampleGraph
            }
          }
          """
            .trimIndent(),
        ),
      )

    compile(
      source(
        """
        fun main() = createGraphFactory<ExampleGraph.Factory>().createGraph()
        """
          .trimIndent(),
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val graph = invokeMain<Any>()
      assertNotNull(graph)
      assertThat(graph.javaClass.simpleName).isEqualTo(Symbols.StringNames.IMPL)
    }
  }

  @Test
  fun `multibindings - maps - empty uses empty singleton`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          @Multibinds(allowEmpty = true)
          val ints: Map<Int, Int>

          val intsProvider: Map<Int, () -> Int>

          val providerOfInts: () -> Map<Int, Int>
        }
        """
          .trimIndent(),
      ),
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val intsProvider = graph.callProperty<Map<Int, () -> Int>>("intsProvider")
      // Use toString() because on JVM this may be inlined
      assertThat(intsProvider.toString()).isEqualTo(MapProviderFactory.empty<Int, Int>().toString())
      val ints = graph.callProperty<Map<Int, Int>>("ints")
      assertThat(ints.toString()).isEqualTo(MapFactory.empty<Int, Int>().toString())
      val providerOfInts = graph.callProperty<() -> Map<Int, Int>>("providerOfInts")
      assertThat(providerOfInts.toString()).isEqualTo(MapFactory.empty<Int, Int>().toString())
    }
  }

  @Test
  fun `injectors cannot have return types`() {
    compile(
      source(
        """
        class Thing {
          @Inject lateinit var string: String
        }

        interface Parent {
          fun injectThing(thing: Thing): String
        }

        @DependencyGraph interface AppGraph : Parent
        """
          .trimIndent(),
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: Thing.kt:14:28 Injector function test.AppGraph.Impl.injectThing must return Unit. Or, if it's not an injector, remove its parameter.
        """
          .trimIndent(),
      )
    }
  }
}
