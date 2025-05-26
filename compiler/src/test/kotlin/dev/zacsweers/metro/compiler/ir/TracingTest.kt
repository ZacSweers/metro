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
          ExampleGraph,Compute SSCs
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
                    ▶ Sort and validate
                      ▶ Build adjacency list
                      ◀ Build adjacency list (xx ms)
                      ▶ Topo sort
                        ▶ Compute SSCs
                        ◀ Compute SSCs (xx ms)
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
          ExampleGraph,Compute SSCs
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
          ChildGraph,Compute SSCs
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
                    ▶ Sort and validate
                      ▶ Build adjacency list
                      ◀ Build adjacency list (xx ms)
                      ▶ Topo sort
                        ▶ Compute SSCs
                        ◀ Compute SSCs (xx ms)
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
                    ▶ Sort and validate
                      ▶ Build adjacency list
                      ◀ Build adjacency list (xx ms)
                      ▶ Topo sort
                        ▶ Compute SSCs
                        ◀ Compute SSCs (xx ms)
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
          """
          ExampleGraph,Build DependencyGraphNode
          ExampleGraph,Implement creator functions
          ExampleGraph,Build binding graph
          ExampleGraph,Check self-cycles
          ExampleGraph,Populate bindings
          ExampleGraph,Build adjacency list
          ExampleGraph,Compute SSCs
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
          $${'$'}ContributedTestChildGraph,Build DependencyGraphNode
          $${'$'}ContributedTestChildGraph,Implement creator functions
          $${'$'}ContributedTestChildGraph,Build binding graph
          $${'$'}ContributedTestChildGraph,Check self-cycles
          $${'$'}ContributedTestChildGraph,Populate bindings
          $${'$'}ContributedTestChildGraph,Build adjacency list
          $${'$'}ContributedTestChildGraph,Compute SSCs
          $${'$'}ContributedTestChildGraph,Check for cycles
          $${'$'}ContributedTestChildGraph,Build component DAG
          $${'$'}ContributedTestChildGraph,Topo sort component DAG
          $${'$'}ContributedTestChildGraph,Expand components
          $${'$'}ContributedTestChildGraph,Topo sort
          $${'$'}ContributedTestChildGraph,Sort and validate
          $${'$'}ContributedTestChildGraph,Compute binding indices
          $${'$'}ContributedTestChildGraph,seal graph
          $${'$'}ContributedTestChildGraph,check empty multibindings
          $${'$'}ContributedTestChildGraph,check for absent bindings
          $${'$'}ContributedTestChildGraph,Validate graph
          $${'$'}ContributedTestChildGraph,Validate binding graph
          $${'$'}ContributedTestChildGraph,Collect bindings
          $${'$'}ContributedTestChildGraph,Implement overrides
          $${'$'}ContributedTestChildGraph,Transform metro graph
          $${'$'}ContributedTestChildGraph,Transform dependency graph
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
                    ▶ Sort and validate
                      ▶ Build adjacency list
                      ◀ Build adjacency list (xx ms)
                      ▶ Topo sort
                        ▶ Compute SSCs
                        ◀ Compute SSCs (xx ms)
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
            [$${'$'}ContributedTestChildGraph] ▶ Transform dependency graph
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
                    ▶ Sort and validate
                      ▶ Build adjacency list
                      ◀ Build adjacency list (xx ms)
                      ▶ Topo sort
                        ▶ Compute SSCs
                        ◀ Compute SSCs (xx ms)
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
            [$${'$'}ContributedTestChildGraph] ◀ Transform dependency graph (xx ms)
          """
            .trimIndent()
        )
    }
  }
}
