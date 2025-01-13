package dev.zacsweers.lattice.compiler.fir

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.lattice.compiler.ExampleGraph
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.allSupertypes
import kotlin.test.Test

class AggregationTest : LatticeCompilerTest() {
  @Test
  fun `contributing types are generated in fir`() {
    compile(
      source(
        """
          @ContributesTo(AppScope::class)
          interface ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph
        """
          .trimIndent()
      ),
    ) {
      val graph = ExampleGraph
      assertThat(graph.allSupertypes().map { it.name })
        .containsExactly("lattice.hints.TestContributedInterface", "test.ContributedInterface")
    }
  }

  @Test
  fun `contributing types are visible from another module`() {
    val firstResult = compile(
      source(
        """
          @ContributesTo(AppScope::class)
          interface ContributedInterface
        """
          .trimIndent()
      ),
    )

    compile(
      source(
        """
          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph
        """
          .trimIndent()
      ),
      previousCompilationResult = firstResult,
    ) {
      val graph = ExampleGraph
      assertThat(graph.allSupertypes().map { it.name })
        .containsExactly("lattice.hints.TestContributedInterface", "test.ContributedInterface")
    }
  }
}
