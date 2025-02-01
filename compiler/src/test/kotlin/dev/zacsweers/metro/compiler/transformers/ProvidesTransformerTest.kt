// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.companionObjectClass
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedMetroGraphClass
import dev.zacsweers.metro.compiler.invokeCreateAs
import dev.zacsweers.metro.compiler.provideValueAs
import dev.zacsweers.metro.compiler.providesFactoryClass
import dev.zacsweers.metro.internal.Factory
import dev.zacsweers.metro.provider
import org.junit.Ignore
import org.junit.Test

class ProvidesTransformerTest : MetroCompilerTest() {

  @Test
  fun `simple function provider`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @Provides
              fun provideValue(): String = "Hello, world!"
            }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
    val providesFactoryClass = result.ExampleGraph.providesFactoryClass()
    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("provideValue", graph)
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>(graph)
    assertThat(providesFactory()).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple property provider`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @Provides
              val value: String get() = "Hello, world!"
            }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
    val providesFactoryClass = result.ExampleGraph.providesFactoryClass()
    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("getValue", graph)
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>(graph)
    assertThat(providesFactory()).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple function provider in a companion object`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              companion object {
                @Provides
                fun provideValue(): String = "Hello, world!"
              }
            }
          """
            .trimIndent()
        )
      )

    val providesFactoryClass = result.ExampleGraph.providesFactoryClass(companion = true)
    // These should be objects since they require no parameters
    // TODO these appear to need metadata annotations written correctly to work
    //    assertThat(providesFactoryClass.kotlin.objectInstance).isNotNull()
    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("provideValue")
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>()
    assertThat(providesFactory()).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple property provider in a companion object`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              companion object {
                @Provides
                val value: String get() = "Hello, world!"
              }
            }
          """
            .trimIndent()
        )
      )

    val providesFactoryClass = result.ExampleGraph.providesFactoryClass(companion = true)
    // These should be objects since they require no parameters
    // TODO these appear to need metadata annotations written correctly to work
    //    assertThat(providesFactoryClass.kotlin.objectInstance).isNotNull()
    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("getValue")
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>()
    assertThat(providesFactory()).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple function provider with arguments`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @Provides
              fun provideIntValue(): Int = 1

              @Provides
              fun provideStringValue(intValue: Int): String = "Hello, ${'$'}intValue!"
            }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
    val providesFactoryClass = result.ExampleGraph.providesFactoryClass("provideStringValue")

    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("provideStringValue", graph, 2)
    assertThat(providedValue).isEqualTo("Hello, 2!")

    // Exercise calling the create + invoke() functions
    val providesFactory =
      providesFactoryClass.invokeCreateAs<Factory<String>>(graph, provider { 2 })
    assertThat(providesFactory()).isEqualTo("Hello, 2!")
  }

  @Test
  fun `simple function provider in companion object with arguments`() {
    val result =
      compile(
        source(
          """
            interface ExampleGraph {
              companion object {
                @Provides
                fun provideStringValue(intValue: Int): String = "Hello, ${'$'}intValue!"
              }
            }
          """
            .trimIndent()
        )
      )

    val providesFactoryClass =
      result.ExampleGraph.companionObjectClass.providesFactoryClass("provideStringValue")

    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("provideStringValue", 2)
    assertThat(providedValue).isEqualTo("Hello, 2!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>(provider { 2 })
    assertThat(providesFactory()).isEqualTo("Hello, 2!")
  }

  @Test
  fun `simple function provider with multiple arguments`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @Provides
              fun provideBooleanValue(): Boolean = false

              @Provides
              fun provideIntValue(): Int = 1

              @Provides
              fun provideStringValue(intValue: Int, booleanValue: Boolean): String = "Hello, ${'$'}intValue! ${'$'}booleanValue"
            }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
    val providesFactoryClass = result.ExampleGraph.providesFactoryClass("provideStringValue")

    // Exercise calling the static provideValue function directly
    val providedValue =
      providesFactoryClass.provideValueAs<String>("provideStringValue", graph, 2, true)
    assertThat(providedValue).isEqualTo("Hello, 2! true")

    // Exercise calling the create + invoke() functions
    val providesFactory =
      providesFactoryClass.invokeCreateAs<Factory<String>>(graph, provider { 2 }, provider { true })
    assertThat(providesFactory()).isEqualTo("Hello, 2! true")
  }

  @Test
  fun `simple function provider with multiple arguments of the same type key`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @Provides
              fun provideIntValue(): Int = 1

              @Provides
              fun provideStringValue(
                intValue: Int,
                intValue2: Int
              ): String = "Hello, ${'$'}intValue - ${'$'}intValue2!"
            }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
    val providesFactoryClass = result.ExampleGraph.providesFactoryClass("provideStringValue")

    // Exercise calling the static provideValue function directly
    val providedValue =
      providesFactoryClass.provideValueAs<String>("provideStringValue", graph, 2, 3)
    assertThat(providedValue).isEqualTo("Hello, 2 - 3!")

    // Exercise calling the create + invoke() functions
    val providesFactory =
      providesFactoryClass.invokeCreateAs<Factory<String>>(graph, provider { 2 }, provider { 3 })
    assertThat(providesFactory()).isEqualTo("Hello, 2 - 3!")
  }

  @Test
  fun `simple function provider with multiple arguments of the same type with different qualifiers`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @Provides
              fun provideIntValue(): Int = 1

              @Named("int2")
              @Provides
              fun provideIntValue2(): Int = 1

              @Provides
              fun provideStringValue(
                intValue: Int,
                @Named("int2") intValue2: Int
              ): String = "Hello, ${'$'}intValue - ${'$'}intValue2!"
            }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
    val providesFactoryClass = result.ExampleGraph.providesFactoryClass("provideStringValue")

    // Exercise calling the static provideValue function directly
    val providedValue =
      providesFactoryClass.provideValueAs<String>("provideStringValue", graph, 2, 3)
    assertThat(providedValue).isEqualTo("Hello, 2 - 3!")

    // Exercise calling the create + invoke() functions
    val providesFactory =
      providesFactoryClass.invokeCreateAs<Factory<String>>(graph, provider { 2 }, provider { 3 })
    assertThat(providesFactory()).isEqualTo("Hello, 2 - 3!")
  }

  @Test
  fun `function with receivers are not currently supported`() {
    val result =
      compile(
        source(
          """
            @DependencyGraph
            interface ExampleGraph {
              @Provides
              private fun String.provideValue(): Int = length
            }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertDiagnostics(
      """
        e: ExampleGraph.kt:9:22 `@Provides` functions may not be extension functions. Use `@Binds` instead for these.
      """
        .trimIndent()
    )
  }

  @Test
  fun `a provider is visible from a supertype in another module`() {
    val otherModuleResult =
      compile(
        source(
          """
            interface Base {
              @Provides fun provideInt(): Int = 2
            }
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
          @DependencyGraph
          interface ExampleGraph : Base {
            val int: Int
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = otherModuleResult,
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      assertThat(graph.callProperty<Int>("int")).isEqualTo(2)
    }
  }

  @Test
  fun `a qualified provider is visible from a supertype in another module`() {
    val otherModuleResult =
      compile(
        source(
          """
            interface Base {
              @Provides @Named("int") fun provideInt(): Int = 2
            }
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
          @DependencyGraph
          interface ExampleGraph : Base {
            @Named("int")
            val int: Int
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = otherModuleResult,
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      assertThat(graph.callProperty<Int>("int")).isEqualTo(2)
    }
  }

  @Test
  fun `a provider with a default is visible from a supertype in another module`() {
    val otherModuleResult =
      compile(
        source(
          """
            interface Base {
              @Provides fun provideString(value: Int = 2): String = value.toString()
            }
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
          @DependencyGraph
          interface ExampleGraph : Base {
            val string: String
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = otherModuleResult,
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      assertThat(graph.callProperty<String>("string")).isEqualTo("2")
    }
  }

  @Ignore("Won't work until we support propagating metadata info")
  @Test
  fun `a private provider is visible from a supertype in another module`() {
    val otherModuleResult =
      compile(
        source(
          """
            interface Base {
              @Provides private fun provideInt(): Int = 2
            }
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
          @DependencyGraph
          interface ExampleGraph : Base {
            val int: Int
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = otherModuleResult,
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      assertThat(graph.callProperty<Int>("int")).isEqualTo(2)
    }
  }

  // TODO
  //  companion object with value params (missing receiver)
}
