// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import org.junit.Ignore
import org.junit.Test

class DependencyGraphErrorsTest : MetroCompilerTest() {

  @Ignore("This isn't captured yet. May need to do it in the IR backend instead.")
  @Test
  fun `graph creators must return graphs - inherited invalid return type`() {
    val result =
      compile(
        source(
          """
          @DependencyGraph
          interface ExampleGraph {
            interface BaseFactory<T> {
              fun create(): T
            }
            @DependencyGraph.Factory
            interface Factory : BaseFactory<Nothing>
          }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertDiagnostics("sdaf")
  }
}
