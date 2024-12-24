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
package dev.zacsweers.lattice.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import dev.zacsweers.lattice.Provider
import dev.zacsweers.lattice.compiler.ExampleGraph
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.assertContainsAll
import dev.zacsweers.lattice.compiler.callFunction
import dev.zacsweers.lattice.compiler.callProperty
import dev.zacsweers.lattice.compiler.createGraphViaFactory
import dev.zacsweers.lattice.compiler.createGraphWithNoArgs
import dev.zacsweers.lattice.compiler.generatedLatticeGraphClass
import java.util.concurrent.Callable
import org.junit.Test

class DependencyGraphTransformerTest : LatticeCompilerTest() {

  @Test
  fun simple() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.BindsInstance
            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Singleton
            import dev.zacsweers.lattice.createGraphFactory
            import java.util.concurrent.Callable

            @Singleton
            @DependencyGraph
            interface ExampleGraph {

              fun exampleClass(): ExampleClass

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@BindsInstance text: String): ExampleGraph
              }
            }

            @Singleton
            @Inject
            class ExampleClass(private val text: String) : Callable<String> {
              override fun call(): String = text
            }

            fun createExampleClass(): (String) -> Callable<String> {
              val factory = createGraphFactory<ExampleGraph.Factory>()
              return { factory.create(it).exampleClass() }
            }

          """
            .trimIndent(),
        )
      )
    val graph =
      result.ExampleGraph.generatedLatticeGraphClass().createGraphViaFactory("Hello, world!")

    val exampleClass = graph.callFunction<Callable<String>>("exampleClass")
    assertThat(exampleClass.call()).isEqualTo("Hello, world!")

    // 2nd pass exercising creating a graph via createGraphFactory()
    @Suppress("UNCHECKED_CAST")
    val callableCreator =
      result.classLoader
        .loadClass("test.ExampleGraphKt")
        .getDeclaredMethod("createExampleClass")
        .invoke(null) as (String) -> Callable<String>
    val callable = callableCreator("Hello, world!")
    assertThat(callable.call()).isEqualTo("Hello, world!")
  }

  @Test
  fun `missing binding should fail compilation and report property accessor`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Inject
            import java.util.concurrent.Callable

            @DependencyGraph
            interface ExampleGraph {

              val text: String
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleGraph.kt:10:3 [Lattice/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is requested at
                [test.ExampleGraph] test.ExampleGraph.text
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and report property accessor with qualifier`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import java.util.concurrent.Callable

            @DependencyGraph
            interface ExampleGraph {

              @Named("hello")
              val text: String
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleGraph.kt:11:3 [Lattice/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("hello") kotlin.String

            @Named("hello") kotlin.String is requested at
                [test.ExampleGraph] test.ExampleGraph.text
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and report property accessor with get site target qualifier`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import java.util.concurrent.Callable

            @DependencyGraph
            interface ExampleGraph {

              @get:Named("hello")
              val text: String
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleGraph.kt:11:3 [Lattice/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("hello") kotlin.String

            @Named("hello") kotlin.String is requested at
                [test.ExampleGraph] test.ExampleGraph.text
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and function accessor`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Inject
            import java.util.concurrent.Callable

            @DependencyGraph
            interface ExampleGraph {

              fun text(): String
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleGraph.kt:10:3 [Lattice/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is requested at
                [test.ExampleGraph] test.ExampleGraph.text()
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and function accessor with qualifier`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import java.util.concurrent.Callable

            @DependencyGraph
            interface ExampleGraph {

              @Named("hello")
              fun text(): String
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleGraph.kt:11:3 [Lattice/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("hello") kotlin.String

            @Named("hello") kotlin.String is requested at
                [test.ExampleGraph] test.ExampleGraph.text()
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and report binding stack`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject

            @DependencyGraph
            abstract class ExampleGraph() {

              abstract fun exampleClass(): ExampleClass
            }

            @Inject
            class ExampleClass(private val text: String)

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleGraph.kt:14:20 [Lattice/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is injected at
                [test.ExampleGraph] test.ExampleClass(…, text)
            test.ExampleClass is requested at
                [test.ExampleGraph] test.ExampleGraph.exampleClass()
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and report binding stack with qualifier`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named

            @DependencyGraph
            abstract class ExampleGraph() {

              abstract fun exampleClass(): ExampleClass
            }

            @Inject
            class ExampleClass(@Named("hello") private val text: String)

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleGraph.kt:15:20 [Lattice/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("hello") kotlin.String

            @Named("hello") kotlin.String is injected at
                [test.ExampleGraph] test.ExampleClass(…, text)
            test.ExampleClass is requested at
                [test.ExampleGraph] test.ExampleGraph.exampleClass()
        """
          .trimIndent()
      )
  }

  @Test
  fun `scoped bindings from providers are scoped correctly`() {
    // Ensure scoped bindings are properly scoped
    // This means that any calls to them should return the same instance, while any calls
    // to unscoped bindings are called every time.
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

            @Singleton
            @DependencyGraph
            abstract class ExampleGraph {

              private var scopedCounter = 0
              private var unscopedCounter = 0

              @Named("scoped")
              abstract val scoped: String

              @Named("unscoped")
              abstract val unscoped: String

              @Singleton
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
            .trimIndent(),
        )
      )

    val graph = result.ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()

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
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Singleton
            import dev.zacsweers.lattice.annotations.SingleIn
            import dev.zacsweers.lattice.annotations.AppScope

            abstract class UserScope private constructor()

            @Singleton
            @SingleIn(AppScope::class)
            @DependencyGraph
            interface ExampleGraph {

              val intValue: Int

              @SingleIn(UserScope::class)
              @Provides
              fun invalidScope(): Int = 0
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContains(
      """
        ExampleGraph.kt:11:1 [Lattice/IncompatiblyScopedBindings] test.ExampleGraph (scopes '@Singleton', '@SingleIn(AppScope::class)') may not reference bindings from different scopes:
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
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

            @DependencyGraph
            interface ExampleGraph : TextProvider {
              val value: String
            }

            interface TextProvider {
              @Provides
              fun provideValue(): String = "Hello, world!"
            }

          """
            .trimIndent(),
        )
      )

    val graph = result.ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
    assertThat(graph.callProperty<String>("value")).isEqualTo("Hello, world!")
  }

  @Test
  fun `providers from supertype companion objects are visible`() {
    // Ensure providers from supertypes are correctly wired. This means both incorporating them in
    // binding resolution and being able to invoke them correctly in the resulting graph.
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

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
            .trimIndent(),
        )
      )

    val graph = result.ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
    assertThat(graph.callProperty<String>("value")).isEqualTo("Hello, world!")
  }

  @Test
  fun `providers overridden from supertypes are errors`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

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
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleGraph.kt:14:16 Do not override `@Provides` declarations. Consider using `@ContributesTo.replaces`, `@ContributesBinding.replaces`, and `@DependencyGraph.excludes` instead."
    )
  }

  @Test
  fun `overrides annotated with provides from non-provides supertypes are ok`() {
    compile(
      kotlin(
        "ExampleGraph.kt",
        """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

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
          .trimIndent(),
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
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides

            @DependencyGraph
            interface ExampleGraph {

              val valueLengths: Int

              @Provides
              fun provideValue(): String = "Hello, world!"

              @Provides
              fun provideValueLengths(value: String, value2: String): Int = value.length + value2.length
            }

          """
            .trimIndent(),
        )
      )

    val graph = result.ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()

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

  @Test
  fun `unscoped providers do not get reused if used only once`() {
    // One aspect of provider fields is we want to reuse them if they're used from multiple places
    // even if they're unscoped. If they're not though, then we don't do this
    //
    // private val stringUserProvider =
    // StringUserProviderFactory.create(StringProvider_Factory.create(...))
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides

            @DependencyGraph
            interface ExampleGraph {

              val valueLengths: Int

              @Provides
              fun provideValue(): String = "Hello, world!"

              @Provides
              fun provideValueLengths(value: String): Int = value.length
            }

          """
            .trimIndent(),
        )
      )

    val graph = result.ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()

    assertThat(graph.javaClass.declaredFields.singleOrNull { it.name == "provideValueProvider" })
      .isNull()

    assertThat(graph.callProperty<Int>("valueLengths")).isEqualTo("Hello, world!".length)
  }

  @Test
  fun `unscoped graphs may not reference scoped types`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Singleton

            @DependencyGraph
            interface ExampleGraph {

              val value: String

              @Singleton
              @Provides
              fun provideValue(): String = "Hello, world!"
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
          ExampleGraph.kt:7:1 [Lattice/IncompatiblyScopedBindings] test.ExampleGraph (unscoped) may not reference scoped bindings:
              kotlin.String (scoped to '@Singleton')
              kotlin.String is requested at
                  [test.ExampleGraph] test.ExampleGraph.value
        """
          .trimIndent()
      )
  }

  @Test
  fun `binding failures should only be focused on the current context`() {
    // small regression test to ensure that we pop the BindingStack correctly
    // while iterating exposed types and don't leave old refs
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides

            @DependencyGraph
            interface ExampleGraph {

              val value: String
              val value2: CharSequence

              @Provides
              fun provideValue(): String = "Hello, world!"
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
          ExampleGraph.kt:10:3 [Lattice/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.CharSequence

              kotlin.CharSequence is requested at
                  [test.ExampleGraph] test.ExampleGraph.value2
        """
          .trimIndent()
      )

    assertThat(result.messages).doesNotContain("kotlin.String is requested at")
  }

  @Test
  fun `simple binds example`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides

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
            .trimIndent(),
        )
      )

    val graph = result.ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()

    assertThat(graph.callProperty<String>("value")).isEqualTo("Hello, world!")

    assertThat(graph.callProperty<CharSequence>("value2")).isEqualTo("Hello, world!")
  }

  @Test
  fun `advanced dependency chains`() {
    // This is a compile-only test. The full integration is in integration-tests
    compile(
      kotlin(
        "ExampleGraph.kt",
        """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Singleton
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.Provider
            import java.nio.file.FileSystem
            import java.nio.file.FileSystems

            @Singleton
            @DependencyGraph
            interface ExampleGraph {

              val repository: Repository

              @Provides
              fun provideFileSystem(): FileSystem = FileSystems.getDefault()

              @Named("cache-dir-name")
              @Provides
              fun provideCacheDirName(): String = "cache"
            }

            @Inject @Singleton class Cache(fileSystem: FileSystem, @Named("cache-dir-name") cacheDirName: Provider<String>)
            @Inject @Singleton class HttpClient(cache: Cache)
            @Inject @Singleton class ApiClient(httpClient: Lazy<HttpClient>)
            @Inject class Repository(apiClient: ApiClient)

          """
          .trimIndent(),
      )
    )
  }

  @Test
  fun `accessors can be wrapped`() {
    // This is a compile-only test. The full integration is in integration-tests
    compile(
      kotlin(
        "ExampleGraph.kt",
        """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.Provider

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
          .trimIndent(),
      )
    )
  }

  @Test
  fun `simple cycle detection`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.Provider

            @DependencyGraph
            interface ExampleGraph {

              val value: Int

              @Provides
              fun provideInt(value: Int): Int = value
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
          ExampleGraph.kt:7:1 [Lattice/DependencyCycle] Found a dependency cycle:
              kotlin.Int is injected at
                  [test.ExampleGraph] test.ExampleGraph.provideInt(…, value)
              kotlin.Int is injected at
                  [test.ExampleGraph] test.ExampleGraph.provideInt(…, value)
              ...
        """
          .trimIndent()
      )
  }

  @Test
  fun `complex cycle detection`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides

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
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
          ExampleGraph.kt:6:1 [Lattice/DependencyCycle] Found a dependency cycle:
              kotlin.Int is injected at
                  [test.ExampleGraph] test.ExampleGraph.provideString(…, int)
              kotlin.Double is injected at
                  [test.ExampleGraph] test.ExampleGraph.provideInt(…, double)
              kotlin.String is injected at
                  [test.ExampleGraph] test.ExampleGraph.provideDouble(…, string)
              kotlin.Int is injected at
                  [test.ExampleGraph] test.ExampleGraph.provideString(…, int)
              ...
        """
          .trimIndent()
      )
  }

  // TODO lazy, provideroflazy, with providers instead of classes, port dagger test, scoped
  @Test
  fun `cycles can be broken - provider`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.Provider
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.BindsInstance
            import java.util.concurrent.Callable

            @DependencyGraph
            interface ExampleGraph {
              val foo: Foo

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@BindsInstance message: String): ExampleGraph
              }
            }

            @Inject
            class Foo(val barProvider: Provider<Bar>): Callable<String> {
              override fun call(): String {
                val bar = barProvider()
                return bar.call()
              }
            }

            @Inject
            class Bar(val foo: Foo, val message: String): Callable<String> {
              override fun call(): String {
                return message
              }
            }

          """
            .trimIndent(),
        )
      )

    val message = "Hello, world!"
    val graph = result.ExampleGraph.generatedLatticeGraphClass().createGraphViaFactory(message)
    val foo: Callable<String> = graph.callProperty("foo")
    assertThat(foo.call()).isEqualTo(message)

    // Assert the foo.barProvider.invoke() yields the same message. Sorta redundant with the above
    // but completionist
    val barProvider = foo.callProperty<Provider<Callable<String>>>("barProvider")
    val barInstance = barProvider()
    assertThat(barInstance.call()).isEqualTo(message)
  }

  @Test
  fun `cycles can be broken - lazy`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.BindsInstance
            import java.util.concurrent.Callable

            @DependencyGraph
            interface ExampleGraph {
              val foo: Foo

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@BindsInstance message: String): ExampleGraph
              }
            }

            @Inject
            class Foo(val barLazy: Lazy<Bar>): Callable<String> {
              override fun call(): String {
                val bar = barLazy.value
                return bar.call()
              }
            }

            @Inject
            class Bar(val foo: Foo, val message: String): Callable<String> {
              private var counter = 0
              override fun call(): String {
                return message + counter++
              }
            }

          """
            .trimIndent(),
        )
      )

    val message = "Hello, world!"
    val graph = result.ExampleGraph.generatedLatticeGraphClass().createGraphViaFactory(message)
    val foo: Callable<String> = graph.callProperty("foo")
    // Multiple calls to the underlying lazy should result in its single instance's count
    // incrementing
    assertThat(foo.call()).isEqualTo(message + "0")
    assertThat(foo.call()).isEqualTo(message + "1")

    // Assert calling the same on the lazy directly
    val barProvider = foo.callProperty<Lazy<Callable<String>>>("barLazy")
    val barInstance = barProvider.value
    assertThat(barInstance.call()).isEqualTo(message + "2")
    assertThat(barInstance.call()).isEqualTo(message + "3")
  }

  @Test
  fun `cycles can be broken - provider - scoped`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.Provider
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Singleton
            import dev.zacsweers.lattice.annotations.BindsInstance
            import java.util.concurrent.Callable

            @Singleton
            @DependencyGraph
            interface ExampleGraph {
              val foo: Foo

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@BindsInstance message: String): ExampleGraph
              }
            }

            @Singleton
            @Inject
            class Foo(val barProvider: Provider<Bar>): Callable<String> {
              override fun call(): String {
                val bar = barProvider()
                check(bar.foo === this)
                return bar.call()
              }
            }

            @Inject
            class Bar(val foo: Foo, val message: String): Callable<String> {
              override fun call(): String {
                return message
              }
            }

          """
            .trimIndent(),
        )
      )

    val message = "Hello, world!"
    val graph = result.ExampleGraph.generatedLatticeGraphClass().createGraphViaFactory(message)
    val foo: Callable<String> = graph.callProperty("foo")
    assertThat(foo.call()).isEqualTo(message)

    // Assert the foo.barProvider.invoke == bar
    val barProvider = foo.callProperty<Provider<Callable<String>>>("barProvider")
    val barInstance = barProvider()
    assertThat(barInstance.call()).isEqualTo(message)

    val fooInBar = barInstance.callProperty<Callable<String>>("foo")
    assertThat(fooInBar).isSameInstanceAs(foo)
  }

  @Test
  fun `graphs cannot have constructors with parameters`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.BindsInstance

            @DependencyGraph
            abstract class ExampleGraph(
              @get:Provides
              val text: String
            ) {

              abstract fun string(): String

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@BindsInstance text: String): ExampleGraph
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
          ExampleGraph.kt:8:28 Dependency graphs cannot have constructors. Use @DependencyGraph.Factory instead.
        """
          .trimIndent()
      )
  }

  @Test
  fun `self referencing graph dependency cycle should fail`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides

            @DependencyGraph
            interface CharSequenceGraph {

              fun value(): CharSequence

              @Provides
              fun provideValue(string: String): CharSequence = string

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(graph: CharSequenceGraph): CharSequenceGraph
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContains(
      """
        ExampleGraph.kt:6:1 [Lattice/GraphDependencyCycle] Graph dependency cycle detected! The below graph depends on itself.
            test.CharSequenceGraph is requested at
                [test.CharSequenceGraph] test.CharSequenceGraph.Factory.create()
      """
        .trimIndent()
    )
  }

  @Test
  fun `graph dependency cycles should fail across multiple graphs`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.Provides

            @DependencyGraph
            interface CharSequenceGraph {

              fun value(): CharSequence

              @Provides
              fun provideValue(string: String): CharSequence = string

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(stringGraph: StringGraph): CharSequenceGraph
              }
            }

            @DependencyGraph
            interface StringGraph {

              val string: String

              @Provides
              fun provideValue(charSequence: CharSequence): String = charSequence.toString()

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(charSequenceGraph: CharSequenceGraph): StringGraph
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContains(
      """
        ExampleGraph.kt:6:1 [Lattice/GraphDependencyCycle] Graph dependency cycle detected!
            test.StringGraph is requested at
                [test.CharSequenceGraph] test.StringGraph.Factory.create()
            test.CharSequenceGraph is requested at
                [test.CharSequenceGraph] test.CharSequenceGraph.Factory.create()
      """
        .trimIndent()
    )
  }

  @Test
  fun `graph creators must be abstract classes or interfaces`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph

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
      )

    result.assertContainsAll(
      "ExampleGraph.kt:35:14 DependencyGraph factory classes should be non-sealed abstract classes or interfaces.",
      "ExampleGraph.kt:43:14 DependencyGraph factory classes should be non-sealed abstract classes or interfaces.",
      "ExampleGraph.kt:53:9 DependencyGraph factory classes should be non-sealed abstract classes or interfaces.",
      "ExampleGraph.kt:63:20 DependencyGraph factory classes should be non-sealed abstract classes or interfaces.",
      "ExampleGraph.kt:71:16 DependencyGraph factory classes should be non-sealed abstract classes or interfaces.",
    )
  }

  @Test
  fun `graph creators cannot be local classes`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph

            @DependencyGraph
            interface GraphWithAbstractClass {

              fun example() {
                @DependencyGraph.Factory
                abstract class Factory {
                  fun create(): GraphWithAbstractClass {
                    TODO()
                  }
                }
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContains(
      """
        ExampleGraph.kt:10:20 DependencyGraph factory classes cannot be local classes.
      """
        .trimIndent()
    )
  }

  @Test
  fun `graph creators must be visible`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph

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

    result.assertContainsAll(
      "ExampleGraph.kt:35:23 DependencyGraph factory must be public or internal.",
      "ExampleGraph.kt:43:21 DependencyGraph factory must be public or internal.",
    )
  }

  @Test
  fun `graph factories fails with no abstract functions`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph

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
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContains(
      """
        ExampleGraph.kt:8:13 @DependencyGraph.Factory classes must have exactly one abstract function but found none.
      """
        .trimIndent()
    )
  }

  @Test
  fun `graph factories fails with more than one abstract function`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph

            @DependencyGraph
            interface ExampleGraph {
              @DependencyGraph.Factory
              interface Factory {
                fun create(): ExampleGraph
                fun create2(): ExampleGraph
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContainsAll(
      "ExampleGraph.kt:9:9 @DependencyGraph.Factory classes must have exactly one abstract function but found 2.",
      "ExampleGraph.kt:10:9 @DependencyGraph.Factory classes must have exactly one abstract function but found 2.",
    )
  }

  @Test
  fun `graph factories cannot inherit multiple abstract functions`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph

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
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContainsAll(
      "ExampleGraph.kt:6:7 @DependencyGraph.Factory classes must have exactly one abstract function but found 2.",
      "ExampleGraph.kt:10:7 @DependencyGraph.Factory classes must have exactly one abstract function but found 2.",
    )
  }

  @Test
  fun `graph factories params must be unique - check bindsinstance`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.BindsInstance

            @DependencyGraph
            interface ExampleGraph {
              val value: Int

              @DependencyGraph.Factory
              interface Factory {
                fun create(@BindsInstance value: Int, @BindsInstance value2: Int): ExampleGraph
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleGraph.kt:12:58 DependencyGraph.Factory abstract function parameters must be unique."
    )
  }

  @Test
  fun `graph factories params must be unique - check graph`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.DependencyGraph
            import dev.zacsweers.lattice.annotations.BindsInstance

            @DependencyGraph
            interface ExampleGraph {
              val value: Int

              @DependencyGraph.Factory
              interface Factory {
                fun create(intGraph: IntGraph, intGraph2: IntGraph): ExampleGraph
              }
            }
            @DependencyGraph
            interface IntGraph {
              val value: Int

              @DependencyGraph.Factory
              interface Factory {
                fun create(@BindsInstance value: Int): IntGraph
              }
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContains(
      "ExampleGraph.kt:12:36 DependencyGraph.Factory abstract function parameters must be unique."
    )
  }

  // TODO
  //  - advanced graph resolution (i.e. complex dep chains)
  //  - break-the-chain deps
  //  - @get:Provides?
  //  - Binds examples
  //  - Inherited exposed types + deduping overrides?
}
