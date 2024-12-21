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
import dev.zacsweers.lattice.compiler.callGraphAccessor
import dev.zacsweers.lattice.compiler.callGraphAccessorProperty
import dev.zacsweers.lattice.compiler.createGraphViaFactory
import dev.zacsweers.lattice.compiler.createGraphWithNoArgs
import dev.zacsweers.lattice.compiler.generatedLatticeGraphClass
import java.util.concurrent.Callable
import org.junit.Test

class ObjectGraphTransformerTest : LatticeCompilerTest() {

  @Test
  fun simple() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.BindsInstance
            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Singleton
            import dev.zacsweers.lattice.createGraphFactory
            import java.util.concurrent.Callable

            @Singleton
            @ObjectGraph
            interface ExampleGraph {

              fun exampleClass(): ExampleClass

              @ObjectGraph.Factory
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
      result.ExampleGraph.generatedLatticeGraphClass()
        .createGraphViaFactory("Hello, world!")

    val exampleClass = graph.callGraphAccessor<Callable<String>>("exampleClass")
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Inject
            import java.util.concurrent.Callable

            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import java.util.concurrent.Callable

            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import java.util.concurrent.Callable

            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Inject
            import java.util.concurrent.Callable

            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import java.util.concurrent.Callable

            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject

            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named

            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

            @Singleton
            @ObjectGraph
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

    val graph =
      result.ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()

    // Repeated calls to the scoped instance only every return one value
    assertThat(graph.callGraphAccessorProperty<String>("scoped")).isEqualTo("text 0")
    assertThat(graph.callGraphAccessorProperty<String>("scoped")).isEqualTo("text 0")

    // Repeated calls to the unscoped instance recompute each time
    assertThat(graph.callGraphAccessorProperty<String>("unscoped")).isEqualTo("text 0")
    assertThat(graph.callGraphAccessorProperty<String>("unscoped")).isEqualTo("text 1")
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Singleton
            import dev.zacsweers.lattice.annotations.SingleIn
            import dev.zacsweers.lattice.annotations.AppScope

            abstract class UserScope private constructor()

            @Singleton
            @SingleIn(AppScope::class)
            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

            @ObjectGraph
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

    val graph =
      result.ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
    assertThat(graph.callGraphAccessorProperty<String>("value")).isEqualTo("Hello, world!")
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

            @ObjectGraph
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

    val graph =
      result.ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()
    assertThat(graph.callGraphAccessorProperty<String>("value")).isEqualTo("Hello, world!")
  }

  @Test
  fun `providers overridden from supertypes are errors`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

            @ObjectGraph
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
      "ExampleGraph.kt:14:16 Do not override `@Provides` declarations. Consider using `@ContributesTo.replaces`, `@ContributesBinding.replaces`, and `@ObjectGraph.excludes` instead."
    )
  }

  @Test
  fun `overrides annotated with provides from non-provides supertypes are ok`() {
    compile(
      kotlin(
        "ExampleGraph.kt",
        """
            package test

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides

            @ObjectGraph
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

    val graph =
      result.ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()

    // Assert we generated a shared field
    val provideValueField =
      graph.javaClass.getDeclaredField("provideValueProvider").apply { isAccessible = true }

    // Get its instance
    @Suppress("UNCHECKED_CAST")
    val provideValueProvider = provideValueField.get(graph) as Provider<String>

    // Get its computed value to plug in below
    val providerValue = provideValueProvider()
    assertThat(graph.javaClass.getDeclaredField("provideValueProvider"))
    assertThat(graph.callGraphAccessorProperty<Int>("valueLengths"))
      .isEqualTo(providerValue.length * 2)
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides

            @ObjectGraph
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

    val graph =
      result.ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()

    assertThat(
        graph.javaClass.declaredFields.singleOrNull { it.name == "provideValueProvider" }
      )
      .isNull()

    assertThat(graph.callGraphAccessorProperty<Int>("valueLengths"))
      .isEqualTo("Hello, world!".length)
  }

  @Test
  fun `unscoped graphs may not reference scoped types`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Singleton

            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides

            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides

            @ObjectGraph
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

    val graph =
      result.ExampleGraph.generatedLatticeGraphClass().createGraphWithNoArgs()

    assertThat(graph.callGraphAccessorProperty<String>("value")).isEqualTo("Hello, world!")

    assertThat(graph.callGraphAccessorProperty<CharSequence>("value2"))
      .isEqualTo("Hello, world!")
  }

  @Test
  fun `advanced dependency chains`() {
    // This is a compile-only test. The full integration is in integration-tests
    compile(
      kotlin(
        "ExampleGraph.kt",
        """
            package test

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Singleton
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.Provider
            import java.nio.file.FileSystem
            import java.nio.file.FileSystems

            @Singleton
            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.Provider

            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.Provider

            @ObjectGraph
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides

            @ObjectGraph
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
              kotlin.String is injected at
                  [test.ExampleGraph] test.ExampleGraph.provideDouble(…, string)
              kotlin.Double is injected at
                  [test.ExampleGraph] test.ExampleGraph.provideInt(…, double)
              kotlin.Int is injected at
                  [test.ExampleGraph] test.ExampleGraph.provideString(…, int)
              ...
        """
          .trimIndent()
      )
  }

  @Test
  fun `graphs cannot have constructors with parameters`() {
    val result =
      compile(
        kotlin(
          "ExampleGraph.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.BindsInstance

            @ObjectGraph
            abstract class ExampleGraph(
              @get:Provides
              val text: String
            ) {

              abstract fun string(): String

              @ObjectGraph.Factory
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
          ExampleGraph.kt:8:28 Object graphs cannot have constructors. Use @ObjectGraph.Factory instead.
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides

            @ObjectGraph
            interface CharSequenceGraph {

              fun value(): CharSequence

              @Provides
              fun provideValue(string: String): CharSequence = string

              @ObjectGraph.Factory
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.Provides

            @ObjectGraph
            interface CharSequenceGraph {

              fun value(): CharSequence

              @Provides
              fun provideValue(string: String): CharSequence = string

              @ObjectGraph.Factory
              fun interface Factory {
                fun create(stringGraph: StringGraph): CharSequenceGraph
              }
            }

            @ObjectGraph
            interface StringGraph {

              val string: String

              @Provides
              fun provideValue(charSequence: CharSequence): String = charSequence.toString()

              @ObjectGraph.Factory
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

            import dev.zacsweers.lattice.annotations.ObjectGraph

            // Ok
            @ObjectGraph
            interface GraphWithAbstractClass {
              @ObjectGraph.Factory
              abstract class Factory {
                abstract fun create(): GraphWithAbstractClass
              }
            }

            // Ok
            @ObjectGraph
            interface GraphWithInterface {
              @ObjectGraph.Factory
              interface Factory {
                fun create(): GraphWithInterface
              }
            }

            // Ok
            @ObjectGraph
            interface GraphWithFunInterface {
              @ObjectGraph.Factory
              fun interface Factory {
                fun create(): GraphWithFunInterface
              }
            }

            @ObjectGraph
            interface GraphWithEnumFactory {
              @ObjectGraph.Factory
              enum class Factory {
                THIS_IS_JUST_WRONG
              }
            }

            @ObjectGraph
            interface GraphWithOpenFactory {
              @ObjectGraph.Factory
              open class Factory {
                fun create(): GraphWithOpenFactory {
                  TODO()
                }
              }
            }

            @ObjectGraph
            interface GraphWithFinalFactory {
              @ObjectGraph.Factory
              class Factory {
                fun create(): GraphWithFinalFactory {
                  TODO()
                }
              }
            }

            @ObjectGraph
            interface GraphWithSealedFactoryInterface {
              @ObjectGraph.Factory
              sealed interface Factory {
                fun create(): GraphWithSealedFactoryInterface
              }
            }

            @ObjectGraph
            interface GraphWithSealedFactoryClass {
              @ObjectGraph.Factory
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
      "ExampleGraph.kt:35:14 ObjectGraph factory classes should be non-sealed abstract classes or interfaces.",
      "ExampleGraph.kt:43:14 ObjectGraph factory classes should be non-sealed abstract classes or interfaces.",
      "ExampleGraph.kt:53:9 ObjectGraph factory classes should be non-sealed abstract classes or interfaces.",
      "ExampleGraph.kt:63:20 ObjectGraph factory classes should be non-sealed abstract classes or interfaces.",
      "ExampleGraph.kt:71:16 ObjectGraph factory classes should be non-sealed abstract classes or interfaces.",
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

            import dev.zacsweers.lattice.annotations.ObjectGraph

            @ObjectGraph
            interface GraphWithAbstractClass {

              fun example() {
                @ObjectGraph.Factory
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
        ExampleGraph.kt:10:20 ObjectGraph factory classes cannot be local classes.
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

            import dev.zacsweers.lattice.annotations.ObjectGraph

            // Ok
            @ObjectGraph
            abstract class GraphWithImplicitPublicFactory {
              @ObjectGraph.Factory
              interface Factory {
                fun create(): GraphWithImplicitPublicFactory
              }
            }

            // Ok
            @ObjectGraph
            abstract class GraphWithPublicFactory {
              @ObjectGraph.Factory
              public interface Factory {
                fun create(): GraphWithPublicFactory
              }
            }

            // Ok
            @ObjectGraph
            abstract class GraphWithInternalFactory {
              @ObjectGraph.Factory
              internal interface Factory {
                fun create(): GraphWithInternalFactory
              }
            }

            @ObjectGraph
            abstract class GraphWithProtectedFactory {
              @ObjectGraph.Factory
              protected interface Factory {
                fun create(): GraphWithProtectedFactory
              }
            }

            @ObjectGraph
            abstract class GraphWithPrivateFactory {
              @ObjectGraph.Factory
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
      "ExampleGraph.kt:35:23 ObjectGraph factory must be public or internal.",
      "ExampleGraph.kt:43:21 ObjectGraph factory must be public or internal.",
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

            import dev.zacsweers.lattice.annotations.ObjectGraph

            @ObjectGraph
            interface ExampleGraph {
              @ObjectGraph.Factory
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
        ExampleGraph.kt:8:13 @ObjectGraph.Factory classes must have exactly one abstract function but found none.
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

            import dev.zacsweers.lattice.annotations.ObjectGraph

            @ObjectGraph
            interface ExampleGraph {
              @ObjectGraph.Factory
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
      "ExampleGraph.kt:9:9 @ObjectGraph.Factory classes must have exactly one abstract function but found 2.",
      "ExampleGraph.kt:10:9 @ObjectGraph.Factory classes must have exactly one abstract function but found 2.",
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

            import dev.zacsweers.lattice.annotations.ObjectGraph

            interface BaseFactory1<T> {
              fun create1(): T
            }

            interface BaseFactory2<T> : BaseFactory1<T> {
              fun create2(): T
            }

            @ObjectGraph
            interface ExampleGraph {
              @ObjectGraph.Factory
              interface Factory : BaseFactory2<ExampleGraph>
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContainsAll(
      "ExampleGraph.kt:6:7 @ObjectGraph.Factory classes must have exactly one abstract function but found 2.",
      "ExampleGraph.kt:10:7 @ObjectGraph.Factory classes must have exactly one abstract function but found 2.",
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.BindsInstance

            @ObjectGraph
            interface ExampleGraph {
              val value: Int

              @ObjectGraph.Factory
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
      "ExampleGraph.kt:12:58 ObjectGraph.Factory abstract function parameters must be unique."
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

            import dev.zacsweers.lattice.annotations.ObjectGraph
            import dev.zacsweers.lattice.annotations.BindsInstance

            @ObjectGraph
            interface ExampleGraph {
              val value: Int

              @ObjectGraph.Factory
              interface Factory {
                fun create(intGraph: IntGraph, intGraph2: IntGraph): ExampleGraph
              }
            }
            @ObjectGraph
            interface IntGraph {
              val value: Int

              @ObjectGraph.Factory
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
      "ExampleGraph.kt:12:36 ObjectGraph.Factory abstract function parameters must be unique."
    )
  }

  // TODO
  //  - advanced graph resolution (i.e. complex dep chains)
  //  - break-the-chain deps
  //  - @get:Provides?
  //  - Binds examples
  //  - Inherited exposed types + deduping overrides?
}
