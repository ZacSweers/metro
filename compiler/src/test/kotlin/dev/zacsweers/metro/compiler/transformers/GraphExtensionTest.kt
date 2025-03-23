package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.compiler.ChildGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.ParentGraph
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphViaFactory
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedMetroGraphClass
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
    val firstCompilation = compile(
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
      previousCompilationResult = firstCompilation
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
      assertThat(childGraph.callProperty<Provider<Int>>("int")).isSameInstanceAs(childGraph.callProperty<Provider<Int>>("int"))
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

  // TODO test
  //  - multiple levels
  //    - three levels of parents
  //    - parent extends interface with provider in companion
  //  - multiple parent graphs
  //  - what happens if you pass a fake parent graph instance in
  //  - inherit binds
  //  - multibindings with mixed parents
  //  - aggregating contributions
}
