// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.tschuchort.compiletesting.KotlinCompilation
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import org.junit.Test

class GraphExtensionTest : MetroCompilerTest() {

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
        """,
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: GrandParentGraph.kt Graph extension 'test.ChildGraph' has overlapping scope annotations with ancestor graphs':
        - @dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class) (from ancestor 'test.GrandParentGraph')
        """
          .trimIndent(),
      )
    }
  }
}
