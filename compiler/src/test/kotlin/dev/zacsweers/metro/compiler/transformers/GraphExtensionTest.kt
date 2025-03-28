// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.compiler.ChildGraph
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.GrandParentGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.Parent1Graph
import dev.zacsweers.metro.compiler.Parent2Graph
import dev.zacsweers.metro.compiler.ParentGraph
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
                fun create(parent: ParentGraph): ChildGraph
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
                fun create(parent: ParentGraph): ChildGraph
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
                fun create(parent: ParentGraph): ChildGraph
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

            @SingleIn(AppScope::class)
            @DependencyGraph
            interface ChildGraph {
              val int: Int

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(parent: ParentGraph): ChildGraph
              }
            }
        """
      )
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

            @SingleIn(AppScope::class)
            @DependencyGraph
            interface ChildGraph {
              val int: Provider<Int>

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(parent: ParentGraph): ChildGraph
              }
            }
        """
      )
    ) {
      val parentGraph = ParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = ChildGraph.generatedMetroGraphClass().createGraphViaFactory(parentGraph)
      assertThat(childGraph.callProperty<Provider<Int>>("int"))
        .isSameInstanceAs(childGraph.callProperty<Provider<Int>>("int"))
    }
  }

  @Test
  fun `scopes are inherited - implicit`() {
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
                fun create(parent: ParentGraph): ChildGraph
              }
            }
        """
      )
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
                fun create(grandParent: GrandParentGraph): ParentGraph
              }

              @Provides fun provideInt(): Int = 1
            }

            @DependencyGraph
            interface ChildGraph {
              val string: String
              val int: Int

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(parent: ParentGraph): ChildGraph
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
                fun create(grandParent: GrandParentGraph): ParentGraph
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
                fun create(parent: ParentGraph): ChildGraph
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
                fun create(parent: ParentGraph): ChildGraph
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
                fun create(parent1: Parent1Graph, parent2: Parent2Graph): ChildGraph
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
                fun create(parent: ParentGraph): ChildGraph
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
                fun create(parent1: Parent1Graph, parent2: Parent2Graph): ChildGraph
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
                fun create(parent: ParentGraph): ChildGraph
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
              fun create(grandParent: GrandParentGraph): Parent1Graph
            }

            @Provides fun provideString(): String = "parent1"
          }

          @DependencyGraph(isExtendable = true)
          interface Parent2Graph {
            @DependencyGraph.Factory
            fun interface Factory {
              fun create(grandParent: GrandParentGraph): Parent2Graph
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
              fun create(parent1: Parent1Graph, parent2: Parent2Graph): ChildGraph
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
          @DependencyGraph
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
              fun create(parent: ParentGraph): ChildGraph
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
}
