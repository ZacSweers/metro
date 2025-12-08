// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedImpl
import org.junit.Ignore
import org.junit.Test

class NullableBindingsTest : MetroCompilerTest() {
  @Test
  fun simple() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          val int: Int
          val nullable: Int?

          @Provides
          fun provideInt(): Int = 0

          @Provides
          fun provideNullableInt(): Int? = 1
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(graph.callProperty<Int>("int")).isEqualTo(0)
      assertThat(graph.callProperty<Int?>("nullable")).isEqualTo(1)
    }
  }

  @Test
  fun `simple missing - nullable is absent`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          val int: Int
          val nullable: Int?

          @Provides
          fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:9:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.Int?

            kotlin.Int? is requested at
                [test.ExampleGraph] test.ExampleGraph.nullable

        Similar bindings:
          - Int (Non-nullable equivalent). Type: Provided. Source: ExampleGraph.kt:12:3
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `simple missing - nonnull is absent`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          val int: Int
          val nullable: Int?

          @Provides
          fun provideNullableInt(): Int? = 1
        }
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:7 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.Int

            kotlin.Int is requested at
                [test.ExampleGraph] test.ExampleGraph.int

        Similar bindings:
          - Int? (Nullable equivalent). Type: Provided. Source: ExampleGraph.kt:12:3
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `absent nullable binding doesn't satisfy nullable request`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          val foo: Foo

          @Inject
          class Foo(val input: Int?)
        }
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:11:13 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.Int?

            kotlin.Int? is injected at
                [test.ExampleGraph] test.ExampleGraph.Foo(…, input)
            test.ExampleGraph.Foo is requested at
                [test.ExampleGraph] test.ExampleGraph.foo
        """
          .trimIndent()
      )
    }
  }

  @Ignore("Not supported yet")
  @Test
  fun `injectable generic class with nullable type arg`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          val foo: GenericFoo<String?>

          @Inject
          class GenericFoo<T>(val input: T)

          @Provides
          fun provideNullableString(): String? = "hello"
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val foo = graph.callProperty<Any>("foo")
      assertThat(foo.callProperty<String?>("input")).isEqualTo("hello")
    }
  }

  @Test
  fun `nullable binding with dependency chain`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          val foo: Foo

          @Inject
          class Foo(val bar: Bar)

          @Inject
          class Bar(val value: String?)

          @Provides
          fun provideNullableString(): String? = "test"
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val foo = graph.callProperty<Any>("foo")
      val bar = foo.callProperty<Any>("bar")
      assertThat(bar.callProperty<String?>("value")).isEqualTo("test")
    }
  }

  @Test
  fun `nullable set multibindings`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          val nullableInts: Set<Int?>
          val ints: Set<Int>

          @Provides
          @IntoSet
          fun provideInt1(): Int? = 0

          @Provides
          @IntoSet
          fun provideIntNull(): Int? = null

          @Provides
          @IntoSet
          fun provideIntNull2(): Int? = null

          @Provides
          @IntoSet
          fun provideInt3(): Int = 3
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val ints = graph.callProperty<Set<Int>>("ints")
      assertThat(ints).containsExactly(3)
      val nullableInts = graph.callProperty<Set<Int>>("nullableInts")
      assertThat(nullableInts).containsExactly(0, null)
    }
  }

  @Test
  fun `nullable set multibindings - missing`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          @Multibinds
          val nullableInts: Set<Int?>
          val ints: Set<Int>

          @Provides
          @IntoSet
          fun provideInt(): Int = 3
        }
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:9:7 [Metro/EmptyMultibinding] Multibinding 'kotlin.collections.Set<kotlin.Int?>' was unexpectedly empty.

        If you expect this multibinding to possibly be empty, annotate its declaration with `@Multibinds(allowEmpty = true)`.

        Similar multibindings:
        - Set<Int>
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `nullable map multibindings`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          val nullableInts: Map<Int, Int?>
          val ints: Map<Int, Int>

          @Provides
          @IntoMap
          @IntKey(0)
          fun provideInt1(): Int? = 0

          @Provides
          @IntoMap
          @IntKey(1)
          fun provideIntNull(): Int? = null

          @Provides
          @IntoMap
          @IntKey(2)
          fun provideIntNull2(): Int? = null

          @Provides
          @IntoMap
          @IntKey(3)
          fun provideInt3(): Int = 3
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val ints = graph.callProperty<Map<Int, Int>>("ints")
      assertThat(ints).containsExactly(3, 3)
      val nullableInts = graph.callProperty<Map<Int, Int?>>("nullableInts")
      assertThat(nullableInts).containsExactly(0, 0, 1, null, 2, null)
    }
  }

  @Test
  fun `nullable map multibindings - in providers`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          val nullableInts: Map<Int, Provider<Int?>>
          val ints: Map<Int, Provider<Int>>

          @Provides
          @IntoMap
          @IntKey(0)
          fun provideInt1(): Int? = 0

          @Provides
          @IntoMap
          @IntKey(1)
          fun provideIntNull(): Int? = null

          @Provides
          @IntoMap
          @IntKey(2)
          fun provideIntNull2(): Int? = null

          @Provides
          @IntoMap
          @IntKey(3)
          fun provideInt3(): Int = 3
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val ints = graph.callProperty<Map<Int, Provider<Int>>>("ints")
      assertThat(ints.mapValues { it.value() }).containsExactly(3, 3)
      val nullableInts = graph.callProperty<Map<Int, Provider<Int?>>>("nullableInts")
      assertThat(nullableInts.mapValues { it.value() }).containsExactly(0, 0, 1, null, 2, null)
    }
  }

  @Test
  fun `nullable map multibindings - missing`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          @Multibinds
          val nullableInts: Set<Int?>
          val ints: Set<Int>

          @Provides
          @IntoSet
          fun provideInt(): Int = 3
        }
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:9:7 [Metro/EmptyMultibinding] Multibinding 'kotlin.collections.Set<kotlin.Int?>' was unexpectedly empty.

        If you expect this multibinding to possibly be empty, annotate its declaration with `@Multibinds(allowEmpty = true)`.

        Similar multibindings:
        - Set<Int>
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `nullable may be bound as non-nullable type`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          val int: Int
          val nullableInt: Int?

          @Provides
          fun provideInt(): Int = 1

          @Binds
          val Int.bindAsNullable: Int?
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      assertThat(graph.callProperty<Int>("int")).isEqualTo(1)
      assertThat(graph.callProperty<Int?>("nullableInt")).isEqualTo(1)
    }
  }

  @Test
  fun `non-nullable may not be bound as nullable type`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          val int: Int
          val nullableInt: Int?

          @Provides
          fun provideInt(): Int = 1

          @Binds
          val Int?.bindAsNullable: Int
        }
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:15:12 Binds receiver type `kotlin.Int?` is not a subtype of bound type `kotlin.Int`.
        """
          .trimIndent()
      )
    }
  }
}
