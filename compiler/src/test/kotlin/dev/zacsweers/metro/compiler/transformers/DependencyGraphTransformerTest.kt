// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callFunction
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.companionObjectInstance
import dev.zacsweers.metro.compiler.createGraphViaFactory
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedImpl
import dev.zacsweers.metro.compiler.invokeInstanceMethod
import dev.zacsweers.metro.compiler.invokeMain
import dev.zacsweers.metro.compiler.newInstanceStrict
import dev.zacsweers.metro.internal.MapFactory
import dev.zacsweers.metro.internal.MapProviderFactory
import java.util.concurrent.Callable
import kotlin.test.Ignore
import kotlin.test.assertNotNull
import org.junit.Test

class DependencyGraphTransformerTest : MetroCompilerTest() {

  @Test
  fun simple() {
    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph {

          fun exampleClass(): ExampleClass

          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides text: String): ExampleGraph
          }
        }

        @SingleIn(AppScope::class)
        @Inject
        class ExampleClass(private val text: String) : Callable<String> {
          override fun call(): String = text
        }

        fun createExampleClass(): (String) -> Callable<String> {
          val factory = createGraphFactory<ExampleGraph.Factory>()
          return { factory.create(it).exampleClass() }
        }

        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphViaFactory("Hello, world!")

      val exampleClass = graph.callFunction<Callable<String>>("exampleClass")
      assertThat(exampleClass.call()).isEqualTo("Hello, world!")

      // 2nd pass exercising creating a graph via createGraphFactory()
      @Suppress("UNCHECKED_CAST")
      val callableCreator =
        classLoader
          .loadClass("test.ExampleGraphKt")
          .getDeclaredMethod("createExampleClass")
          .invoke(null) as (String) -> Callable<String>
      val callable = callableCreator("Hello, world!")
      assertThat(callable.call()).isEqualTo("Hello, world!")
    }
  }

  @Test
  fun `missing binding should fail compilation and report property accessor`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {

          val text: String
        }

        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:9:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is requested at
                [test.ExampleGraph] test.ExampleGraph.text
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `missing binding should fail compilation and report property accessor with qualifier`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {

          @Named("hello")
          val text: String
        }

        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:10:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @dev.zacsweers.metro.Named("hello") kotlin.String

            @dev.zacsweers.metro.Named("hello") kotlin.String is requested at
                [test.ExampleGraph] test.ExampleGraph.text
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `missing binding should fail compilation and report property accessor with get site target qualifier`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {

          @get:Named("hello")
          val text: String
        }

        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:10:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @dev.zacsweers.metro.Named("hello") kotlin.String

            @dev.zacsweers.metro.Named("hello") kotlin.String is requested at
                [test.ExampleGraph] test.ExampleGraph.text
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `missing binding should fail compilation and function accessor`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {

          fun text(): String
        }

        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:9:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is requested at
                [test.ExampleGraph] test.ExampleGraph.text()
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `missing binding should fail compilation and function accessor with qualifier`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {

          @Named("hello")
          fun text(): String
        }

        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:10:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @dev.zacsweers.metro.Named("hello") kotlin.String

            @dev.zacsweers.metro.Named("hello") kotlin.String is requested at
                [test.ExampleGraph] test.ExampleGraph.text()
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `missing binding should fail compilation and report binding stack`() {
    compile(
      source(
        """
        @DependencyGraph
        abstract class ExampleGraph() {

          abstract fun exampleClass(): ExampleClass
        }

        @Inject
        class ExampleClass(private val text: String)

        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:13:28 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is injected at
                [test.ExampleGraph] test.ExampleClass(…, text)
            test.ExampleClass is requested at
                [test.ExampleGraph] test.ExampleGraph.exampleClass()
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `missing binding should fail compilation and report binding stack with qualifier`() {
    compile(
      source(
        """
        @DependencyGraph
        abstract class ExampleGraph() {

          abstract fun exampleClass(): ExampleClass
        }

        @Inject
        class ExampleClass(@Named("hello") private val text: String)

        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:13:44 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @dev.zacsweers.metro.Named("hello") kotlin.String

            @dev.zacsweers.metro.Named("hello") kotlin.String is injected at
                [test.ExampleGraph] test.ExampleClass(…, text)
            test.ExampleClass is requested at
                [test.ExampleGraph] test.ExampleGraph.exampleClass()
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `scoped bindings from providers are scoped correctly`() {
    // Ensure scoped bindings are properly scoped
    // This means that any calls to them should return the same instance, while any calls
    // to unscoped bindings are called every time.
    val result =
      compile(
        source(
          """
          @DependencyGraph(AppScope::class)
          abstract class ExampleGraph {

            private var scopedCounter = 0
            private var unscopedCounter = 0

            @Named("scoped")
            abstract val scoped: String

            @Named("unscoped")
            abstract val unscoped: String

            @SingleIn(AppScope::class)
            @Provides
            @Named("scoped")
            fun provideScoped(): String = "text " + scopedCounter++

            @Provides
            @Named("unscoped")
            fun provideUnscoped(): String = "text " + unscopedCounter++
          }

          @Inject
          class ExampleClass(@Named("hello") private val text: String)
          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    // Repeated calls to the scoped instance only every return one value
    assertThat(graph.callProperty<String>("scoped")).isEqualTo("text 0")
    assertThat(graph.callProperty<String>("scoped")).isEqualTo("text 0")

    // Repeated calls to the unscoped instance recompute each time
    assertThat(graph.callProperty<String>("unscoped")).isEqualTo("text 0")
    assertThat(graph.callProperty<String>("unscoped")).isEqualTo("text 1")
  }

  @Test
  fun `scoped graphs cannot depend on scoped bindings with mismatched scopes`() {
    // Ensure scoped bindings match the graph that is trying to use them
    val result =
      compile(
        source(
          """
          @Singleton
          @DependencyGraph(AppScope::class)
          interface ExampleGraph {

            val intValue: Int

            @SingleIn(UserScope::class)
            @Provides
            fun invalidScope(): Int = 0
          }

          abstract class UserScope private constructor()
          @Scope annotation class Singleton
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertDiagnostics(
      """
      e: ExampleGraph.kt:8:11 [Metro/IncompatiblyScopedBindings] test.ExampleGraph (scopes '@SingleIn(AppScope::class)', '@Singleton') may not reference bindings from different scopes:
          kotlin.Int (scoped to '@SingleIn(UserScope::class)')
          kotlin.Int is requested at
              [test.ExampleGraph] test.ExampleGraph.intValue
      """
        .trimIndent()
    )
  }

  @Test
  fun `providers from supertypes are wired correctly`() {
    // Ensure providers from supertypes are correctly wired. This means both incorporating them in
    // binding resolution and being able to invoke them correctly in the resulting graph.
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph : TextProvider {
            val value: String
          }

          interface TextProvider {
            @Provides
            fun provideValue(): String = "Hello, world!"
          }

          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()
    assertThat(graph.callProperty<String>("value")).isEqualTo("Hello, world!")
  }

  @Test
  fun `providers from supertype companion objects are visible`() {
    // Ensure providers from supertypes are correctly wired. This means both incorporating them in
    // binding resolution and being able to invoke them correctly in the resulting graph.
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph : TextProvider {

            val value: String
          }

          interface TextProvider {
            companion object {
              @Provides
              fun provideValue(): String = "Hello, world!"
            }
          }

          """
            .trimIndent()
        )
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()
    assertThat(graph.callProperty<String>("value")).isEqualTo("Hello, world!")
  }

  @Test
  fun `providers overridden from supertypes are errors`() {
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph : TextProvider {

            val value: String

            override fun provideValue(): String = "Hello, overridden world!"
          }

          interface TextProvider {
            @Provides
            fun provideValue(): String = "Hello, world!"
          }

          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
        options = metroOptions.copy(transformProvidersToPrivate = false),
      )

    result.assertDiagnostics(
      "e: ExampleGraph.kt:11:16 Do not override `@Provides` declarations. Consider using `@ContributesTo.replaces`, `@ContributesBinding.replaces`, and `@DependencyGraph.excludes` instead."
    )
  }

  @Test
  fun `overrides annotated with provides from non-provides supertypes are ok`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph : TextProvider {

          val value: String

          @Provides
          override fun provideValue(): String = "Hello, overridden world!"
        }

        interface TextProvider {
          fun provideValue(): String = "Hello, world!"
        }

        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `unscoped providers get reused if used multiple times`() {
    // One aspect of provider fields is we want to reuse them if they're used from multiple places
    // even if they're unscoped
    //
    // private val stringProvider: Provider<String> = StringProvider_Factory.create(...)
    // private val stringUserProvider = StringUserProviderFactory.create(stringProvider)
    // private val stringUserProvider2 = StringUserProvider2Factory.create(stringProvider)
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {

          val valueLengths: Int

          @Provides
          fun provideValue(): String = "Hello, world!"

          @Provides
          fun provideValueLengths(value: String, value2: String): Int = value.length + value2.length
        }

        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()

      // Assert we generated a shared field
      val provideValueField =
        graph.javaClass.getDeclaredField("provideValueProvider").apply { isAccessible = true }

      // Get its instance
      @Suppress("UNCHECKED_CAST")
      val provideValueProvider = provideValueField.get(graph) as Provider<String>

      // Get its computed value to plug in below
      val providerValue = provideValueProvider()
      assertThat(graph.javaClass.getDeclaredField("provideValueProvider"))
      assertThat(graph.callProperty<Int>("valueLengths")).isEqualTo(providerValue.length * 2)
    }
  }

  @Test
  fun `unscoped providers do not get reused if used only once`() {
    // One aspect of provider fields is we want to reuse them if they're used from multiple places
    // even if they're unscoped. If they're not though, then we don't do this
    //
    // private val stringUserProvider =
    // StringUserProviderFactory.create(StringProvider_Factory.create(...))
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {

          val valueLengths: Int

          @Provides
          fun provideValue(): String = "Hello, world!"

          @Provides
          fun provideValueLengths(value: String): Int = value.length
        }

        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()

      assertThat(graph.javaClass.declaredFields.singleOrNull { it.name == "provideValueProvider" })
        .isNull()

      assertThat(graph.callProperty<Int>("valueLengths")).isEqualTo("Hello, world!".length)
    }
  }

  @Test
  fun `unscoped graphs may not reference scoped types`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {

          val value: String

          @SingleIn(AppScope::class)
          @Provides
          fun provideValue(): String = "Hello, world!"
        }

        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:11 [Metro/IncompatiblyScopedBindings] test.ExampleGraph (unscoped) may not reference scoped bindings:
            kotlin.String (scoped to '@SingleIn(AppScope::class)')
            kotlin.String is requested at
                [test.ExampleGraph] test.ExampleGraph.value
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `binding failures should only be focused on the current context`() {
    // small regression test to ensure that we pop the BindingStack correctly
    // while iterating exposed types and don't leave old refs
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {

          val value: String
          val value2: CharSequence

          @Provides
          fun provideValue(): String = "Hello, world!"
        }

        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:10:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.CharSequence

            kotlin.CharSequence is requested at
                [test.ExampleGraph] test.ExampleGraph.value2

        Similar bindings:
          - String (Subtype). Type: Provided. Source: ExampleGraph.kt:12:3
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `simple binds example`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {

          val value: String
          val value2: CharSequence

          @Provides
          fun bind(value: String): CharSequence = value

          @Provides
          fun provideValue(): String = "Hello, world!"
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(graph.callProperty<String>("value")).isEqualTo("Hello, world!")
      assertThat(graph.callProperty<CharSequence>("value2")).isEqualTo("Hello, world!")
    }
  }

  @Test
  fun `advanced dependency chains`() {
    // This is a compile-only test. The full integration is in integration-tests
    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph {

          val repository: Repository

          @Provides
          fun provideFileSystem(): FileSystem = FileSystems.getDefault()

          @Named("cache-dir-name")
          @Provides
          fun provideCacheDirName(): String = "cache"
        }

        @Inject @SingleIn(AppScope::class) class Cache(fileSystem: FileSystem, @Named("cache-dir-name") cacheDirName: Provider<String>)
        @Inject @SingleIn(AppScope::class) class HttpClient(cache: Cache)
        @Inject @SingleIn(AppScope::class) class ApiClient(httpClient: Lazy<HttpClient>)
        @Inject class Repository(apiClient: ApiClient)
        """
          .trimIndent(),
        extraImports = arrayOf("java.nio.file.FileSystem", "java.nio.file.FileSystems"),
      )
    )
  }

  @Test
  fun `accessors can be wrapped`() {
    // This is a compile-only test. The full integration is in integration-tests
    compile(
      source(
        """
        @DependencyGraph
        abstract class ExampleGraph {

          var counter = 0

          abstract val scalar: Int
          abstract val provider: Provider<Int>
          abstract val lazy: Lazy<Int>
          abstract val providerOfLazy: Provider<Lazy<Int>>

          @Provides
          fun provideInt(): Int = counter++
        }

        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `simple cycle detection`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {

          val value: Int

          @Provides
          fun provideInt(value: Int): Int = value
        }

        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:11 [Metro/DependencyCycle] Found a dependency cycle while processing 'test.ExampleGraph'.
        Cycle:
            Int <--> Int (depends on itself)

        Trace:
            kotlin.Int is injected at
                [test.ExampleGraph] test.ExampleGraph.provideInt(…, value)
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `complex cycle detection`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {

          val value: String

          @Provides
          fun provideString(int: Int): String {
              return "Value: " + int
          }

          @Provides
          fun provideInt(double: Double): Int {
              return double.toInt()
          }

          @Provides
          fun provideDouble(string: String): Double {
              return string.length.toDouble()
          }
        }

        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:11 [Metro/DependencyCycle] Found a dependency cycle while processing 'test.ExampleGraph'.
        Cycle:
            Double --> String --> Int --> Double

        Trace:
            kotlin.Double is injected at
                [test.ExampleGraph] test.ExampleGraph.provideInt(…, double)
            kotlin.String is injected at
                [test.ExampleGraph] test.ExampleGraph.provideDouble(…, string)
            kotlin.Int is injected at
                [test.ExampleGraph] test.ExampleGraph.provideString(…, int)
            kotlin.Double is injected at
                [test.ExampleGraph] test.ExampleGraph.provideInt(…, double)
            ...
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `graphs cannot have constructors with parameters`() {
    val result =
      compile(
        source(
          """
          @DependencyGraph
          abstract class ExampleGraph(
            @get:Provides
            val text: String
          ) {

            abstract fun string(): String

            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Provides text: String): ExampleGraph
            }
          }

          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertDiagnostics(
      "e: ExampleGraph.kt:7:28 Dependency graphs cannot have constructor parameters. Use @DependencyGraph.Factory instead."
    )
  }

  @Test
  fun `self referencing graph dependency cycle should fail`() {
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface CharSequenceGraph {

            fun value(): CharSequence

            @Provides
            fun provideValue(string: String): CharSequence = string

            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Includes graph: CharSequenceGraph): CharSequenceGraph
            }
          }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertDiagnostics(
      """
      e: CharSequenceGraph.kt:16:33 DependencyGraph.Factory declarations cannot have their target graph type as parameters.
      """
        .trimIndent()
    )
  }

  @Test
  fun `graph creators must be abstract classes or interfaces`() {
    compile(
      source(
        fileNameWithoutExtension = "ExampleGraph",
        source =
          """
          // Ok
          @DependencyGraph
          interface GraphWithAbstractClass {
            @DependencyGraph.Factory
            abstract class Factory {
              abstract fun create(): GraphWithAbstractClass
            }
          }

          // Ok
          @DependencyGraph
          interface GraphWithInterface {
            @DependencyGraph.Factory
            interface Factory {
              fun create(): GraphWithInterface
            }
          }

          // Ok
          @DependencyGraph
          interface GraphWithFunInterface {
            @DependencyGraph.Factory
            fun interface Factory {
              fun create(): GraphWithFunInterface
            }
          }

          @DependencyGraph
          interface GraphWithEnumFactory {
            @DependencyGraph.Factory
            enum class Factory {
              THIS_IS_JUST_WRONG
            }
          }

          @DependencyGraph
          interface GraphWithOpenFactory {
            @DependencyGraph.Factory
            open class Factory {
              fun create(): GraphWithOpenFactory {
                TODO()
              }
            }
          }

          @DependencyGraph
          interface GraphWithFinalFactory {
            @DependencyGraph.Factory
            class Factory {
              fun create(): GraphWithFinalFactory {
                TODO()
              }
            }
          }

          @DependencyGraph
          interface GraphWithSealedFactoryInterface {
            @DependencyGraph.Factory
            sealed interface Factory {
              fun create(): GraphWithSealedFactoryInterface
            }
          }

          @DependencyGraph
          interface GraphWithSealedFactoryClass {
            @DependencyGraph.Factory
            sealed class Factory {
              abstract fun create(): GraphWithSealedFactoryClass
            }
          }
          """
            .trimIndent(),
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:36:14 @DependencyGraph.Factory declarations should be non-sealed abstract classes or interfaces.
        e: ExampleGraph.kt:44:14 @DependencyGraph.Factory declarations should be non-sealed abstract classes or interfaces.
        e: ExampleGraph.kt:54:9 @DependencyGraph.Factory declarations should be non-sealed abstract classes or interfaces.
        e: ExampleGraph.kt:64:20 @DependencyGraph.Factory declarations should be non-sealed abstract classes or interfaces.
        e: ExampleGraph.kt:72:16 @DependencyGraph.Factory declarations should be non-sealed abstract classes or interfaces.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `graph creators cannot be local classes`() {
    compile(
      source(
        """
        @DependencyGraph
        interface GraphWithAbstractClass {

          fun example() {
            @DependencyGraph.Factory
            abstract class Factory {
              fun create(): GraphWithAbstractClass {
                error("noop")
              }
            }
          }
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: GraphWithAbstractClass.kt:11:20 @DependencyGraph.Factory declarations cannot be local classes."
      )
    }
  }

  @Test
  fun `graph creators must be visible`() {
    val result =
      compile(
        source(
          fileNameWithoutExtension = "graphs",
          source =
            """
            // Ok
            @DependencyGraph
            abstract class GraphWithImplicitPublicFactory {
              @DependencyGraph.Factory
              interface Factory {
                fun create(): GraphWithImplicitPublicFactory
              }
            }

            // Ok
            @DependencyGraph
            abstract class GraphWithPublicFactory {
              @DependencyGraph.Factory
              public interface Factory {
                fun create(): GraphWithPublicFactory
              }
            }

            // Ok
            @DependencyGraph
            abstract class GraphWithInternalFactory {
              @DependencyGraph.Factory
              internal interface Factory {
                fun create(): GraphWithInternalFactory
              }
            }

            @DependencyGraph
            abstract class GraphWithProtectedFactory {
              @DependencyGraph.Factory
              protected interface Factory {
                fun create(): GraphWithProtectedFactory
              }
            }

            @DependencyGraph
            abstract class GraphWithPrivateFactory {
              @DependencyGraph.Factory
              private interface Factory {
                fun create(): GraphWithPrivateFactory
              }
            }
            """
              .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertDiagnostics(
      """
      e: graphs.kt:36:3 @DependencyGraph.Factory declarations must be public or internal.
      e: graphs.kt:44:3 @DependencyGraph.Factory declarations must be public or internal.
      """
        .trimIndent()
    )
  }

  @Test
  fun `graph factories fails with no abstract functions`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          @DependencyGraph.Factory
          interface Factory {
            fun create(): ExampleGraph {
              TODO()
            }
          }
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:9:13 @DependencyGraph.Factory declarations must have exactly one abstract function but found none.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `graph factories fails with more than one abstract function`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          @DependencyGraph.Factory
          interface Factory {
            fun create(): ExampleGraph
            fun create2(): ExampleGraph
          }
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:10:9 @DependencyGraph.Factory declarations must have exactly one abstract function but found 2.
        e: ExampleGraph.kt:11:9 @DependencyGraph.Factory declarations must have exactly one abstract function but found 2.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `graph factories cannot inherit multiple abstract functions`() {
    compile(
      source(
        """
        interface BaseFactory1<T> {
          fun create1(): T
        }

        interface BaseFactory2<T> : BaseFactory1<T> {
          fun create2(): T
        }

        @DependencyGraph
        interface ExampleGraph {
          @DependencyGraph.Factory
          interface Factory : BaseFactory2<ExampleGraph>
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: BaseFactory1.kt:17:13 @DependencyGraph.Factory declarations must have exactly one abstract function but found 2.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `graph factories params must be unique - check bindsinstance`() {
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph {
            val value: Int

            @DependencyGraph.Factory
            interface Factory {
              fun create(@Provides value: Int, @Provides value2: Int): ExampleGraph
            }
          }
          """
            .trimIndent()
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertDiagnostics(
      "e: ExampleGraph.kt:12:48 DependencyGraph.Factory abstract function parameters must be unique."
    )
  }

  @Test
  fun `graph factories params must be unique - check graph`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val value: Int

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes intGraph: IntGraph, @Includes intGraph2: IntGraph): ExampleGraph
          }
        }
        @DependencyGraph
        interface IntGraph {
          val value: Int

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Provides value: Int): IntGraph
          }
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:12:56 DependencyGraph.Factory abstract function parameters must be unique.
        """
          .trimIndent()
      )
    }
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
          .trimIndent()
      )
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
            .trimIndent()
        )
      )

    compile(
      source(
        """
        fun main(int: Int) = IntGraph(int)
        """
          .trimIndent()
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
            .trimIndent()
        )
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
          .trimIndent()
      ),
      previousCompilationResult = firstResult,
    ) {
      val graph = ExampleGraph.companionObjectInstance.callFunction<Any>("createDefault", 3)
      assertThat(graph).isNotNull()
      assertThat(graph.callProperty<Int>("int")).isEqualTo(3)
    }
  }

  @Test
  fun `simple multibinds accessed from accessor`() {
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph {
            @Multibinds val strings: Set<String>

            @Provides
            @IntoSet
            fun provideString(): String = "Hello, world!"
          }
          """
            .trimIndent()
        )
      )
    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val strings = graph.callProperty<Set<String>>("strings")
    assertThat(strings).containsExactly("Hello, world!")
  }

  /**
   * This tests that an implicit multibinding with an explicit one do not conflict as duplicate
   * bindings
   */
  @Test
  fun `simple multibinds accessed from accessor - different order declaration`() {
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph {
            @Provides
            @IntoSet
            fun provideString(): String = "Hello, world!"

            @Multibinds val strings: Set<String>
          }
          """
            .trimIndent()
        )
      )
    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val strings = graph.callProperty<Set<String>>("strings")
    assertThat(strings).containsExactly("Hello, world!")
  }

  @Test
  fun `simple implicit multibindings from accessor`() {
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph {
            val strings: Set<String>

            @Provides
            @IntoSet
            fun provideString(): String = "Hello, world!"
          }
          """
            .trimIndent()
        )
      )
    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val strings = graph.callProperty<Set<String>>("strings")
    assertThat(strings).containsExactly("Hello, world!")
  }

  @Test
  fun `empty multibinding with no opt-in is an error`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          @Multibinds val strings: Set<String>
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:19 [Metro/EmptyMultibinding] Multibinding 'kotlin.collections.Set<kotlin.String>' was unexpectedly empty.

        If you expect this multibinding to possibly be empty, annotate its declaration with `@Multibinds(allowEmpty = true)`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `empty multibinding with no opt-in is an error and reports similar types - set`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          @Multibinds val strings: Set<String>

          @IntoSet
          @Provides
          fun provideCharSequence(): CharSequence = "Hello, world!"
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:19 [Metro/EmptyMultibinding] Multibinding 'kotlin.collections.Set<kotlin.String>' was unexpectedly empty.

        If you expect this multibinding to possibly be empty, annotate its declaration with `@Multibinds(allowEmpty = true)`.

        Similar multibindings:
        - Set<CharSequence>
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `empty multibinding with no opt-in is an error and reports similar types - map`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          @Multibinds val strings: Map<String, String>

          @StringKey("Element")
          @IntoMap
          @Provides
          fun provideCharSequence(): CharSequence = "Hello, world!"
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:19 [Metro/EmptyMultibinding] Multibinding 'kotlin.collections.Map<kotlin.String, kotlin.String>' was unexpectedly empty.

        If you expect this multibinding to possibly be empty, annotate its declaration with `@Multibinds(allowEmpty = true)`.

        Similar multibindings:
        - Map<String, CharSequence>
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `simple explicit opted-in multibindings with no contributors is empty`() {
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph {
            @Multibinds(allowEmpty = true) val strings: Set<String>
          }
          """
            .trimIndent()
        )
      )
    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val strings = graph.callProperty<Set<String>>("strings")
    assertThat(strings).isEmpty()
  }

  @Test
  fun `simple multibindings from class injection`() {
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph {
            val exampleClass: ExampleClass

            @Provides
            @IntoSet
            fun provideString(): String = "Hello, world!"
          }

          @Inject
          class ExampleClass(val strings: Set<String>) : Callable<Set<String>> {
            override fun call(): Set<String> = strings
          }
          """
            .trimIndent()
        )
      )
    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val strings = graph.callProperty<Callable<Set<String>>>("exampleClass")
    assertThat(strings.call()).containsExactly("Hello, world!")
  }

  @Test
  fun `simple multibindings from provided class`() {
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph {
            val exampleClass: ExampleClass

            @Provides
            @IntoSet
            fun provideString(): String = "Hello, world!"

            @Provides fun provideExampleClass(strings: Set<String>): ExampleClass = ExampleClass(strings)
          }

          class ExampleClass(val strings: Set<String>) : Callable<Set<String>> {
            override fun call(): Set<String> = strings
          }
          """
            .trimIndent()
        )
      )
    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val strings = graph.callProperty<Callable<Set<String>>>("exampleClass")
    assertThat(strings.call()).containsExactly("Hello, world!")
  }

  /**
   * We used to track binds providers in a map, which would fail on cases where the same callable ID
   * was used. This ensures we support that case.
   */
  @Test
  fun `multiple multibinding contributors with matching callable ids`() {
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph : ContributingInterface1, ContributingInterface2 {
            val strings: Set<String>

            @Provides
            val provideInt: Int get() = 1

            @Binds
            val Int.provideString: Number

            @Provides
            @IntoSet
            val provideString: String get() = "0"

          }

          interface ContributingInterface1 {
            @Provides
            @IntoSet
            fun provideString(int: Int): String = int.toString()
          }

          interface ContributingInterface2 {
            @Provides
            @IntoSet
            fun provideString(number: Number): String {
              // Resolves to 1 + 2 = 3
              return (number.toInt() + 2).toString()
            }
          }
          """
            .trimIndent()
        )
      )
    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()

    val strings = graph.callProperty<Set<String>>("strings")
    assertThat(strings).containsExactly("0", "1", "3")
  }

  @Test
  fun `single module with contributed multibinding as elements used in constructor injection`() {
    compile(
      source(
        """
        abstract class LoggedInScope
        interface ContributedInterface
        class Impl1 : ContributedInterface

        @ContributesTo(AppScope::class)
        interface MultibindingsModule {

          @Provides
          @ElementsIntoSet
          fun provideImpl1(): Set<ContributedInterface> = setOf(Impl1())
        }

        class MultibindingConsumer @Inject constructor(val contributions: Set<ContributedInterface>)

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val multibindingConsumer: MultibindingConsumer
        }
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(
          exampleGraph
            .callProperty<Any>("multibindingConsumer")
            .callProperty<Set<Any>>("contributions")
            .map { it.javaClass.canonicalName }
        )
        .isEqualTo(listOf("test.Impl1"))
    }
  }

  @Test
  fun `single module with contributed multibinding used in constructor injection`() {
    compile(
      source(
        """
        abstract class LoggedInScope
        interface ContributedInterface
        class Impl1 : ContributedInterface

        @ContributesTo(AppScope::class)
        interface MultibindingsModule {

          @Provides
          @IntoSet
          fun provideImpl1(): ContributedInterface = Impl1()
        }

        class MultibindingConsumer @Inject constructor(val contributions: Set<ContributedInterface>)

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val multibindingConsumer: MultibindingConsumer
        }
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(
          exampleGraph
            .callProperty<Any>("multibindingConsumer")
            .callProperty<Set<Any>>("contributions")
            .map { it.javaClass.canonicalName }
        )
        .isEqualTo(listOf("test.Impl1"))
    }
  }

  // The annotation is stored on the FirPropertyAccessorSymbol, this test ensures
  // we check there too
  @Test
  fun `private provider with get-annotated Provides`() {
    compile(
      source(
        """
        @DependencyGraph
        abstract class ExampleGraph {
          abstract val count: Int

          @get:Provides val countProvider: Int = 3
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val count = graph.callProperty<Int>("count")
      assertThat(count).isEqualTo(3)
    }
  }

  // Compile-only validation test
  @Test
  fun `graphs with scope properties declare implicit SingleIn scopes`() {
    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph {
          val exampleClass: ExampleClass
        }

        @SingleIn(AppScope::class)
        @Inject
        class ExampleClass
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertNotNull(graph.callProperty<Any>("exampleClass"))
    }
  }

  // Compile-only validation test
  @Test
  fun `graphs with additional scopes declare implicit SingleIn scopes`() {
    compile(
      source(
        """
        @DependencyGraph(AppScope::class, additionalScopes = [LoggedInScope::class])
        interface ExampleGraph {
          val appClass: AppClass
          val loggedInClass: LoggedInClass
        }

        abstract class LoggedInScope private constructor()

        @SingleIn(AppScope::class)
        @Inject
        class AppClass

        @SingleIn(LoggedInScope::class)
        @Inject
        class LoggedInClass
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertNotNull(graph.callProperty<Any>("appClass"))
      assertNotNull(graph.callProperty<Any>("loggedInClass"))
    }
  }

  @Test
  fun `JvmSuppressWildcards does not affect type keys`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          @Multibinds(allowEmpty = true)
          val ints: Set<Int>

          val exampleClass: ExampleClass
        }

        @Inject
        class ExampleClass(ints: Set<@JvmSuppressWildcards Int>)
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertNotNull(graph.callProperty<Any>("exampleClass"))
    }
  }

  @Test
  fun `a multibinding can be declared with @Multibinds and contributed to using @ElementsIntoSet`() {
    compile(
      source(
        """
        interface MultiboundType

        @Inject
        class MultiImpl : MultiboundType

        @ContributesTo(AppScope::class)
        interface MultibindingsModule {
          @Provides @ElementsIntoSet
          fun provideMulti(impl: MultiImpl): Set<@JvmSuppressWildcards MultiboundType> = setOf(impl)
        }

        @ContributesTo(AppScope::class)
        interface MultibindingsModule2 {
          @Multibinds(allowEmpty = true)
          fun provideMulti(): Set<@JvmSuppressWildcards MultiboundType>
        }

        @DependencyGraph(AppScope::class)
        interface ExampleGraph {
          val multi: Set<MultiboundType>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertNotNull(graph.callProperty<Any>("multi"))
    }
  }

  @Test
  fun `duplicate bindings are reported - double provides`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val exampleClass: ExampleClass

          @Provides fun provideExampleClass1(): ExampleClass = ExampleClass()
          @Provides fun provideExampleClass2(): ExampleClass = ExampleClass()
        }

        class ExampleClass
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:11 [Metro/DuplicateBinding] Multiple bindings found for test.ExampleClass

          ExampleGraph.kt:10:3
            @Provides fun provideExampleClass1(): test.ExampleClass
                                                  ~~~~~~~~~~~~~~~~~
          ExampleGraph.kt:11:3
            @Provides fun provideExampleClass2(): test.ExampleClass
                                                  ~~~~~~~~~~~~~~~~~
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate bindings are reported - double provides and binds`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val exampleClass: ExampleClass

          @Provides fun provideExampleClass1(): ExampleClass = Impl1()
          @Binds fun Impl2.provideExampleClass2(): ExampleClass
        }

        interface ExampleClass
        class Impl1 : ExampleClass
        @Inject class Impl2 : ExampleClass
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:11 [Metro/DuplicateBinding] Multiple bindings found for test.ExampleClass

          ExampleGraph.kt:10:3
            @Provides fun provideExampleClass1(): test.ExampleClass
                                                  ~~~~~~~~~~~~~~~~~
          ExampleGraph.kt:11:10
            @Binds fun test.Impl2.provideExampleClass2(): test.ExampleClass
                                                          ~~~~~~~~~~~~~~~~~
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate bindings are reported - double binds`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val exampleClass: ExampleClass

          @Binds fun Impl1.provideExampleClass1(): ExampleClass
          @Binds fun Impl2.provideExampleClass2(): ExampleClass
        }

        interface ExampleClass
        @Inject class Impl1 : ExampleClass
        @Inject class Impl2 : ExampleClass
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:11 [Metro/DuplicateBinding] Multiple bindings found for test.ExampleClass

          ExampleGraph.kt:10:10
            @Binds fun test.Impl1.provideExampleClass1(): test.ExampleClass
                                                          ~~~~~~~~~~~~~~~~~
          ExampleGraph.kt:11:10
            @Binds fun test.Impl2.provideExampleClass2(): test.ExampleClass
                                                          ~~~~~~~~~~~~~~~~~
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `duplicate bindings are reported - double contributed binds`() {
    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph {
          val exampleClass: ExampleClass
        }

        interface ExampleClass

        @ContributesBinding(AppScope::class)
        @Inject
        class Impl1 : ExampleClass

        @ContributesBinding(AppScope::class)
        @Inject
        class Impl2 : ExampleClass
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:11 [Metro/DuplicateBinding] Multiple bindings found for test.ExampleClass

          ExampleGraph.kt:13:1
            test.Impl1 contributes a binding of test.ExampleClass
                                                ~~~~~~~~~~~~~~~~~
          ExampleGraph.kt:17:1
            test.Impl2 contributes a binding of test.ExampleClass
                                                ~~~~~~~~~~~~~~~~~
        """
          .trimIndent()
      )
    }
  }

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
        )
      )

    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph
        """
          .trimIndent(),
        extraImports = arrayOf("other.OtherClass"),
      ),
      previousCompilationResult = otherResult,
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:11 [Metro/DuplicateBinding] Multiple bindings found for other.OtherClass

          <unknown location, likely a separate compilation>
            other.ExampleClass contributes a binding of other.OtherClass
                                                        ~~~~~~~~~~~~~~~~
          <unknown location, likely a separate compilation>
            other.ExampleClass2 contributes a binding of other.OtherClass
                                                         ~~~~~~~~~~~~~~~~
        """
          .trimIndent()
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
        )
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
        interface ExampleGraph
        """
          .trimIndent(),
        extraImports = arrayOf("other.OtherClass"),
      ),
      previousCompilationResult = otherResult,
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:11 [Metro/DuplicateBinding] Multiple bindings found for other.OtherClass

          <unknown location, likely a separate compilation>
            other.ExampleClass contributes a binding of other.OtherClass
                                                        ~~~~~~~~~~~~~~~~
          ExampleClass2.kt:7:1
            test.ExampleClass2 contributes a binding of other.OtherClass
                                                        ~~~~~~~~~~~~~~~~
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `transitive scoped bindings are ordered correctly`() {
    compile(
      source(
        """
        interface ContributedInterface

        @Inject
        @SingleIn(AppScope::class)
        class Impl1 : ContributedInterface

        @Inject
        @SingleIn(AppScope::class)
        class Impl2(val contributedInterface: ContributedInterface)

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
          val impl1: Impl1
          val impl2: Impl2

          @Binds val Impl1.bind: ContributedInterface
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()

      // Impl1 is correctly scoped and bound
      val impl1 = graph.callProperty<Any>("impl1")
      val contributed = graph.callProperty<Any>("contributedInterface")
      assertThat(impl1.javaClass.simpleName).isEqualTo("Impl1")
      assertThat(impl1).isSameInstanceAs(contributed)

      // Impl2 correctly uses the bound type
      val impl2 = graph.callProperty<Any>("impl2")
      val impl1FromImpl2 = impl2.callProperty<Any>("contributedInterface")
      assertThat(impl1FromImpl2).isSameInstanceAs(impl1)
      assertThat(impl1FromImpl2).isSameInstanceAs(contributed)

      // Calling again also respects scoping
      assertThat(graph.callProperty<Any>("impl2")).isSameInstanceAs(impl2)
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
            .trimIndent()
        )
      )

    compile(
      source(
        """
        fun main() = createGraph<ExampleGraph>()
        """
          .trimIndent()
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
            .trimIndent()
        )
      )

    compile(
      source(
        """
        fun main() = createGraphFactory<ExampleGraph.Factory>().createGraph()
        """
          .trimIndent()
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val graph = invokeMain<Any>()
      assertNotNull(graph)
      assertThat(graph.javaClass.simpleName).isEqualTo(Symbols.StringNames.IMPL)
    }
  }

  @Test
  fun `similar bindings - different qualifiers`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val int: Int

          @Provides @Named("qualified") fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.Int

            kotlin.Int is requested at
                [test.ExampleGraph] test.ExampleGraph.int

        Similar bindings:
          - @Named("qualified") Int (Different qualifier). Type: Provided. Source: ExampleGraph.kt:10:3
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `similar bindings - different qualifiers - qualifier on requested`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          @Named("qualified") val int: Int

          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:27 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @dev.zacsweers.metro.Named("qualified") kotlin.Int

            @dev.zacsweers.metro.Named("qualified") kotlin.Int is requested at
                [test.ExampleGraph] test.ExampleGraph.int

        Similar bindings:
          - Int (Different qualifier). Type: Provided. Source: ExampleGraph.kt:10:3
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `similar bindings - multibinding - set`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val int: Int

          @Provides @IntoSet fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.Int

            kotlin.Int is requested at
                [test.ExampleGraph] test.ExampleGraph.int

        Similar bindings:
          - Set<Int> (Multibinding). Type: Multibinding.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `similar bindings - multibinding - map`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val int: Int

          @Provides @IntoMap @StringKey("hello") fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.Int

            kotlin.Int is requested at
                [test.ExampleGraph] test.ExampleGraph.int

        Similar bindings:
          - Map<String, Int> (Multibinding). Type: Multibinding.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `similar bindings - subtype`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val int: Number

          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.Number

            kotlin.Number is requested at
                [test.ExampleGraph] test.ExampleGraph.int

        Similar bindings:
          - Int (Subtype). Type: Provided. Source: ExampleGraph.kt:10:3
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `similar bindings - supertype`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val int: Int

          @Provides fun provideNumber(): Number = 0
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.Int

            kotlin.Int is requested at
                [test.ExampleGraph] test.ExampleGraph.int

        Similar bindings:
          - Number (Supertype). Type: Provided. Source: ExampleGraph.kt:10:3
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `similar bindings - multiple`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val int: Int

          @Provides fun provideNumber(): Number = 0
          @Provides @Named("qualified") fun provideInt(): Int = 0
          @Provides @IntoSet fun provideIntIntoSet(): Int = 0
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.Int

            kotlin.Int is requested at
                [test.ExampleGraph] test.ExampleGraph.int

        Similar bindings:
          - @Named("qualified") Int (Different qualifier). Type: Provided. Source: ExampleGraph.kt:11:3
          - Number (Supertype). Type: Provided. Source: ExampleGraph.kt:10:3
          - Set<Int> (Multibinding). Type: Multibinding.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `multibindings - map`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val ints: Map<Int, Int>

          @Provides @IntoMap @IntKey(0) fun provideInt0(): Int = 0
          @Provides @IntoMap @IntKey(1) fun provideInt1(): Int = 1
          @Provides @IntoMap @IntKey(2) fun provideInt2(): Int = 2
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val ints = graph.callProperty<Map<Int, Int>>("ints")
      assertThat(ints).containsExactly(0, 0, 1, 1, 2, 2)
    }
  }

  @Test
  fun `multibindings - map provider`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val ints: Map<Int, Provider<Int>>

          @Provides @IntoMap @IntKey(0) fun provideInt0(): Int = 0
          @Provides @IntoMap @IntKey(1) fun provideInt1(): Int = 1
          @Provides @IntoMap @IntKey(2) fun provideInt2(): Int = 2
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val ints = graph.callProperty<Map<Int, Provider<Int>>>("ints")
      assertThat(ints.mapValues { (_, value) -> value() }).containsExactly(0, 0, 1, 1, 2, 2)
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

          val intsProvider: Map<Int, Provider<Int>>

          val providerOfInts: Provider<Map<Int, Int>>
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val intsProvider = graph.callProperty<Map<Int, Provider<Int>>>("intsProvider")
      // Use toString() because on JVM this may be inlined
      assertThat(intsProvider.toString()).isEqualTo(MapProviderFactory.empty<Int, Int>().toString())
      val ints = graph.callProperty<Map<Int, Int>>("ints")
      assertThat(ints.toString()).isEqualTo(MapFactory.empty<Int, Int>().toString())
      val providerOfInts = graph.callProperty<Provider<Map<Int, Int>>>("providerOfInts")
      assertThat(providerOfInts.toString()).isEqualTo(MapFactory.empty<Int, Int>().toString())
    }
  }

  @Ignore("TODO is this a case we want to support?")
  @Test
  fun `multibindings - map providers of lazy`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val ints: Map<Int, Provider<Lazy<Int>>>

          @Provides @IntoMap @IntKey(0) fun provideInt0(): Int = 0
          @Provides @IntoMap @IntKey(1) fun provideInt1(): Int = 1
          @Provides @IntoMap @IntKey(2) fun provideInt2(): Int = 2
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val ints = graph.callProperty<Map<Int, Provider<Lazy<Int>>>>("ints")
      assertThat(ints.mapValues { (_, value) -> value().value }).containsExactly(0, 0, 1, 1, 2, 2)
    }
  }

  @Test
  fun `multibindings - map provider - declared non-provider`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          @Multibinds
          val ints: Map<Int, Int>

          val exampleClass: ExampleClass

          @Provides @IntoMap @IntKey(0) fun provideInt0(): Int = 0
          @Provides @IntoMap @IntKey(1) fun provideInt1(): Int = 1
          @Provides @IntoMap @IntKey(2) fun provideInt2(): Int = 2
        }

        @Inject class ExampleClass(val ints: Map<Int, Provider<Int>>)
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val exampleClass = graph.callProperty<Any>("exampleClass")
      val ints = exampleClass.callProperty<Map<Int, Provider<Int>>>("ints")
      assertThat(ints.mapValues { (_, value) -> value() }).containsExactly(0, 0, 1, 1, 2, 2)
    }
  }

  @Test
  fun `multibindings - map provider - declared non-provider - with class contributor`() {
    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph {
          val exampleClass: ExampleClass
        }

        @ContributesTo(AppScope::class)
        interface IntsBinding {
          @Multibinds(allowEmpty = true)
          val ints: Map<Int, Provider<Int>>
        }

        fun interface IntHolder {
          fun value(): Int
        }

        @ContributesIntoMap(AppScope::class)
        @IntKey(0)
        @Inject
        class ZeroHolder : IntHolder {
          override fun value(): Int = 0
        }

        @Inject class ExampleClass(val ints: Map<Int, Provider<IntHolder>>)
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val exampleClass = graph.callProperty<Any>("exampleClass")
      val ints = exampleClass.callProperty<Map<Int, Provider<Any>>>("ints")
      assertThat(ints.mapValues { (_, value) -> value().invokeInstanceMethod<Int>("value") })
        .containsExactly(0, 0)
    }
  }

  // Regression test
  @Test
  fun `scoped provider with declared accessor still works`() {
    val first =
      compile(
        source(
          """
          interface Base

          class Impl : Base

          @GraphExtension
          interface ChildGraph {
            val message: String

            @GraphExtension.Factory
            interface Factory {
              fun create(): ChildGraph
            }
          }
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ParentGraph {
          val base: Base

          fun childGraphFactory(): ChildGraph.Factory

          @Provides
          @SingleIn(Unit::class)
          fun provideBase(): Base = Impl()

          @Provides
          fun provideMessage(base: Base): String = base.toString()
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = first,
    )
  }

  @Test
  fun `qualifiers are propagated in includes accessors`() {
    compile(
      source(
        """
            class NumberProviders {
              fun provideInt(): Int = 1
              @Named("int") fun provideQualifiedInt(): Int = 2
              @SingleIn(AppScope::class) fun provideScopedLong(): Long = 3L
              @SingleIn(AppScope::class) @Named("long") fun provideScopedQualifiedLong(): Long = 4L
            }

            @DependencyGraph
            interface ExampleGraph {
              val int: Int
              @Named("int") val qualifiedInt: Int
              val scopedLong: Long
              @Named("long") val qualifiedScopedLong: Long

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Includes parent: NumberProviders): ExampleGraph
              }
            }
        """
      )
    ) {
      val numberProviders = classLoader.loadClass("test.NumberProviders").newInstanceStrict()
      val exampleGraph = ExampleGraph.generatedImpl().createGraphViaFactory(numberProviders)
      assertThat(exampleGraph.callProperty<Int>("int")).isEqualTo(1)
      assertThat(exampleGraph.callProperty<Int>("qualifiedInt")).isEqualTo(2)
      assertThat(exampleGraph.callProperty<Long>("scopedLong")).isEqualTo(3L)
      assertThat(exampleGraph.callProperty<Long>("qualifiedScopedLong")).isEqualTo(4L)
    }
  }

  @Test
  fun `optional deps with back referencing default`() {
    compile(
      source(
        """
            @DependencyGraph
            interface ExampleGraph {
              val message: String

              @Provides private fun provideInt(): Int = 3

              @Provides
              private fun provideMessage(
                intValue: Int,
                input: CharSequence = "Not found: " + intValue,
              ): String = input.toString()
            }
        """
      )
    )
  }

  @Test
  fun `map cycle graph`() {
    compile(
      source(
        """
            @Inject class X(val y: Y)

            @Inject
            class Y(
              val mapOfProvidersOfX: Map<String, Provider<X>>,
              val mapOfProvidersOfY: Map<String, Provider<Y>>,
            )

            @DependencyGraph
            interface CycleMapGraph {
              fun y(): Y

              @Binds @IntoMap @StringKey("X") val X.x: X

              @Binds @IntoMap @StringKey("Y") val Y.y: Y
            }
        """
      )
    )
  }

  @Test
  fun `multiple empty multibinds are reported together`() {
    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph {
          @Multibinds val ints: Set<Int>
          @Multibinds val strings: Set<String>
          @Multibinds val stringsAndInts: Map<String, Int>
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:19 [Metro/EmptyMultibinding] Multibinding 'kotlin.collections.Set<kotlin.Int>' was unexpectedly empty.

        If you expect this multibinding to possibly be empty, annotate its declaration with `@Multibinds(allowEmpty = true)`.

        e: ExampleGraph.kt:9:19 [Metro/EmptyMultibinding] Multibinding 'kotlin.collections.Set<kotlin.String>' was unexpectedly empty.

        If you expect this multibinding to possibly be empty, annotate its declaration with `@Multibinds(allowEmpty = true)`.

        e: ExampleGraph.kt:10:19 [Metro/EmptyMultibinding] Multibinding 'kotlin.collections.Map<kotlin.String, kotlin.Int>' was unexpectedly empty.

        If you expect this multibinding to possibly be empty, annotate its declaration with `@Multibinds(allowEmpty = true)`.
        """
          .trimIndent()
      )
    }
  }

  // Regression tests that ensures that a default dependency (i.e. one that would pass through
  // topo sorting's onMissing() handler doesn't break the later satisfied checks
  @Test
  fun `optional dependency does not break topo sorting`() {
    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph {
          fun foo(): Foo
        }

        @SingleIn(AppScope::class)
        class Foo @Inject constructor(
          val bar: Bar,
          val text: String = "default"
        )

        @Inject class Bar
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val foo = graph.callFunction<Any>("foo")
      assertThat(foo.callProperty<String>("text")).isEqualTo("default")
    }
  }

  @Test
  fun `roots already in the graph are not re-added`() {
    // Regression test to ensure we don't try to unnecessarily recompute
    // bindings that are already present in the graph (provided some other way)
    // This only affects constructor-injected classes as they would return a
    // non-null value for the binding when it tried to create it
    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val value: Dependency

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Provides value: Dependency): ExampleGraph
          }
        }

        @Inject class Dependency
        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `qualified accessors are valid when narrowing`() {
    compile(
      source(
        """
        interface Parent1 {
          val prop: String
          fun function(): String
        }

        @DependencyGraph interface AppGraph : Parent1 {
          @Named("qualified") override val prop: String
          @Named("qualified") override fun function(): String

          @Named("qualified") @Provides fun provideString(): String = "hello"
        }
        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `qualified accessors are invalid when widening`() {
    compile(
      source(
        """
        interface Parent1 {
          @Named("qualified") val prop: String
          @Named("qualified") fun function(): String
        }

        @DependencyGraph interface AppGraph : Parent1 {
          override val prop: String
          override fun function(): String

          @Named("qualified") @Provides fun provideString(): String = "hello"
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        $$"""
        e: Parent1.kt:11:1 [Metro/QualifierOverrideMismatch] Overridden accessor property 'test.AppGraph.Impl.prop' must have the same qualifier annotations as the overridden accessor property. However, the final accessor property qualifier is 'null' but overridden symbol test.Parent1.prop has '@Named("qualified")'.'
        e: Parent1.kt:11:1 [Metro/QualifierOverrideMismatch] Overridden accessor function 'test.AppGraph.Impl.function' must have the same qualifier annotations as the overridden accessor function. However, the final accessor function qualifier is 'null' but overridden symbol test.Parent1.function has '@Named("qualified")'.'
        e: Parent1.kt:13:16 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is requested at
                [test.AppGraph] test.AppGraph.function()

        Similar bindings:
          - @Named("qualified") String (Different qualifier). Type: Provided. Source: Parent1.kt:15:3
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `conflicting overrides for accessor properties`() {
    compile(
      source(
        """
        interface Parent1 {
          val string: String
        }

        interface Parent2 {
          @Named("qualified") val string: String
        }

        @DependencyGraph interface AppGraph : Parent1, Parent2
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        $$"""
        e: Parent1.kt:11:27 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is requested at
                [test.AppGraph] test.AppGraph.string

        e: Parent1.kt:14:1 [Metro/QualifierOverrideMismatch] Overridden accessor property 'test.AppGraph.Impl.string' must have the same qualifier annotations as the overridden accessor property. However, the final accessor property qualifier is 'null' but overridden symbol test.Parent2.string has '@Named("qualified")'.'
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `conflicting overrides for accessor functions`() {
    compile(
      source(
        """
        interface Parent1 {
          fun string(): String
        }

        interface Parent2 {
          @Named("qualified") fun string(): String
        }

        @DependencyGraph interface AppGraph : Parent1, Parent2
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        $$"""
        e: Parent1.kt:11:27 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is requested at
                [test.AppGraph] test.AppGraph.string()

        e: Parent1.kt:14:1 [Metro/QualifierOverrideMismatch] Overridden accessor function 'test.AppGraph.Impl.string' must have the same qualifier annotations as the overridden accessor function. However, the final accessor function qualifier is 'null' but overridden symbol test.Parent2.string has '@Named("qualified")'.'
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `conflicting overrides for injectors`() {
    compile(
      source(
        """
        class Thing {
          @Inject lateinit var string: String
        }

        interface Parent1 {
          fun injectThing(thing: Thing)
        }

        interface Parent2 {
          fun injectThing(@Named("qualified") thing: Thing)
        }

        @DependencyGraph interface AppGraph : Parent1, Parent2
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        $$"""
        e: Thing.kt:18:1 [Metro/QualifierOverrideMismatch] Overridden injector function 'test.AppGraph.Impl.injectThing' must have the same qualifier annotations as the overridden injector function. However, the final injector function qualifier is 'null' but overridden symbol test.Parent2.injectThing has '@Named("qualified")'.'
        e: Thing.kt:18:28 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is injected at
                [test.AppGraph] test.AppGraph.injectThing()
            dev.zacsweers.metro.MembersInjector<test.Thing> is requested at
                [test.AppGraph] test.AppGraph.injectThing()
        """
          .trimIndent()
      )
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
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: Thing.kt:14:1 Injector function test.AppGraph.Impl.injectThing must return Unit. Or, if it's not an injector, remove its parameter.
        """
          .trimIndent()
      )
    }
  }
}
