// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.MetroCompilerTest
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import org.junit.Test

class TracingTest : MetroCompilerTest() {

  @Test
  fun `simple graph`() {
    val reportsDir = temporaryFolder.newFolder("reports").toPath()
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
        """
          .trimIndent()
      ),
      options = metroOptions.copy(reportsDestination = reportsDir),
    ) {
      val timings = reportsDir.listDirectoryEntries().single().resolve("timings.csv").readText()
      val withoutTime = timings.lines().drop(1).joinToString("\n") { it.substringBeforeLast(",") }
      assertThat(withoutTime)
        .isEqualTo(
          """
          main,Transform contributions
          ExampleGraph,Build DependencyGraphNode
          ExampleGraph,Implement creator functions
          ExampleGraph,Build binding graph
          ExampleGraph,Populate bindings
          ExampleGraph,Build adjacency list
          ExampleGraph,Compute SCCs
          ExampleGraph,Check for cycles
          ExampleGraph,Build component DAG
          ExampleGraph,Topo sort component DAG
          ExampleGraph,Expand components
          ExampleGraph,Topo sort
          ExampleGraph,Sort and validate
          ExampleGraph,Compute binding indices
          ExampleGraph,seal graph
          ExampleGraph,check empty multibindings
          ExampleGraph,check for absent bindings
          ExampleGraph,Validate graph
          ExampleGraph,Validate binding graph
          ExampleGraph,Collect bindings
          ExampleGraph,Implement overrides
          ExampleGraph,Generate Metro metadata
          ExampleGraph,Transform metro graph
          ExampleGraph,[ExampleGraph] Transform dependency graph
          main,Core transformers
          main,Metro compiler
          """
            .trimIndent()
        )

      val traceLog = reportsDir.listDirectoryEntries().single().resolve("traceLog.txt").readText()
      val cleanedLog = traceLog.replace("\\((\\d+) ms\\)".toRegex(), "(xx ms)")
      assertThat(cleanedLog.trim())
        .isEqualTo(
          """
          [main] ▶ Metro compiler
            ▶ Transform contributions
            ◀ Transform contributions (xx ms)
            ▶ Core transformers
              ▶ [ExampleGraph] Transform dependency graph
                ▶ Build DependencyGraphNode
                ◀ Build DependencyGraphNode (xx ms)
                ▶ Implement creator functions
                ◀ Implement creator functions (xx ms)
                ▶ Build binding graph
                ◀ Build binding graph (xx ms)
                ▶ Validate binding graph
                  ▶ Validate graph
                    ▶ seal graph
                      ▶ Populate bindings
                      ◀ Populate bindings (xx ms)
                      ▶ Build adjacency list
                      ◀ Build adjacency list (xx ms)
                      ▶ Sort and validate
                        ▶ Topo sort
                          ▶ Compute SCCs
                          ◀ Compute SCCs (xx ms)
                          ▶ Check for cycles
                          ◀ Check for cycles (xx ms)
                          ▶ Build component DAG
                          ◀ Build component DAG (xx ms)
                          ▶ Topo sort component DAG
                          ◀ Topo sort component DAG (xx ms)
                          ▶ Expand components
                          ◀ Expand components (xx ms)
                        ◀ Topo sort (xx ms)
                      ◀ Sort and validate (xx ms)
                      ▶ Compute binding indices
                      ◀ Compute binding indices (xx ms)
                    ◀ seal graph (xx ms)
                    ▶ check empty multibindings
                    ◀ check empty multibindings (xx ms)
                    ▶ check for absent bindings
                    ◀ check for absent bindings (xx ms)
                  ◀ Validate graph (xx ms)
                ◀ Validate binding graph (xx ms)
                ▶ Transform metro graph
                  ▶ Collect bindings
                  ◀ Collect bindings (xx ms)
                  ▶ Implement overrides
                  ◀ Implement overrides (xx ms)
                  ▶ Generate Metro metadata
                  ◀ Generate Metro metadata (xx ms)
                ◀ Transform metro graph (xx ms)
              ◀ [ExampleGraph] Transform dependency graph (xx ms)
            ◀ Core transformers (xx ms)
          [main] ◀ Metro compiler (xx ms)
          """
            .trimIndent()
        )
    }
  }

  @Test
  fun `graph extensions nest`() {
    val reportsDir = temporaryFolder.newFolder("reports").toPath()
    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph : ChildGraph.Factory {
          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides text: String): ExampleGraph
          }
        }

        @GraphExtension(Unit::class)
        interface ChildGraph {

          fun exampleClass(): ExampleClass

          @GraphExtension.Factory
          fun interface Factory {
            fun create(): ChildGraph
          }
        }

        @Inject
        class ExampleClass(private val text: String) : Callable<String> {
          override fun call(): String = text
        }
        """
          .trimIndent()
      ),
      options = metroOptions.copy(reportsDestination = reportsDir),
    ) {
      val timings = reportsDir.listDirectoryEntries().single().resolve("timings.csv").readText()
      val withoutTime = timings.lines().drop(1).joinToString("\n") { it.substringBeforeLast(",") }
      assertThat(withoutTime)
        .isEqualTo(
          """
          main,Transform contributions
          ExampleGraph,Build DependencyGraphNode
          ExampleGraph,Implement creator functions
          ExampleGraph,Build binding graph
          ExampleGraph,Generate graph extension ChildGraph
          ChildGraphImpl,Build DependencyGraphNode
          ChildGraphImpl,Implement creator functions
          ChildGraphImpl,Build binding graph
          ChildGraphImpl,Populate bindings
          ChildGraphImpl,Build adjacency list
          ChildGraphImpl,Compute SCCs
          ChildGraphImpl,Check for cycles
          ChildGraphImpl,Build component DAG
          ChildGraphImpl,Topo sort component DAG
          ChildGraphImpl,Expand components
          ChildGraphImpl,Topo sort
          ChildGraphImpl,Sort and validate
          ChildGraphImpl,Compute binding indices
          ChildGraphImpl,seal graph
          ChildGraphImpl,check empty multibindings
          ChildGraphImpl,check for absent bindings
          ChildGraphImpl,Validate graph
          ChildGraphImpl,Validate binding graph
          ChildGraphImpl,Collect bindings
          ChildGraphImpl,Implement overrides
          ChildGraphImpl,Transform metro graph
          ChildGraphImpl,[ChildGraphImpl] Transform dependency graph
          ExampleGraph,Populate bindings
          ExampleGraph,Build adjacency list
          ExampleGraph,Compute SCCs
          ExampleGraph,Check for cycles
          ExampleGraph,Build component DAG
          ExampleGraph,Topo sort component DAG
          ExampleGraph,Expand components
          ExampleGraph,Topo sort
          ExampleGraph,Sort and validate
          ExampleGraph,Compute binding indices
          ExampleGraph,seal graph
          ExampleGraph,check empty multibindings
          ExampleGraph,check for absent bindings
          ExampleGraph,Validate graph
          ExampleGraph,Validate binding graph
          ExampleGraph,Collect bindings
          ExampleGraph,Implement overrides
          ExampleGraph,Generate Metro metadata
          ExampleGraph,Transform metro graph
          ExampleGraph,[ExampleGraph] Transform dependency graph
          main,Core transformers
          main,Metro compiler
          """
            .trimIndent()
        )

      val traceLog = reportsDir.listDirectoryEntries().single().resolve("traceLog.txt").readText()
      val cleanedLog = traceLog.replace("\\((\\d+) ms\\)".toRegex(), "(xx ms)")
      assertThat(cleanedLog.trim())
        .isEqualTo(
          """
          [main] ▶ Metro compiler
            ▶ Transform contributions
            ◀ Transform contributions (xx ms)
            ▶ Core transformers
              ▶ [ExampleGraph] Transform dependency graph
                ▶ Build DependencyGraphNode
                ◀ Build DependencyGraphNode (xx ms)
                ▶ Implement creator functions
                ◀ Implement creator functions (xx ms)
                ▶ Build binding graph
                ◀ Build binding graph (xx ms)
                ▶ Generate graph extension ChildGraph
                ◀ Generate graph extension ChildGraph (xx ms)
              ▶ [ChildGraphImpl] Transform dependency graph
                ▶ Build DependencyGraphNode
                ◀ Build DependencyGraphNode (xx ms)
                ▶ Implement creator functions
                ◀ Implement creator functions (xx ms)
                ▶ Build binding graph
                ◀ Build binding graph (xx ms)
                ▶ Validate binding graph
                  ▶ Validate graph
                    ▶ seal graph
                      ▶ Populate bindings
                      ◀ Populate bindings (xx ms)
                      ▶ Build adjacency list
                      ◀ Build adjacency list (xx ms)
                      ▶ Sort and validate
                        ▶ Topo sort
                          ▶ Compute SCCs
                          ◀ Compute SCCs (xx ms)
                          ▶ Check for cycles
                          ◀ Check for cycles (xx ms)
                          ▶ Build component DAG
                          ◀ Build component DAG (xx ms)
                          ▶ Topo sort component DAG
                          ◀ Topo sort component DAG (xx ms)
                          ▶ Expand components
                          ◀ Expand components (xx ms)
                        ◀ Topo sort (xx ms)
                      ◀ Sort and validate (xx ms)
                      ▶ Compute binding indices
                      ◀ Compute binding indices (xx ms)
                    ◀ seal graph (xx ms)
                    ▶ check empty multibindings
                    ◀ check empty multibindings (xx ms)
                    ▶ check for absent bindings
                    ◀ check for absent bindings (xx ms)
                  ◀ Validate graph (xx ms)
                ◀ Validate binding graph (xx ms)
                ▶ Transform metro graph
                  ▶ Collect bindings
                  ◀ Collect bindings (xx ms)
                  ▶ Implement overrides
                  ◀ Implement overrides (xx ms)
                ◀ Transform metro graph (xx ms)
              ◀ [ChildGraphImpl] Transform dependency graph (xx ms)
                ▶ Validate binding graph
                  ▶ Validate graph
                    ▶ seal graph
                      ▶ Populate bindings
                      ◀ Populate bindings (xx ms)
                      ▶ Build adjacency list
                      ◀ Build adjacency list (xx ms)
                      ▶ Sort and validate
                        ▶ Topo sort
                          ▶ Compute SCCs
                          ◀ Compute SCCs (xx ms)
                          ▶ Check for cycles
                          ◀ Check for cycles (xx ms)
                          ▶ Build component DAG
                          ◀ Build component DAG (xx ms)
                          ▶ Topo sort component DAG
                          ◀ Topo sort component DAG (xx ms)
                          ▶ Expand components
                          ◀ Expand components (xx ms)
                        ◀ Topo sort (xx ms)
                      ◀ Sort and validate (xx ms)
                      ▶ Compute binding indices
                      ◀ Compute binding indices (xx ms)
                    ◀ seal graph (xx ms)
                    ▶ check empty multibindings
                    ◀ check empty multibindings (xx ms)
                    ▶ check for absent bindings
                    ◀ check for absent bindings (xx ms)
                  ◀ Validate graph (xx ms)
                ◀ Validate binding graph (xx ms)
                ▶ Transform metro graph
                  ▶ Collect bindings
                  ◀ Collect bindings (xx ms)
                  ▶ Implement overrides
                  ◀ Implement overrides (xx ms)
                  ▶ Generate Metro metadata
                  ◀ Generate Metro metadata (xx ms)
                ◀ Transform metro graph (xx ms)
              ◀ [ExampleGraph] Transform dependency graph (xx ms)
            ◀ Core transformers (xx ms)
          [main] ◀ Metro compiler (xx ms)
          """
            .trimIndent()
        )
    }
  }

  @Test
  fun `contributed extension`() {
    val reportsDir = temporaryFolder.newFolder("reports").toPath()
    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph {
          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides text: String): ExampleGraph
          }
        }

        @GraphExtension(Unit::class)
        interface ChildGraph {

          fun exampleClass(): ExampleClass

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          fun interface Factory {
            fun createChildGraph(): ChildGraph
          }
        }

        @Inject
        class ExampleClass(private val text: String) : Callable<String> {
          override fun call(): String = text
        }
        """
          .trimIndent()
      ),
      options = metroOptions.copy(reportsDestination = reportsDir),
    ) {
      val timings = reportsDir.listDirectoryEntries().single().resolve("timings.csv").readText()
      val withoutTime = timings.lines().drop(1).joinToString("\n") { it.substringBeforeLast(",") }
      assertThat(withoutTime)
        .isEqualTo(
          """
          main,Transform contributions
          ExampleGraph,Build DependencyGraphNode
          ExampleGraph,Implement creator functions
          ExampleGraph,Build binding graph
          ExampleGraph,Generate graph extension ChildGraph
          ChildGraphImpl,Build DependencyGraphNode
          ChildGraphImpl,Implement creator functions
          ChildGraphImpl,Build binding graph
          ChildGraphImpl,Populate bindings
          ChildGraphImpl,Build adjacency list
          ChildGraphImpl,Compute SCCs
          ChildGraphImpl,Check for cycles
          ChildGraphImpl,Build component DAG
          ChildGraphImpl,Topo sort component DAG
          ChildGraphImpl,Expand components
          ChildGraphImpl,Topo sort
          ChildGraphImpl,Sort and validate
          ChildGraphImpl,Compute binding indices
          ChildGraphImpl,seal graph
          ChildGraphImpl,check empty multibindings
          ChildGraphImpl,check for absent bindings
          ChildGraphImpl,Validate graph
          ChildGraphImpl,Validate binding graph
          ChildGraphImpl,Collect bindings
          ChildGraphImpl,Implement overrides
          ChildGraphImpl,Transform metro graph
          ChildGraphImpl,[ChildGraphImpl] Transform dependency graph
          ExampleGraph,Populate bindings
          ExampleGraph,Build adjacency list
          ExampleGraph,Compute SCCs
          ExampleGraph,Check for cycles
          ExampleGraph,Build component DAG
          ExampleGraph,Topo sort component DAG
          ExampleGraph,Expand components
          ExampleGraph,Topo sort
          ExampleGraph,Sort and validate
          ExampleGraph,Compute binding indices
          ExampleGraph,seal graph
          ExampleGraph,check empty multibindings
          ExampleGraph,check for absent bindings
          ExampleGraph,Validate graph
          ExampleGraph,Validate binding graph
          ExampleGraph,Collect bindings
          ExampleGraph,Implement overrides
          ExampleGraph,Generate Metro metadata
          ExampleGraph,Transform metro graph
          ExampleGraph,[ExampleGraph] Transform dependency graph
          main,Core transformers
          main,Metro compiler
          """
            .trimIndent()
        )

      val traceLog = reportsDir.listDirectoryEntries().single().resolve("traceLog.txt").readText()
      val cleanedLog = traceLog.replace("\\((\\d+) ms\\)".toRegex(), "(xx ms)")
      assertThat(cleanedLog.trim())
        .isEqualTo(
          """
          [main] ▶ Metro compiler
            ▶ Transform contributions
            ◀ Transform contributions (xx ms)
            ▶ Core transformers
              ▶ [ExampleGraph] Transform dependency graph
                ▶ Build DependencyGraphNode
                ◀ Build DependencyGraphNode (xx ms)
                ▶ Implement creator functions
                ◀ Implement creator functions (xx ms)
                ▶ Build binding graph
                ◀ Build binding graph (xx ms)
                ▶ Generate graph extension ChildGraph
                ◀ Generate graph extension ChildGraph (xx ms)
              ▶ [ChildGraphImpl] Transform dependency graph
                ▶ Build DependencyGraphNode
                ◀ Build DependencyGraphNode (xx ms)
                ▶ Implement creator functions
                ◀ Implement creator functions (xx ms)
                ▶ Build binding graph
                ◀ Build binding graph (xx ms)
                ▶ Validate binding graph
                  ▶ Validate graph
                    ▶ seal graph
                      ▶ Populate bindings
                      ◀ Populate bindings (xx ms)
                      ▶ Build adjacency list
                      ◀ Build adjacency list (xx ms)
                      ▶ Sort and validate
                        ▶ Topo sort
                          ▶ Compute SCCs
                          ◀ Compute SCCs (xx ms)
                          ▶ Check for cycles
                          ◀ Check for cycles (xx ms)
                          ▶ Build component DAG
                          ◀ Build component DAG (xx ms)
                          ▶ Topo sort component DAG
                          ◀ Topo sort component DAG (xx ms)
                          ▶ Expand components
                          ◀ Expand components (xx ms)
                        ◀ Topo sort (xx ms)
                      ◀ Sort and validate (xx ms)
                      ▶ Compute binding indices
                      ◀ Compute binding indices (xx ms)
                    ◀ seal graph (xx ms)
                    ▶ check empty multibindings
                    ◀ check empty multibindings (xx ms)
                    ▶ check for absent bindings
                    ◀ check for absent bindings (xx ms)
                  ◀ Validate graph (xx ms)
                ◀ Validate binding graph (xx ms)
                ▶ Transform metro graph
                  ▶ Collect bindings
                  ◀ Collect bindings (xx ms)
                  ▶ Implement overrides
                  ◀ Implement overrides (xx ms)
                ◀ Transform metro graph (xx ms)
              ◀ [ChildGraphImpl] Transform dependency graph (xx ms)
                ▶ Validate binding graph
                  ▶ Validate graph
                    ▶ seal graph
                      ▶ Populate bindings
                      ◀ Populate bindings (xx ms)
                      ▶ Build adjacency list
                      ◀ Build adjacency list (xx ms)
                      ▶ Sort and validate
                        ▶ Topo sort
                          ▶ Compute SCCs
                          ◀ Compute SCCs (xx ms)
                          ▶ Check for cycles
                          ◀ Check for cycles (xx ms)
                          ▶ Build component DAG
                          ◀ Build component DAG (xx ms)
                          ▶ Topo sort component DAG
                          ◀ Topo sort component DAG (xx ms)
                          ▶ Expand components
                          ◀ Expand components (xx ms)
                        ◀ Topo sort (xx ms)
                      ◀ Sort and validate (xx ms)
                      ▶ Compute binding indices
                      ◀ Compute binding indices (xx ms)
                    ◀ seal graph (xx ms)
                    ▶ check empty multibindings
                    ◀ check empty multibindings (xx ms)
                    ▶ check for absent bindings
                    ◀ check for absent bindings (xx ms)
                  ◀ Validate graph (xx ms)
                ◀ Validate binding graph (xx ms)
                ▶ Transform metro graph
                  ▶ Collect bindings
                  ◀ Collect bindings (xx ms)
                  ▶ Implement overrides
                  ◀ Implement overrides (xx ms)
                  ▶ Generate Metro metadata
                  ◀ Generate Metro metadata (xx ms)
                ◀ Transform metro graph (xx ms)
              ◀ [ExampleGraph] Transform dependency graph (xx ms)
            ◀ Core transformers (xx ms)
          [main] ◀ Metro compiler (xx ms)
          """
            .trimIndent()
        )
    }
  }
}
