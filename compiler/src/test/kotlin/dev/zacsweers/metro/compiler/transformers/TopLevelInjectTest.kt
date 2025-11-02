// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.captureStandardOut
import dev.zacsweers.metro.compiler.createGraphViaFactory
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.generatedImpl
import dev.zacsweers.metro.compiler.getInstanceMethod
import dev.zacsweers.metro.compiler.invokeInstanceMethod
import dev.zacsweers.metro.compiler.invokeSuspendInstanceFunction
import kotlin.reflect.KClass
import kotlin.reflect.full.contextParameters
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.kotlinFunction
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalContextParameters::class)
class TopLevelInjectTest : MetroCompilerTest() {

  override val metroOptions: MetroOptions
    get() = MetroOptions(enableTopLevelFunctionInjection = true)

  @Test
  fun simple() {
    val result =
      compile(
        source(
          """
          @Inject
          fun App() {
            println("Hello, world!")
          }

          @DependencyGraph
          interface ExampleGraph {
            val app: AppClass
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val app = graph.callProperty<Any>("app")
    val output = captureStandardOut { app.invokeInstanceMethod<Any>("invoke") }
    assertThat(output).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple assisted`() {
    val result =
      compile(
        source(
          """
          @Inject
          fun App(@Assisted message: String) {
            println(message)
          }

          @DependencyGraph
          interface ExampleGraph {
            val app: AppClass
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val app = graph.callProperty<Any>("app")
    val output = captureStandardOut { app.invokeInstanceMethod<Any>("invoke", "Hello, world!") }
    assertThat(output).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple injected`() {
    val result =
      compile(
        source(
          """
          @Inject
          fun App(message: String) {
            println(message)
          }

          @DependencyGraph
          interface ExampleGraph {
            val app: AppClass

            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Provides message: String): ExampleGraph
            }
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphViaFactory("Hello, world!")

    val app = graph.callProperty<Any>("app")
    val output = captureStandardOut { app.invokeInstanceMethod<Any>("invoke") }
    assertThat(output).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple injected and assisted with return type`() {
    val result =
      compile(
        source(
          """
          @Inject
          fun App(@Assisted int: Int, message: String): String {
            return message + int
          }

          @DependencyGraph
          interface ExampleGraph {
            val app: AppClass

            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Provides message: String): ExampleGraph
            }
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphViaFactory("Hello, world!")

    val app = graph.callProperty<Any>("app")
    val returnString = app.invokeInstanceMethod<String>("invoke", 2)
    assertThat(returnString).isEqualTo("Hello, world!2")
  }

  @Test
  fun `simple injected - always returns new instances`() {
    val result =
      compile(
        source(
          """
          @Inject
          fun App(int: Int): Int {
            return int
          }

          @DependencyGraph
          abstract class ExampleGraph {
            abstract val app: AppClass

            private var count: Int = 0

            @Provides private fun provideInt(): Int {
              return count++
            }
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val app = graph.callProperty<Any>("app")
    val invoker = { app.invokeInstanceMethod<Int>("invoke") }
    assertThat(invoker()).isEqualTo(0)
    assertThat(invoker()).isEqualTo(1)
    assertThat(invoker()).isEqualTo(2)
  }

  @Test
  fun `simple injected - provider - always returns new instances`() {
    val result =
      compile(
        source(
          """
          @Inject
          fun App(int: Provider<Int>): Int {
            return int()
          }

          @DependencyGraph
          abstract class ExampleGraph {
            abstract val app: AppClass

            private var count: Int = 0

            @Provides private fun provideInt(): Int {
              return count++
            }
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val app = graph.callProperty<Any>("app")
    val invoker = { app.invokeInstanceMethod<Int>("invoke") }
    assertThat(invoker()).isEqualTo(0)
    assertThat(invoker()).isEqualTo(1)
    assertThat(invoker()).isEqualTo(2)
  }

  @Test
  fun `simple injected - lazy`() {
    val result =
      compile(
        source(
          """
          @Inject
          fun App(int: Lazy<Int>): Int {
            // Call it multiple times to ensure it's lazy
            int.value
            int.value
            int.value
            return int.value
          }

          @DependencyGraph
          abstract class ExampleGraph {
            abstract val app: AppClass

            private var count: Int = 0

            @Provides private fun provideInt(): Int {
              return count++
            }
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    // Lazy is scoped to the function, so while it's lazy in the function it's not lazy to multiple
    // function calls
    // The sample snippet adds some inner lazy calls though to ensure it's lazy within the function
    val app = graph.callProperty<Any>("app")
    val invoker = { app.invokeInstanceMethod<Int>("invoke") }
    assertThat(invoker()).isEqualTo(0)
    assertThat(invoker()).isEqualTo(1)
    assertThat(invoker()).isEqualTo(2)
  }

  @Test
  fun `qualifiers are linked`() {
    val result =
      compile(
        source(
          """
          @Inject
          fun App(@Named("int") int: Int): Int {
            return int
          }

          @DependencyGraph
          interface ExampleGraph {
            val app: AppClass

            @Named("int") @Provides private fun provideInt(): Int {
              return 0
            }
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val app = graph.callProperty<Any>("app")
    val invoker = { app.invokeInstanceMethod<Int>("invoke") }
    assertThat(invoker()).isEqualTo(0)
  }

  @Test
  fun `qualifiers on function are propagated to the class`() {
    val result =
      compile(
        source(
          """
          @Named("app")
          @Inject
          fun App(int: Int): Int {
            return int
          }

          @DependencyGraph
          interface ExampleGraph {
            @Named("app") val app: AppClass

            @Provides private fun provideInt(): Int {
              return 0
            }
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val app = graph.callProperty<Any>("app")
    val invoker = { app.invokeInstanceMethod<Int>("invoke") }
    assertThat(invoker()).isEqualTo(0)
  }

  @Test
  fun `assisted parameters in different order`() {
    val result =
      compile(
        source(
          """
          @Inject
          fun App(int: Int, @Assisted message: String, long: Long): String {
            return message + int.toString() + long.toString()
          }

          @DependencyGraph
          interface ExampleGraph {
            val app: AppClass

            @Provides val provideInt: Int get() = 2
            @Provides val provideLong: Long get() = 3
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val app = graph.callProperty<Any>("app")
    val output = app.invokeInstanceMethod<String>("invoke", "Hello, world!")
    assertThat(output).isEqualTo("Hello, world!23")
  }

  @Test
  fun `composable annotations are copied`() {
    val result =
      compile(
        sourceFiles =
          arrayOf(
            COMPOSE_ANNOTATIONS,
            source(
              """
              import androidx.compose.runtime.Composable

              @Composable
              @Inject
              fun App() {

              }

              @DependencyGraph
              interface ExampleGraph {
                val app: AppClass
              }
              """
                .trimIndent()
            ),
          )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val app = graph.callProperty<Any>("app")
    val method = app.getInstanceMethod("invoke")
    assertThat(method.annotations.map { it.annotationClass.qualifiedName })
      .contains("androidx.compose.runtime.Composable")
  }

  @Test
  fun `context parameters`() {
    val result =
      compile(
        sourceFiles =
          arrayOf(
            COMPOSE_ANNOTATIONS,
            source(
              """
              import androidx.compose.runtime.Composable

              interface Modifier {
                companion object : Modifier
              }

              interface SharedTransitionScope
              interface Clock
              interface MyUiComponentClass

              @Inject
              @Composable
              context(
                _: MyUiComponentClass,
              )
              fun App(
                clock: Clock,
              ) {
                // ...
              }

              @DependencyGraph
              interface ExampleGraph {
                val app: AppClass

                @Provides fun provideClock(): Clock = object : Clock {}
                @Provides fun provideUiComponent(): MyUiComponentClass = object : MyUiComponentClass {}
              }
              """
                .trimIndent()
            ),
          ),
        compilationBlock = { this.kotlincArguments += "-Xcontext-parameters" },
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val app = graph.callProperty<Any>("app")
    val method = app.getInstanceMethod("invoke")
    assertThat(method.annotations.map { it.annotationClass.qualifiedName })
      .contains("androidx.compose.runtime.Composable")
    assertThat(method.parameterCount).isEqualTo(0)
  }

  @Test
  fun `context parameters - multiple unused`() {
    val result =
      compile(
        sourceFiles =
          arrayOf(
            COMPOSE_ANNOTATIONS,
            source(
              """
              import androidx.compose.runtime.Composable

              interface Modifier {
                companion object : Modifier
              }

              interface SharedTransitionScope
              interface Clock
              interface MyUiComponentClass

              @Inject
              @Composable
              context(
                _: SharedTransitionScope,
                _: MyUiComponentClass,
              )
              fun App(
                clock: Clock,
              ) {
                // ...
              }

              @DependencyGraph
              interface ExampleGraph {
                val app: AppClass

                @Provides fun provideClock(): Clock = object : Clock {}
                @Provides fun provideUiComponent(): MyUiComponentClass = object : MyUiComponentClass {}
                @Provides fun provideScope(): SharedTransitionScope = object : SharedTransitionScope {}
              }
              """
                .trimIndent()
            ),
          ),
        compilationBlock = { this.kotlincArguments += "-Xcontext-parameters" },
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val app = graph.callProperty<Any>("app")
    val method = app.getInstanceMethod("invoke")
    assertThat(method.annotations.map { it.annotationClass.qualifiedName })
      .contains("androidx.compose.runtime.Composable")
    assertThat(method.parameterCount).isEqualTo(0)
  }

  @Test
  fun `context parameters with some assisted`() {
    val result =
      compile(
        sourceFiles =
          arrayOf(
            COMPOSE_ANNOTATIONS,
            source(
              """
              import androidx.compose.runtime.Composable

              interface Modifier {
                companion object : Modifier
              }

              interface SharedTransitionScope
              interface Clock
              interface MyUiComponentClass

              @Inject
              @Composable
              context(
                @Assisted sharedTransitionScope: SharedTransitionScope,
                _: MyUiComponentClass,
              )
              fun App(
                clock: Clock,
                @Assisted modifier: Modifier = Modifier,
              ) {
                // ...
              }

              @DependencyGraph
              interface ExampleGraph {
                val app: AppClass

                @Provides fun provideClock(): Clock = object : Clock {}
                @Provides fun provideUiComponent(): MyUiComponentClass = object : MyUiComponentClass {}
              }
              """
                .trimIndent()
            ),
          ),
        compilationBlock = { this.kotlincArguments += "-Xcontext-parameters" },
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val app = graph.callProperty<Any>("app")
    val method = app.getInstanceMethod("invoke")
    assertThat(method.annotations.map { it.annotationClass.qualifiedName })
      .contains("androidx.compose.runtime.Composable")
    // Assert assisted params
    assertThat(method.parameterCount).isEqualTo(2)
    assertThat(method.parameterTypes[0].canonicalName).isEqualTo("test.SharedTransitionScope")
    assertThat(method.parameterTypes[1].canonicalName).isEqualTo("test.Modifier")
    // Assert context params
    val kFunction = method.kotlinFunction!!
    assertThat(kFunction.contextParameters).hasSize(1)
    assertThat(kFunction.contextParameters[0].type.classifier!!.expectAs<KClass<*>>().qualifiedName)
      .isEqualTo("test.SharedTransitionScope")

    // Ensure we carry over parameter default
    assertThat(kFunction.valueParameters[0].isOptional).isTrue()
  }

  @Test
  fun `suspend keywords are propagated`() = runTest {
    val result =
      compile(
        source(
          """
          import kotlinx.coroutines.Deferred
          import kotlinx.coroutines.CompletableDeferred

          @Inject
          suspend fun App(deferred: Deferred<String>): String {
            return deferred.await()
          }

          @DependencyGraph
          interface ExampleGraph {
            val app: AppClass

            @Provides private fun provideDeferred(): Deferred<String> {
              return CompletableDeferred("Hello, world!")
            }
          }
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()
    val app = graph.callProperty<Any>("app")
    val output = app.invokeSuspendInstanceFunction<String>("invoke")
    assertThat(output).isEqualTo("Hello, world!")
  }
}
