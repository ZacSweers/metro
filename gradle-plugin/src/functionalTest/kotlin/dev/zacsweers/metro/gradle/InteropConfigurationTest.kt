// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("FunctionName")

package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.GradleBuilder.build
import org.junit.Test

class InteropConfigurationTest {
  @Test
  fun `includeDagger adds interop-dagger to classpath`() {
    val fixture =
      object : MetroProject() {
        override fun sources() =
          listOf(
            source(
              """
              @DependencyGraph
              interface AppGraph {
                val javaxValue: javax.inject.Provider<Int>
                val jakartaValue: jakarta.inject.Provider<Int>
                val daggerLazyValue: dagger.Lazy<Int>

                @Provides
                fun provideInt(): Int = 3
              }

              fun main() {
                val graph = createGraph<AppGraph>()
                check(graph.javaxValue.get() == 3)
                check(graph.jakartaValue.get() == 3)
                check(graph.daggerLazyValue.get() == 3)
              }
              """,
              "TestInterop",
            )
          )

        override fun StringBuilder.onBuildScript() {
          // language=kotlin
          appendLine(
            """
            metro {
              interop {
                includeDagger()
              }
            }
            """
              .trimIndent()
          )
        }
      }

    val project = fixture.gradleProject

    // Build should succeed with interop-dagger on classpath
    build(project.rootDir, "compileKotlin")
    // TODO invoke main()?
  }
}
