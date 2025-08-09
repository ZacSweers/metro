// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.compiler.ChildGraph
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.GrandParentGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.Parent1Graph
import dev.zacsweers.metro.compiler.Parent2Graph
import dev.zacsweers.metro.compiler.ParentGraph
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.assertThrows
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphViaFactory
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedMetroGraphClass
import java.lang.reflect.Proxy
import org.junit.Test

class GraphExtensionTest : MetroCompilerTest() {

  @Test
  fun simple() {
    compile(
      source(
        """
            @DependencyGraph(isExtendable = true)
            interface ParentGraph {
              @Provides fun provideInt(): Int = 1
            }

            @DependencyGraph
            interface ChildGraph {
              val int: Int

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ParentGraph): ChildGraph
              }
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(1)
    }
  }

  @Test
  fun `simple extension in another module`() {
    val firstCompilation =
      compile(
        source(
          """
            @DependencyGraph(isExtendable = true)
            interface ParentGraph {
              @Provides fun provideInt(): Int = 1
            }
        """
        )
      )

    compile(
      source(
        """
            @DependencyGraph
            interface ChildGraph {
              val int: Int

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ParentGraph): ChildGraph
              }
            }
        """
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val parentGraph = ParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(1)
    }
  }

  @Test
  fun `unscoped inherited providers remain unscoped`() {
    compile(
      source(
        """
            @DependencyGraph(isExtendable = true)
            abstract class ParentGraph {
              private var count: Int = 0

              @Provides fun provideInt(): Int = count++
            }

            @DependencyGraph
            interface ChildGraph {
              val int: Int

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ParentGraph): ChildGraph
              }
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)
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
            @DependencyGraph(isExtendable = true)
            abstract class ParentGraph {
              private var count: Int = 0

              @SingleIn(AppScope::class)
              @Provides
              fun provideInt(): Int = count++
            }

            @SingleIn(Unit::class)
            @DependencyGraph
            interface ChildGraph {
              val int: Int

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ParentGraph): ChildGraph
              }
            }
        """
      ),
      options = metroOptions.copy(enableStrictValidation = true)
    ) {
      val parentGraph = ParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)
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
            @DependencyGraph(isExtendable = true)
            abstract class ParentGraph {
              private var count: Int = 0

              @SingleIn(AppScope::class)
              @Provides
              fun provideInt(): Int = count++
            }

            @SingleIn(Unit::class)
            @DependencyGraph
            interface ChildGraph {
              val int: Provider<Int>

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ParentGraph): ChildGraph
              }
            }
        """
      ),
      options = metroOptions.copy(enableStrictValidation = true)
    ) {
      val parentGraph = ParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)
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
            @DependencyGraph(isExtendable = true)
            abstract class ParentGraph {
              private var count: Int = 0

              @SingleIn(AppScope::class)
              @Provides
              fun provideInt(): Int = count++
            }

            @DependencyGraph
            interface ChildGraph {
              val int: Int

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ParentGraph): ChildGraph
              }
            }
        """
      ),
      options = metroOptions.copy(enableStrictValidation = true)
    ) {
      val parentGraph = ParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(0)
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(0)
    }
  }

  @Test
  fun `multiple levels - three levels`() {
    compile(
      source(
        """
            @DependencyGraph(isExtendable = true)
            interface GrandParentGraph {
              @Provides fun provideString(): String = "grandparent"
            }

            @DependencyGraph(isExtendable = true)
            interface ParentGraph {
              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends grandParent: GrandParentGraph): ParentGraph
              }

              @Provides fun provideInt(): Int = 1
            }

            @DependencyGraph
            interface ChildGraph {
              val string: String
              val int: Int

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ParentGraph): ChildGraph
              }
            }
        """,
        fileNameWithoutExtension = "Graphs",
      )
    ) {
      val grandParentGraph = GrandParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val parentGraph =
        ParentGraph.generatedMetroGraphClass().createGraphViaFactory(grandParentGraph)
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)
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
            @DependencyGraph(isExtendable = true)
            interface GrandParentGraph {
              @Provides fun provideString(): String = "grandparent"
            }

            @DependencyGraph(isExtendable = true)
            interface ParentGraph {
              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends grandParent: GrandParentGraph): ParentGraph
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
            interface ChildGraph {
              val string: String
              val int: Int

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ParentGraph): ChildGraph
              }
            }
        """
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val grandParentGraph = GrandParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val parentGraph =
        ParentGraph.generatedMetroGraphClass().createGraphViaFactory(grandParentGraph)
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)
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

            @DependencyGraph(isExtendable = true)
            interface ParentGraph : HasCompanion {
              @Provides fun provideInt(): Int = 1
            }

            @DependencyGraph
            interface ChildGraph {
              val string: String
              val int: Int

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ParentGraph): ChildGraph
              }
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)
      assertThat(childGraph.callProperty<String>("string")).isEqualTo("companion")
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(1)
    }
  }

  @Test
  fun `multiple parent graphs`() {
    compile(
      source(
        """
            @DependencyGraph(isExtendable = true)
            interface Parent1Graph {
              @Provides fun provideString(): String = "parent1"
            }

            @DependencyGraph(isExtendable = true)
            interface Parent2Graph {
              @Provides fun provideInt(): Int = 2
            }

            @DependencyGraph
            interface ChildGraph {
              val string: String
              val int: Int

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent1: Parent1Graph, @Extends parent2: Parent2Graph): ChildGraph
              }
            }
        """
      )
    ) {
      val parent1Graph = Parent1Graph.generatedMetroGraphClass().createGraphWithNoArgs()
      val parent2Graph = Parent2Graph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph =
        ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parent1Graph, parent2Graph)
      assertThat(childGraph.callProperty<String>("string")).isEqualTo("parent1")
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(2)
    }
  }

  @Test
  fun `inherited binds`() {
    compile(
      source(
        """
            interface Base
            class Impl : Base

            @DependencyGraph(isExtendable = true)
            interface ParentGraph {
              @Provides fun impl(): Impl = Impl()
              @Binds fun bind(impl: Impl): Base
            }

            @DependencyGraph
            interface ChildGraph {
              val base: Base

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ParentGraph): ChildGraph
              }
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)
      assertThat(childGraph.callProperty<Any>("base").javaClass.simpleName).isEqualTo("Impl")
    }
  }

  @Test
  fun `multibindings with mixed parents`() {
    compile(
      source(
        """
            @DependencyGraph(isExtendable = true)
            interface Parent1Graph {
              @IntoSet @Provides fun string1(): String = "parent1"
            }

            @DependencyGraph(isExtendable = true)
            interface Parent2Graph {
              @IntoSet @Provides fun string2(): String = "parent2"
            }

            @DependencyGraph
            interface ChildGraph {
              val strings: Set<String>

              @IntoSet @Provides fun string3(): String = "child"

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent1: Parent1Graph, @Extends parent2: Parent2Graph): ChildGraph
              }
            }
        """
      )
    ) {
      val parent1Graph = Parent1Graph.generatedMetroGraphClass().createGraphWithNoArgs()
      val parent2Graph = Parent2Graph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph =
        ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parent1Graph, parent2Graph)
      assertThat(childGraph.callProperty<Set<String>>("strings"))
        .containsExactly("parent1", "parent2", "child")
    }
  }

  @Test
  fun `invalid parent graph instance is rejected`() {
    compile(
      source(
        """
            @DependencyGraph(isExtendable = true)
            interface ParentGraph {
              @Provides fun provideString(): String = "parent"
            }

            @DependencyGraph
            interface ChildGraph {
              val string: String

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ParentGraph): ChildGraph
              }
            }
        """
      )
    ) {
      val fakeParentGraph =
        Proxy.newProxyInstance(ParentGraph.classLoader, arrayOf(ParentGraph)) { _, method, _ ->
          if (method.name == "toString") "fake" else null
        }
      assertThrows<Throwable> {
          ChildGraph.generatedMetroGraphClass().createGraphViaFactory(fakeParentGraph)
        }
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo(
          "Constructor parameter parent _must_ be a Metro-compiler-generated instance of test.ParentGraph but was $fakeParentGraph"
        )
    }
  }

  @Test
  fun `two parents with common grandparent`() {
    compile(
      source(
        """
          @DependencyGraph(isExtendable = true)
          interface GrandParentGraph {
            @Provides fun provideCommonInt(): Int = 42
          }

          @DependencyGraph(isExtendable = true)
          interface Parent1Graph {
            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends grandParent: GrandParentGraph): Parent1Graph
            }

            @Provides fun provideString(): String = "parent1"
          }

          @DependencyGraph(isExtendable = true)
          interface Parent2Graph {
            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends grandParent: GrandParentGraph): Parent2Graph
            }

            @Provides fun provideDouble(): Double = 3.14
          }

          @DependencyGraph
          interface ChildGraph {
            val commonInt: Int
            val string: String
            val double: Double

            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends parent1: Parent1Graph, @Extends parent2: Parent2Graph): ChildGraph
            }
          }
      """
      )
    ) {
      val grandParentGraph = GrandParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val parent1Graph =
        Parent1Graph.generatedMetroGraphClass().createGraphViaFactory(grandParentGraph)
      val parent2Graph =
        Parent2Graph.generatedMetroGraphClass().createGraphViaFactory(grandParentGraph)
      val childGraph =
        ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parent1Graph, parent2Graph)

      assertThat(childGraph.callProperty<Int>("commonInt")).isEqualTo(42)
      assertThat(childGraph.callProperty<String>("string")).isEqualTo("parent1")
      assertThat(childGraph.callProperty<Double>("double")).isEqualTo(3.14)
    }
  }

  @Test
  fun `no accessors generated when extension not enabled`() {
    compile(
      source(
        """
          @DependencyGraph(AppScope::class)
          abstract class ExampleGraph {
            @Provides
            @SingleIn(AppScope::class)
            fun provideString(): String = "nonExtendable"
          }
      """
      )
    ) {
      val graphClass = ExampleGraph.generatedMetroGraphClass()
      val accessors =
        graphClass.declaredMethods.map { it.name }.filter { it.contains("_metroAccessor") }
      assertThat(accessors).isEmpty()
    }
  }

  @Test
  fun `multibindings with parent and child`() {
    compile(
      source(
        """
          @DependencyGraph(isExtendable = true)
          interface ParentGraph {
            @IntoSet
            @Provides
            fun stringFromParent(): String = "parent"

            val parentSet: Set<String>
          }

          @DependencyGraph
          interface ChildGraph {
            val strings: Set<String>

            @IntoSet
            @Provides
            fun stringFromChild(): String = "child"

            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends parent: ParentGraph): ChildGraph
            }
          }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)

      assertThat(parentGraph.callProperty<Set<String>>("parentSet")).containsExactly("parent")
      assertThat(childGraph.callProperty<Set<String>>("strings")).containsExactly("parent", "child")
    }
  }

  @Test
  fun `child graphs cannot have the same aggregation scope as parent - direct`() {
    compile(
      source(
        """
          @DependencyGraph(AppScope::class, isExtendable = true)
          interface ParentGraph {
            @Provides
            fun provideString(): String = "parent"
          }

          @DependencyGraph(AppScope::class)
          interface ChildGraph {
            val string: String

            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends parent: ParentGraph): ChildGraph
            }
          }
        """
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ParentGraph.kt:19:35 Graph extensions (@Extends) may not have overlapping aggregation scopes with its parent graph but the following scopes overlap:
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
          @DependencyGraph(isExtendable = true)
          interface ParentGraph {
            @Provides
            fun provideString(): String = "parent"
          }

          @SingleIn(AppScope::class)
          @DependencyGraph
          interface ChildGraph {
            val string: String

            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends parent: ParentGraph): ChildGraph
            }
          }
        """
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ParentGraph.kt:21:35 Graph extensions (@Extends) may not have overlapping scope annotations with its parent graph but the following annotations overlap:
          - @SingleIn(dev.zacsweers.metro.AppScope::class)
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `child graphs parents cannot have the same aggregation scope - direct`() {
    compile(
      source(
        """
          @DependencyGraph(AppScope::class, isExtendable = true)
          interface ParentGraph {
            @Provides
            fun provideString(): String = "parent"
          }

          @DependencyGraph(AppScope::class, isExtendable = true)
          interface OtherParentGraph

          @DependencyGraph(Unit::class)
          interface ChildGraph {
            val string: String

            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends parent: ParentGraph, @Extends other: OtherParentGraph): ChildGraph
            }
          }
        """
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ParentGraph.kt:22:65 Graph extensions (@Extends) may not have multiple parents with the same aggregation scopes:
          Scope: dev.zacsweers.metro.AppScope
          Parent 1: test.ParentGraph
          Parent 2: test.OtherParentGraph
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `child graphs parents cannot have the same scope annotations - direct`() {
    compile(
      source(
        """
          @SingleIn(AppScope::class)
          @DependencyGraph(isExtendable = true)
          interface ParentGraph {
            @Provides
            fun provideString(): String = "parent"
          }

          @SingleIn(AppScope::class)
          @DependencyGraph(isExtendable = true)
          interface OtherParentGraph

          @SingleIn(Unit::class)
          @DependencyGraph
          interface ChildGraph {
            val string: String

            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends parent: ParentGraph, @Extends other: OtherParentGraph): ChildGraph
            }
          }
        """
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ParentGraph.kt:25:65 Graph extensions (@Extends) may not have multiple parents with the same aggregation scopes:
          Scope: @SingleIn(dev.zacsweers.metro.AppScope::class)
          Parent 1: test.ParentGraph
          Parent 2: test.OtherParentGraph
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
          @DependencyGraph(isExtendable = true)
          interface GrandParentGraph {
            @Provides
            fun provideString(): String = "grandParent"
          }

          abstract class UserScope private constructor()

          @SingleIn(UserScope::class)
          @DependencyGraph(isExtendable = true)
          interface ParentGraph {
            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends grandParent: GrandParentGraph): ParentGraph
            }
          }

          @SingleIn(AppScope::class)
          @DependencyGraph
          interface ChildGraph {
            val string: String

            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends parent: ParentGraph): ChildGraph
            }
          }
        """
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: GrandParentGraph.kt:27:21 Graph extensions (@Extends) may not have overlapping scopes with its ancestor graphs but the following scopes overlap:
          - @dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class) (from ancestor 'test.GrandParentGraph')
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `child graphs cannot have the same scope annotations as multiple ancestors - indirect`() {
    compile(
      source(
        """
          @SingleIn(Scope1::class)
          @DependencyGraph(isExtendable = true)
          interface GrandParentGraph {
            @Provides
            fun provideString(): String = "grandParent"
          }

          @SingleIn(Scope2::class)
          @DependencyGraph(isExtendable = true)
          interface OtherGrandParentGraph

          abstract class Scope1 private constructor()
          abstract class Scope2 private constructor()

          @SingleIn(AppScope::class)
          @DependencyGraph(isExtendable = true)
          interface ParentGraph {
            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends grandParent: GrandParentGraph, @Extends other: OtherGrandParentGraph): ParentGraph
            }
          }

          @SingleIn(Scope1::class)
          @SingleIn(Scope2::class)
          @DependencyGraph
          interface ChildGraph {
            val string: String

            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends parent: ParentGraph): ChildGraph
            }
          }
        """
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: GrandParentGraph.kt:33:21 Graph extensions (@Extends) may not have overlapping scopes with its ancestor graphs but the following scopes overlap:
          - @dev.zacsweers.metro.SingleIn(test.Scope1::class) (from ancestor 'test.GrandParentGraph')
          - @dev.zacsweers.metro.SingleIn(test.Scope2::class) (from ancestor 'test.OtherGrandParentGraph')
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `child graphs ancestors cannot have the same scope annotations - indirect`() {
    compile(
      source(
        """
          @SingleIn(AppScope::class)
          @DependencyGraph(isExtendable = true)
          interface GrandParentGraph {
            @Provides
            fun provideString(): String = "grandParent"
          }

          @SingleIn(AppScope::class)
          @DependencyGraph(isExtendable = true)
          interface OtherGrandParentGraph

          abstract class Scope1 private constructor()
          abstract class Scope2 private constructor()

          @SingleIn(Scope1::class)
          @DependencyGraph(isExtendable = true)
          interface ParentGraph {
            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends grandParent: GrandParentGraph): ParentGraph
            }
          }

          @SingleIn(Scope2::class)
          @DependencyGraph(isExtendable = true)
          interface ParentGraph2 {
            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends other: OtherGrandParentGraph): ParentGraph2
            }
          }

          @DependencyGraph(Unit::class)
          interface ChildGraph {
            val string: String

            @DependencyGraph.Factory
            fun interface Factory {
              fun create(@Extends parent: ParentGraph, @Extends parent2: ParentGraph2): ChildGraph
            }
          }
        """
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: GrandParentGraph.kt:40:21 Graph extensions (@Extends) may not have multiple ancestors with the same scopes:
          Scope: @dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
          Ancestor 1: test.GrandParentGraph
          Ancestor 2: test.OtherGrandParentGraph
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
            @DependencyGraph(isExtendable = true)
            interface ParentGraph {
              @Provides fun provideInt(): Int = 1
              @Provides @Named("int") fun provideQualifiedInt(): Int = 2
              @Provides @SingleIn(AppScope::class) fun provideScopedLong(): Long = 3L
              @Provides @SingleIn(AppScope::class) @Named("long") fun provideScopedQualifiedLong(): Long = 4L
            }

            @DependencyGraph
            interface ChildGraph {
              val int: Int
              @Named("int") val qualifiedInt: Int
              val scopedLong: Long
              @Named("long") val qualifiedScopedLong: Long

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ParentGraph): ChildGraph
              }
            }
        """
      ),
      options = metroOptions.copy(enableStrictValidation = true)
    ) {
      val parentGraph = ParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)
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
            @DependencyGraph
            interface ChildGraph {
              val int: Int

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ParentGraph): ChildGraph
              }
            }

            @SingleIn(AppScope::class)
            @DependencyGraph(isExtendable = true)
            interface ParentGraph {
              @Provides fun provideInt(): Int = 1
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)
      assertThat(childGraph.callProperty<Int>("int")).isEqualTo(1)
    }
  }
}
