// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.captureStandardOut
import dev.zacsweers.metro.compiler.companionObjectClass
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedImpl
import dev.zacsweers.metro.compiler.invokeCreateAs
import dev.zacsweers.metro.compiler.provideValueAs
import dev.zacsweers.metro.compiler.providesFactoryClass
import dev.zacsweers.metro.internal.Factory
import dev.zacsweers.metro.provider
import kotlinx.coroutines.test.runTest
import org.jetbrains.kotlin.name.ClassId
import org.junit.Test

class BindingContainerTransformerTest : MetroCompilerTest() {

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

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()
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

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()
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
          $$"""
          @DependencyGraph
          interface ExampleGraph {
            @Provides
            fun provideIntValue(): Int = 1

            @Provides
            fun provideStringValue(intValue: Int): String = "Hello, $intValue!"
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()
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
          $$"""
          interface ExampleGraph {
            companion object {
              @Provides
              fun provideStringValue(intValue: Int): String = "Hello, $intValue!"
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
          $$"""
          @DependencyGraph
          interface ExampleGraph {
            @Provides
            fun provideBooleanValue(): Boolean = false

            @Provides
            fun provideIntValue(): Int = 1

            @Provides
            fun provideStringValue(intValue: Int, booleanValue: Boolean): String = "Hello, $intValue! $booleanValue"
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()
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
          $$"""
          @DependencyGraph
          interface ExampleGraph {
            @Provides
            fun provideIntValue(): Int = 1

            @Provides
            fun provideStringValue(
              intValue: Int,
              intValue2: Int
            ): String = "Hello, $intValue - $intValue2!"
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()
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
          $$"""
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
            ): String = "Hello, $intValue - $intValue2!"
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()
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
      e: ExampleGraph.kt:9:22 `@Provides` functions may not be extension functions. Use `@Binds` instead for these. See https://zacsweers.github.io/metro/latest/bindings/#binds for more information.
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
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
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
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(graph.callProperty<String>("string")).isEqualTo("2")
    }
  }

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
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(graph.callProperty<Int>("int")).isEqualTo(2)
    }
  }

  @Test
  fun `function types are supported`() = runTest {
    compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph {
            val unitFunction: () -> Unit
            val intFunction: () -> Int
            val intIntFunction: (Int) -> Int
            val floatReceiverFloatFunction: Float.() -> Float
            val suspendBooleanFunction: suspend () -> Boolean

            @Provides fun provideUnitFunction(): () -> Unit = { println("Hello, world!") }
            @Provides fun provideIntFunction(): () -> Int = { 2 }
            @Provides fun provideIntIntFunction(): (Int) -> Int = { 2 * it }
            @Provides fun provideFloatReceiverFloatFunction(): Float.() -> Float = { 2 * this }
            @Provides fun provideSuspendBooleanFunction(): suspend () -> Boolean = { true }
          }
          """
            .trimIndent()
        )
      )
      .apply {
        val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
        val out = captureStandardOut { graph.callProperty<() -> Unit>("unitFunction").invoke() }
        assertThat(out).isEqualTo("Hello, world!")
        assertThat(graph.callProperty<() -> Int>("intFunction").invoke()).isEqualTo(2)
        assertThat(graph.callProperty<(Int) -> Int>("intIntFunction").invoke(2)).isEqualTo(4)
        assertThat(graph.callProperty<Float.() -> Float>("floatReceiverFloatFunction").invoke(2f))
          .isEqualTo(4f)
        assertThat(graph.callProperty<suspend () -> Boolean>("suspendBooleanFunction").invoke())
          .isEqualTo(true)
      }
  }

  @Test
  fun `private qualifiers are propagated`() {
    val firstCompilation =
      compile(
        source(
          """
          interface EnabledProvider {
            @Qualifier @Retention(BINARY) private annotation class FlipperEnabled

            @FlipperEnabled
            @Provides
            private fun provideEnabled(): Boolean = true

            @Provides
            private fun provideEnabledValue(@FlipperEnabled enabled: Boolean): String = enabled.toString()
          }
          """
            .trimIndent(),
          extraImports =
            arrayOf("kotlin.annotation.AnnotationRetention.BINARY", "javax.inject.Qualifier"),
        )
      )

    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph : EnabledProvider {
          val value: String
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstCompilation,
    )
  }

  @Test
  fun `nullable types do not conflict with non-nullable types`() {
    compile(
      source(
        """
        interface Base {
          @Provides fun provideInt(): Int = 2
          @Provides fun provideNullNullableInt(): Int? = null
          @Provides fun provideString(): String = "Hello"
          @Provides fun provideNotNullNullableString(): String? = "NullableHello"
        }
        @DependencyGraph
        interface ExampleGraph : Base {
          val int: Int
          val nullableInt: Int?
          val string: String
          val nullableString: String?
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(graph.callProperty<Int>("int")).isEqualTo(2)
      assertThat(graph.callProperty<Int?>("nullableInt")).isEqualTo(null)
      assertThat(graph.callProperty<String>("string")).isEqualTo("Hello")
      assertThat(graph.callProperty<String?>("nullableString")).isEqualTo("NullableHello")
    }
  }

  // TODO move to compiler-tests when reporting sourceless diagnostics work
  @Test
  fun `dagger interop module with subcomponents will warn`() {
    val firstCompilation =
      compile(
        SourceFile.java(
          "SomeSubcomponent.java",
          """
          import dagger.Subcomponent;

          @Subcomponent
          public interface SomeSubcomponent {
            @Subcomponent.Factory
            interface Factory {
              SomeSubcomponent create();
            }
          }
          """
            .trimIndent(),
        ),
        SourceFile.java(
          "ExampleModule.java",
          """
          import dagger.Provides;
          import dagger.Module;

          @Module(subcomponents = SomeSubcomponent.class)
          public class ExampleModule {
            public ExampleModule() {

            }
          }
          """
            .trimIndent(),
        ),
      )

    compile(
      source(
        """
        @DependencyGraph(bindingContainers = [ExampleModule::class])
        interface ExampleGraph
        """
          .trimIndent()
      ),
      previousCompilationResult = firstCompilation,
      options = metroOptions.withDaggerInterop(),
    ) {
      ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertDiagnostics(
        "w: Included Dagger module 'ExampleModule' declares a `subcomponents` parameter but this will be ignored by Metro in interop."
      )
    }
  }

  private fun MetroOptions.withDaggerInterop(): MetroOptions {
    return copy(
      enableDaggerRuntimeInterop = true,
      customBindingContainerAnnotations = setOf(ClassId.fromString("dagger/Module")),
    )
  }

  // TODO
  //  companion object with value params (missing receiver)
}
