// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import org.junit.Test

class InjectedClassTransformerTest : MetroCompilerTest() {

  // https://github.com/ZacSweers/metro/issues/935
  // Covers a use-case where we would previously get a Kotlin internal error that read:
  // e: kotlin.NotImplementedError: An operation is not implemented: Unknown file
  @Test
  fun `when no factory is found for a Java class, the exception should still specify it in the error message`() {
    val previousResult =
      compile(
        sourceJava(
          """
          public class ExampleClass {
            @Inject public ExampleClass() {

            }
          }
          """
            .trimIndent(),
        ),
      )
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val exampleClass: ExampleClass
        }
        """
          .trimIndent(),
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
      options = metroOptions.toBuilder().enableDaggerRuntimeInterop(true).build(),
      previousCompilationResult = previousResult,
    ) {
      assertDiagnostics(
        """
        e: [Metro/UnprocessedUpstreamDeclaration] Cannot use injected declaration `test.ExampleClass` because the upstream declaration was not processed by Metro.

        Run Metro's compiler for the upstream module. If Dagger owns that upstream declaration instead, run Dagger's compiler there.

        e: ExampleGraph.kt:8:7 [Metro/MissingBinding] No binding found for ExampleClass

          trace (in test.ExampleGraph):
              ExampleClass is requested at test.ExampleGraph.exampleClass

          help: ensure ExampleClass has an @Inject constructor or is provided by an @Provides or @Binds
                declaration visible to ExampleGraph
          docs: https://zacsweers.github.io/metro/latest/diagnostics/#missingbinding
        """
          .trimIndent(),
      )
    }
  }
}
