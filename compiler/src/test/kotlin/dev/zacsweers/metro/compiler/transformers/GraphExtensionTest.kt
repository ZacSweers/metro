// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.compiler.GrandParentGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.ParentGraph
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callFunction
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedImpl
import dev.zacsweers.metro.compiler.invokeInstanceMethod
import org.junit.Test

class GraphExtensionTest : MetroCompilerTest() {

  @Test
  fun simple() {
    compile(
      source(
        """
            @DependencyGraph
            interface ParentGraph : ChildGraph.Factory {
              @Provides fun provideInt(): Int = 1
            }

            @GraphExtension
            interface ChildGraph {
              val int: Int

              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ChildGraph
              }
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.invokeInstanceMethod<Any>("create")
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(1)
    }
  }

  @Test
  fun `simple extension in another module`() {
    val firstCompilation =
      compile(
        source(
          """
            @GraphExtension
            interface ChildGraph {
              val int: Int

              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ChildGraph
              }
            }
        """
        )
      )

    compile(
      source(
        """
            @DependencyGraph
            interface ParentGraph : ChildGraph.Factory {
              @Provides fun provideInt(): Int = 1
            }
        """
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val parentGraph = ParentGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("create")
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(1)
    }
  }

  @Test
  fun `unscoped inherited providers remain unscoped`() {
    compile(
      source(
        """
            @DependencyGraph
            abstract class ParentGraph : ChildGraph.Factory {
              private var count: Int = 0

              @Provides fun provideInt(): Int = count++
            }

            @GraphExtension
            interface ChildGraph {
              val int: Int

              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ChildGraph
              }
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("create")
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(0)
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(1)
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(2)
    }
  }

  @Test
  fun `scoped providers are respected`() {
    compile(
      source(
        """
            @SingleIn(AppScope::class)
            @DependencyGraph
            abstract class ParentGraph : ChildGraph.Factory {
              private var count: Int = 0

              @SingleIn(AppScope::class)
              @Provides
              fun provideInt(): Int = count++
            }

            @SingleIn(Unit::class)
            @GraphExtension
            interface ChildGraph {
              val int: Int

              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ChildGraph
              }
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("create")
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(0)
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(0)
    }
  }

  @Test
  fun `scoped providers fields are reused`() {
    compile(
      source(
        """
            @SingleIn(AppScope::class)
            @DependencyGraph
            abstract class ParentGraph : ChildGraph.Factory {
              private var count: Int = 0

              @SingleIn(AppScope::class)
              @Provides
              fun provideInt(): Int = count++
            }

            @SingleIn(Unit::class)
            @GraphExtension
            interface ChildGraph {
              val int: Provider<Int>

              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ChildGraph
              }
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("create")
      assertThat(childGraph.callProperty<Provider<Int>>("int"))
        .isSameInstanceAs(childGraph.callProperty<Provider<Int>>("int"))
    }
  }

  @Test
  fun `scoped bindings are inherited - implicit`() {
    compile(
      source(
        """
            @SingleIn(AppScope::class)
            @DependencyGraph
            abstract class ParentGraph : ChildGraph.Factory {
              private var count: Int = 0

              @SingleIn(AppScope::class)
              @Provides
              fun provideInt(): Int = count++
            }

            @GraphExtension
            interface ChildGraph {
              val int: Int

              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ChildGraph
              }
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("create")
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(0)
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(0)
    }
  }

  @Test
  fun `multiple levels - three levels`() {
    compile(
      source(
        """
            @DependencyGraph
            interface GrandParentGraph : ParentGraph.Factory {
              @Provides fun provideString(): String = "grandparent"
            }

            @GraphExtension
            interface ParentGraph : ChildGraph.Factory {
              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ParentGraph
              }

              @Provides fun provideInt(): Int = 1
            }

            @GraphExtension
            interface ChildGraph {
              val string: String
              val int: Int

              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ChildGraph
              }
            }
        """,
        fileNameWithoutExtension = "Graphs",
      )
    ) {
      val grandParentGraph = GrandParentGraph.generatedImpl().createGraphWithNoArgs()
      val parentGraph = grandParentGraph.callFunction<Any>("create")
      val childGraph = parentGraph.callFunction<Any>("create")
      assertThat(childGraph.callProperty<String>("string")).isEqualTo("grandparent")
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(1)
    }
  }

  @Test
  fun `multiple levels - three levels - multi-module`() {
    val firstCompilation =
      compile(
        source(
          """
            @GraphExtension
            interface ChildGraph {
              val string: String
              val int: Int

              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ChildGraph
              }
            }

            @GraphExtension
            interface ParentGraph : ChildGraph.Factory {
              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ParentGraph
              }

              @Provides fun provideInt(): Int = 1
            }
        """,
          fileNameWithoutExtension = "ParentGraphs",
        )
      )

    compile(
      source(
        """
            @DependencyGraph
            interface GrandParentGraph : ParentGraph.Factory {
              @Provides fun provideString(): String = "grandparent"
            }
        """
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val grandParentGraph = GrandParentGraph.generatedImpl().createGraphWithNoArgs()
      val parentGraph = grandParentGraph.callFunction<Any>("create")
      val childGraph = parentGraph.callFunction<Any>("create")
      assertThat(childGraph.callProperty<String>("string")).isEqualTo("grandparent")
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(1)
    }
  }

  @Test
  fun `parent extends interface with companion provider`() {
    compile(
      source(
        """
            interface HasCompanion {
              companion object {
                @Provides fun provideString(): String = "companion"
              }
            }

            @DependencyGraph
            interface ParentGraph : HasCompanion, ChildGraph.Factory {
              @Provides fun provideInt(): Int = 1
            }

            @GraphExtension
            interface ChildGraph {
              val string: String
              val int: Int

              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ChildGraph
              }
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("create")
      assertThat(childGraph.callProperty<String>("string")).isEqualTo("companion")
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(1)
    }
  }

  @Test
  fun `inherited binds`() {
    compile(
      source(
        """
            interface Base
            class Impl : Base

            @DependencyGraph
            interface ParentGraph : ChildGraph.Factory {
              @Provides fun impl(): Impl = Impl()
              @Binds fun bind(impl: Impl): Base
            }

            @GraphExtension
            interface ChildGraph {
              val base: Base

              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ChildGraph
              }
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("create")
      assertThat(childGraph.callProperty<Any>("base").javaClass.simpleName).isEqualTo("Impl")
    }
  }

  @Test
  fun `multibindings with parent and child`() {
    compile(
      source(
        """
          @DependencyGraph
          interface ParentGraph : ChildGraph.Factory {
            @IntoSet
            @Provides
            fun stringFromParent(): String = "parent"

            val parentSet: Set<String>
          }

          @GraphExtension
          interface ChildGraph {
            val strings: Set<String>

            @IntoSet
            @Provides
            fun stringFromChild(): String = "child"

            @GraphExtension.Factory
            fun interface Factory {
              fun create(): ChildGraph
            }
          }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("create")

      assertThat(parentGraph.callProperty<Set<String>>("parentSet")).containsExactly("parent")
      assertThat(childGraph.callProperty<Set<String>>("strings")).containsExactly("parent", "child")
    }
  }

  @Test
  fun `child graphs cannot have the same aggregation scope as parent - direct`() {
    compile(
      source(
        """
          @DependencyGraph(AppScope::class)
          interface ParentGraph : ChildGraph.Factory {
            @Provides
            fun provideString(): String = "parent"
          }

          @GraphExtension(AppScope::class)
          interface ChildGraph {
            val string: String

            @GraphExtension.Factory
            fun interface Factory {
              fun create(): ChildGraph
            }
          }
        """
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ParentGraph.kt:8:35 Graph extension 'test.ChildGraph' has overlapping aggregation scopes with parent graph 'test.ParentGraph':
        - dev.zacsweers.metro.AppScope
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `child graphs cannot have the same scope annotations as parent - direct`() {
    compile(
      source(
        """
          @SingleIn(AppScope::class)
          @DependencyGraph
          interface ParentGraph : ChildGraph.Factory {
            @Provides
            fun provideString(): String = "parent"
          }

          @SingleIn(AppScope::class)
          @GraphExtension
          interface ChildGraph {
            val string: String

            @GraphExtension.Factory
            fun interface Factory {
              fun create(): ChildGraph
            }
          }
        """
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ParentGraph.kt:9:35 Graph extension 'test.ChildGraph' has overlapping scope annotations with parent graph 'test.ParentGraph':
        - @SingleIn(dev.zacsweers.metro.AppScope::class)
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `child graphs cannot have the same scope annotations as ancestor - indirect`() {
    compile(
      source(
        """
          @SingleIn(AppScope::class)
          @DependencyGraph
          interface GrandParentGraph : ParentGraph.Factory {
            @Provides
            fun provideString(): String = "grandParent"
          }

          abstract class UserScope private constructor()

          @SingleIn(UserScope::class)
          @GraphExtension
          interface ParentGraph : ChildGraph.Factory {
            @GraphExtension.Factory
            fun interface Factory {
              fun create(): ParentGraph
            }
          }

          @SingleIn(AppScope::class)
          @GraphExtension
          interface ChildGraph {
            val string: String

            @GraphExtension.Factory
            fun interface Factory {
              fun create(): ChildGraph
            }
          }
        """
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: GrandParentGraph.kt Graph extension 'test.ChildGraph' has overlapping scope annotations with ancestor graphs':
        - @dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class) (from ancestor 'test.GrandParentGraph')
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `qualifiers are propagated in accessors too`() {
    compile(
      source(
        """
            @SingleIn(AppScope::class)
            @DependencyGraph
            interface ParentGraph : ChildGraph.Factory {
              @Provides fun provideInt(): Int = 1
              @Provides @Named("int") fun provideQualifiedInt(): Int = 2
              @Provides @SingleIn(AppScope::class) fun provideScopedLong(): Long = 3L
              @Provides @SingleIn(AppScope::class) @Named("long") fun provideScopedQualifiedLong(): Long = 4L
            }

            @GraphExtension
            interface ChildGraph {
              val int: Int
              @Named("int") val qualifiedInt: Int
              val scopedLong: Long
              @Named("long") val qualifiedScopedLong: Long

              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ChildGraph
              }
            }
        """
      ),
      options = metroOptions.copy(enableFullBindingGraphValidation = true),
    ) {
      val parentGraph = ParentGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("create")
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(1)
      assertThat(childGraph.callProperty<Int>("qualifiedInt")).isEqualTo(2)
      assertThat(childGraph.callProperty<Long>("scopedLong")).isEqualTo(3L)
      assertThat(childGraph.callProperty<Long>("qualifiedScopedLong")).isEqualTo(4L)
    }
  }

  // Regression test for https://github.com/ZacSweers/metro/issues/375
  @Test
  fun `parent graph is generated first even if child graph is defined first`() {
    compile(
      source(
        """
            @GraphExtension
            interface ChildGraph {
              val int: Int

              @GraphExtension.Factory
              fun interface Factory {
                fun create(): ChildGraph
              }
            }

            @SingleIn(AppScope::class)
            @DependencyGraph
            interface ParentGraph : ChildGraph.Factory {
              @Provides fun provideInt(): Int = 1
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("create")
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(1)
    }
  }
}
