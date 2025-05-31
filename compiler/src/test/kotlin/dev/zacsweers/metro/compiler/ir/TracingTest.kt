// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.MetroCompilerTest
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
      val timings = reportsDir.resolve("timings.csv").readText()
      val withoutTime = timings.lines().drop(1).joinToString("\n") { it.substringBeforeLast(",") }
      assertThat(withoutTime)
        .isEqualTo(
          """
          ExampleGraph,Build DependencyGraphNode
          ExampleGraph,Implement creator functions
          ExampleGraph,Build binding graph
          ExampleGraph,Check self-cycles
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
          ExampleGraph,Transform metro graph
          ExampleGraph,Transform dependency graph
        """
            .trimIndent()
        )

      val traceLog = reportsDir.resolve("traceLog.txt").readText()
      val cleanedLog = traceLog.replace("\\((\\d+) ms\\)".toRegex(), "(xx ms)")
      assertThat(cleanedLog.trim())
        .isEqualTo(
          """
            [ExampleGraph] ▶ Transform dependency graph
              ▶ Build DependencyGraphNode
              ◀ Build DependencyGraphNode (xx ms)
              ▶ Implement creator functions
              ◀ Implement creator functions (xx ms)
              ▶ Build binding graph
              ◀ Build binding graph (xx ms)
              ▶ Validate binding graph
                ▶ Check self-cycles
                ◀ Check self-cycles (xx ms)
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
            [ExampleGraph] ◀ Transform dependency graph (xx ms)
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
            @DependencyGraph(AppScope::class, isExtendable = true)
            interface ExampleGraph {
              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Provides text: String): ExampleGraph
              }
            }

            @DependencyGraph(Unit::class)
            interface ChildGraph {

              fun exampleClass(): ExampleClass

              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Extends parent: ExampleGraph): ChildGraph
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
      val timings = reportsDir.resolve("timings.csv").readText()
      val withoutTime = timings.lines().drop(1).joinToString("\n") { it.substringBeforeLast(",") }
      assertThat(withoutTime)
        .isEqualTo(
          """
          ExampleGraph,Build DependencyGraphNode
          ExampleGraph,Implement creator functions
          ExampleGraph,Build binding graph
          ExampleGraph,Check self-cycles
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
          ExampleGraph,Transform dependency graph
          ChildGraph,Build DependencyGraphNode
          ChildGraph,Implement creator functions
          ChildGraph,Build binding graph
          ChildGraph,Check self-cycles
          ChildGraph,Populate bindings
          ChildGraph,Build adjacency list
          ChildGraph,Compute SCCs
          ChildGraph,Check for cycles
          ChildGraph,Build component DAG
          ChildGraph,Topo sort component DAG
          ChildGraph,Expand components
          ChildGraph,Topo sort
          ChildGraph,Sort and validate
          ChildGraph,Compute binding indices
          ChildGraph,seal graph
          ChildGraph,check empty multibindings
          ChildGraph,check for absent bindings
          ChildGraph,Validate graph
          ChildGraph,Validate binding graph
          ChildGraph,Collect bindings
          ChildGraph,Implement overrides
          ChildGraph,Transform metro graph
          ChildGraph,Transform dependency graph
        """
            .trimIndent()
        )

      val traceLog = reportsDir.resolve("traceLog.txt").readText()
      val cleanedLog = traceLog.replace("\\((\\d+) ms\\)".toRegex(), "(xx ms)")
      assertThat(cleanedLog.trim())
        .isEqualTo(
          """
            [ExampleGraph] ▶ Transform dependency graph
              ▶ Build DependencyGraphNode
              ◀ Build DependencyGraphNode (xx ms)
              ▶ Implement creator functions
              ◀ Implement creator functions (xx ms)
              ▶ Build binding graph
              ◀ Build binding graph (xx ms)
              ▶ Validate binding graph
                ▶ Check self-cycles
                ◀ Check self-cycles (xx ms)
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
            [ExampleGraph] ◀ Transform dependency graph (xx ms)
            [ChildGraph] ▶ Transform dependency graph
              ▶ Build DependencyGraphNode
              ◀ Build DependencyGraphNode (xx ms)
              ▶ Implement creator functions
              ◀ Implement creator functions (xx ms)
              ▶ Build binding graph
              ◀ Build binding graph (xx ms)
              ▶ Validate binding graph
                ▶ Check self-cycles
                ◀ Check self-cycles (xx ms)
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
            [ChildGraph] ◀ Transform dependency graph (xx ms)
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
            @DependencyGraph(AppScope::class, isExtendable = true)
            interface ExampleGraph {
              @DependencyGraph.Factory
              fun interface Factory {
                fun create(@Provides text: String): ExampleGraph
              }
            }

            @ContributesGraphExtension(Unit::class)
            interface ChildGraph {

              fun exampleClass(): ExampleClass

              @ContributesGraphExtension.Factory(AppScope::class)
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
      val timings = reportsDir.resolve("timings.csv").readText()
      val withoutTime = timings.lines().drop(1).joinToString("\n") { it.substringBeforeLast(",") }
      assertThat(withoutTime)
        .isEqualTo(
          $$$"""
          ExampleGraph,Build DependencyGraphNode
          ExampleGraph,Implement creator functions
          ExampleGraph,Build binding graph
          ExampleGraph,Check self-cycles
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
          ExampleGraph,Generate contributed graph ChildGraph
          ExampleGraph,Implement overrides
          ExampleGraph,Generate Metro metadata
          ExampleGraph,Transform metro graph
          ExampleGraph,Transform dependency graph
          $$ContributedChildGraph,Build DependencyGraphNode
          $$ContributedChildGraph,Implement creator functions
          $$ContributedChildGraph,Build binding graph
          $$ContributedChildGraph,Check self-cycles
          $$ContributedChildGraph,Populate bindings
          $$ContributedChildGraph,Build adjacency list
          $$ContributedChildGraph,Compute SCCs
          $$ContributedChildGraph,Check for cycles
          $$ContributedChildGraph,Build component DAG
          $$ContributedChildGraph,Topo sort component DAG
          $$ContributedChildGraph,Expand components
          $$ContributedChildGraph,Topo sort
          $$ContributedChildGraph,Sort and validate
          $$ContributedChildGraph,Compute binding indices
          $$ContributedChildGraph,seal graph
          $$ContributedChildGraph,check empty multibindings
          $$ContributedChildGraph,check for absent bindings
          $$ContributedChildGraph,Validate graph
          $$ContributedChildGraph,Validate binding graph
          $$ContributedChildGraph,Collect bindings
          $$ContributedChildGraph,Implement overrides
          $$ContributedChildGraph,Transform metro graph
          $$ContributedChildGraph,Transform dependency graph
        """
            .trimIndent()
        )

      val traceLog = reportsDir.resolve("traceLog.txt").readText()
      val cleanedLog = traceLog.replace("\\((\\d+) ms\\)".toRegex(), "(xx ms)")
      assertThat(cleanedLog.trim())
        .isEqualTo(
          $$$"""
            [ExampleGraph] ▶ Transform dependency graph
              ▶ Build DependencyGraphNode
              ◀ Build DependencyGraphNode (xx ms)
              ▶ Implement creator functions
              ◀ Implement creator functions (xx ms)
              ▶ Build binding graph
              ◀ Build binding graph (xx ms)
              ▶ Validate binding graph
                ▶ Check self-cycles
                ◀ Check self-cycles (xx ms)
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
                  ▶ Generate contributed graph ChildGraph
                  ◀ Generate contributed graph ChildGraph (xx ms)
                ◀ Implement overrides (xx ms)
                ▶ Generate Metro metadata
                ◀ Generate Metro metadata (xx ms)
              ◀ Transform metro graph (xx ms)
            [ExampleGraph] ◀ Transform dependency graph (xx ms)
            [$$ContributedChildGraph] ▶ Transform dependency graph
              ▶ Build DependencyGraphNode
              ◀ Build DependencyGraphNode (xx ms)
              ▶ Implement creator functions
              ◀ Implement creator functions (xx ms)
              ▶ Build binding graph
              ◀ Build binding graph (xx ms)
              ▶ Validate binding graph
                ▶ Check self-cycles
                ◀ Check self-cycles (xx ms)
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
            [$$ContributedChildGraph] ◀ Transform dependency graph (xx ms)
          """
            .trimIndent()
        )
    }
  }
}
