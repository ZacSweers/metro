// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.diagnostics

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.incremental.BaseIncrementalCompilationTest
import dev.zacsweers.metro.gradle.source
import org.junit.Test

class DiagnosticsTest : BaseIncrementalCompilationTest() {
  @Test
  fun providesSCCShouldNotCrashReporter() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(code)

        val code =
          source(
            """
            interface A
            interface B
            interface C

            @BindingContainer
            object MyModule {
                // If the reporter tries to link A -> C directly, it will crash.
                @Provides fun provideA(b: B): A = object : A {}
                @Provides fun provideB(a: A, c: Lazy<C>): B = object : B {}
                @Provides fun provideC(b: B): C = object : C {}
            }

            @DependencyGraph
            interface AppGraph {
                val a: A
                @DependencyGraph.Factory
                interface Factory {
                    fun create(@Includes module: MyModule): AppGraph
                }
            }
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject
    val result = project.compileKotlinAndFail()

    assertThat(result.output).contains("[Metro/DependencyCycle]")
    assertThat(result.output).contains("B --> A --> B")
  }
}
