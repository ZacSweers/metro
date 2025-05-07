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
      options = metroOptions.copy(reportsDestination = reportsDir, debug = true),
    ) {
      val timings = reportsDir.resolve("timings.csv").readText()
      // tag,description,durationMs
      // test.ExampleGraph,Build DependencyGraphNode,15
      // test.ExampleGraph,Implement creator functions,0
      // test.ExampleGraph,Build binding graph,1
      // test.ExampleGraph,-- Validate binding graph,4
      // test.ExampleGraph,Transform metro graph,10
      // test.ExampleGraph,Transform dependency graph,46
      val withoutTime = timings.lines().drop(1).joinToString("\n") { it.substringBeforeLast(",") }
      assertThat(withoutTime)
        .isEqualTo(
          """
          ExampleGraph,Build DependencyGraphNode
          ExampleGraph,Implement creator functions
          ExampleGraph,Build binding graph
          ExampleGraph,Validate binding graph
          ExampleGraph,Collect bindings
          ExampleGraph,Compute safe init order
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
              ◀ Validate binding graph (xx ms)
              ▶ Transform metro graph
                ▶ Collect bindings
                ◀ Collect bindings (xx ms)
                ▶ Compute safe init order
                ◀ Compute safe init order (xx ms)
                ▶ Implement overrides
                ◀ Implement overrides (xx ms)
              ◀ Transform metro graph (xx ms)
            [ExampleGraph] ◀ Transform dependency graph (xx ms)
          """
            .trimIndent()
        )
    }
  }
}
