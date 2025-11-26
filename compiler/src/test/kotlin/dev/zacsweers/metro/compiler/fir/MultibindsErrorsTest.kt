// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode.COMPILATION_ERROR
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import org.junit.Test

class MultibindsErrorsTest : MetroCompilerTest() {

  @Test
  fun `only one multibinding annotation is allowed`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @IntoSet @ElementsIntoSet fun provideInts(): Set<Int> = emptySet()
          @Provides @IntoSet @IntoMap @ClassKey(Int::class) fun provideOtherInts(): Set<Int> = emptySet()
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:33 Only one of `@Multibinds`, `@ElementsIntoSet`, `@IntoMap`, or `@IntoSet` is allowed.
        e: ExampleGraph.kt:8:57 Only one of `@Multibinds`, `@ElementsIntoSet`, `@IntoMap`, or `@IntoSet` is allowed.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `no overrides`() {
    compile(
      source(
        """
        interface ExampleGraph : Base {
          @Multibinds override fun ints(): Set<Int>
          @ElementsIntoSet @Provides override fun provideInts(): Set<Int> = emptySet()
          @IntoSet @Provides override fun provideInt(): Int = 0
          @Provides @IntoMap @ClassKey(Int::class) override fun provideMapInts(): Int = 0
        }

        interface Base {
          fun ints(): Set<Int>
          fun provideInts(): Set<Int>
          fun provideInt(): Int
          fun provideMapInts(): Int
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
      options = metroOptions.copy(transformProvidersToPrivate = false),
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:15 Multibinding contributors cannot be overrides.
        e: ExampleGraph.kt:8:30 Multibinding contributors cannot be overrides.
        e: ExampleGraph.kt:9:22 Multibinding contributors cannot be overrides.
        e: ExampleGraph.kt:10:44 Multibinding contributors cannot be overrides.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `multibinds must be abstract`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @Multibinds fun ints(): Set<Int> = emptySet()
          @Multibinds val intsProp: Set<Int> get() = emptySet()
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:27 Non-private @Multibinds declarations must be abstract and not have a function or getter body.
        e: ExampleGraph.kt:8:29 Non-private @Multibinds declarations must be abstract and not have a function or getter body.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `multibinds cannot be scoped`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @Multibinds @SingleIn(AppScope::class) fun ints(): Set<Int>
          @Multibinds @SingleIn(AppScope::class) val intsProp: Set<Int>
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:15 @Multibinds declarations may not have scopes.
        e: ExampleGraph.kt:8:15 @Multibinds declarations may not have scopes.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `multibinds cannot be binds or provides`() {
    // Impossible to write a Provides+Multibinds since abtract check would kick in
    compile(
      source(
        """
        interface ExampleGraph {
          @Multibinds @Binds @Named("qualified") val Set<Int>.intsProp: Set<Int>
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:55 `@Multibinds` declarations cannot also be annotated with `@Provides` or `@Binds` annotations.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `multibinds must be maps or sets`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @Multibinds fun missingReturnType()
          @Multibinds fun explicitBadReturn(): Nothing
          @Multibinds fun badMapSubtype(): LinkedHashMap<String, String>
          @Multibinds fun badSetSubtype(): LinkedHashSet<String>
          @Multibinds fun okMap(): Map<String, String>
          @Multibinds fun okSet(): Set<String>
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:19 `@Multibinds` declarations can only return a `Map` or `Set`.
        e: ExampleGraph.kt:8:40 `@Multibinds` declarations can only return a `Map` or `Set`.
        e: ExampleGraph.kt:9:36 `@Multibinds` declarations can only return a `Map` or `Set`.
        e: ExampleGraph.kt:10:36 `@Multibinds` declarations can only return a `Map` or `Set`.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `Provides or binds required - IntoMap`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @IntoMap @ClassKey(Int::class) fun bad(): Int = 0
          @Provides @IntoMap @ClassKey(Int::class) fun providesGood(): Int = 0
          @Binds @IntoMap @ClassKey(Int::class) fun Int.bindsGood(): Number
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:38 `@IntoSet`, `@IntoMap`, and `@ElementsIntoSet` must be used in conjunction with `@Provides` or `@Binds` annotations.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `Provides or binds required - IntoSet`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @IntoSet fun bad(): Int = 0
          @Provides @IntoSet fun providesGood(): Int = 0
          @Binds @IntoSet fun Int.bindsGood(): Number
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:16 `@IntoSet`, `@IntoMap`, and `@ElementsIntoSet` must be used in conjunction with `@Provides` or `@Binds` annotations.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `Provides or binds required - ElementsIntoSet`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @ElementsIntoSet fun bad(): Set<Int> = setOf(1)
          @Provides @ElementsIntoSet fun providesGood(): Set<Int> = setOf(1)
          @Binds @Named("qualified") @ElementsIntoSet fun Set<Int>.bindsGood(): Set<Int>
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:24 `@IntoSet`, `@IntoMap`, and `@ElementsIntoSet` must be used in conjunction with `@Provides` or `@Binds` annotations.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `ElementsIntoSet - must be a collection`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @Provides @ElementsIntoSet fun bad(): Int = 1
          @Provides @ElementsIntoSet fun badIterable(): Iterable<Int> = setOf(1)
          @Provides @ElementsIntoSet fun providesGood(): Set<Int> = setOf(1)
          @Provides @ElementsIntoSet fun providesGoodSubtype(): HashSet<Int> = hashSetOf(1)
          @Provides @ElementsIntoSet fun providesGoodList(): List<Int> = listOf(1)
          @Provides @ElementsIntoSet fun providesGoodCollection(): Collection<Int> = listOf(1)
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:34 `@ElementsIntoSet` must return a Collection.
        e: ExampleGraph.kt:8:34 `@ElementsIntoSet` must return a Collection.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `multiple map keys are an error`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @ClassKey(Int::class)
          @IntKey(1)
          @Provides
          @IntoMap
          fun bad(): Int = 1
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:3 Only one @MapKey should be be used on a given @IntoMap declaration.
        e: ExampleGraph.kt:8:3 Only one @MapKey should be be used on a given @IntoMap declaration.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `map key missing into map`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @ClassKey(Int::class)
          @Provides
          fun bad(): Int = 1
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:3 `@MapKey` annotations are only allowed on `@IntoMap` declarations.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `IntoMap missing map key`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @IntoMap
          @Provides
          fun bad(): Int = 1
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:9:7 `@IntoMap` declarations must define a @MapKey annotation.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `maps cannot have nullable keys`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @Multibinds(allowEmpty = true)
          val strings: Map<String?, String>
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:16 Multibinding map keys cannot be nullable. Use a non-nullable type instead.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `maps cannot have star projection keys`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @Multibinds(allowEmpty = true)
          val strings: Map<*, String>
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:16 Multibinding Map keys cannot be star projections. Use a concrete type instead.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `maps cannot have star projection values`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @Multibinds(allowEmpty = true)
          val strings: Map<String, *>
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:16 Multibinding Map values cannot be star projections. Use a concrete type instead.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `maps cannot have star projection values - wrapped in provider`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @Multibinds(allowEmpty = true)
          val strings: Map<String, Provider<*>>
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:16 Multibinding Map values cannot be star projections. Use a concrete type instead.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `sets cannot have star projection values`() {
    compile(
      source(
        """
        interface ExampleGraph {
          @Multibinds(allowEmpty = true)
          val strings: Set<*>
        }
        """
          .trimIndent()
      ),
      expectedExitCode = COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:8:16 Multibinding Set elements cannot be star projections. Use a concrete type instead.
        """
          .trimIndent()
      )
    }
  }
}
