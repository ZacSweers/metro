// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callFunction
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedImpl
import dev.zacsweers.metro.compiler.invokeMain
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import org.junit.Test

class AsContributionErrorsTest : MetroCompilerTest() {

  @Test
  fun `receiver must be a graph - regular graph - happy path`() {
    compile(
      source(
        fileNameWithoutExtension = "graphs",
        source =
          """
          @ContributesTo(AppScope::class)
          interface ContributedInterface
          @DependencyGraph(AppScope::class)
          interface AppGraph

          fun example(appGraph: AppGraph) {
            appGraph.asContribution<ContributedInterface>()
            val contributed = appGraph.asContribution<ContributedInterface>()
            val contributed2: ContributedInterface = appGraph.asContribution()
          }
          """
            .trimIndent(),
      )
    )
  }

  @Test
  fun `receiver must be a graph - useless casts`() {
    compile(
      source(
        fileNameWithoutExtension = "graphs",
        source =
          """
          @ContributesTo(AppScope::class)
          interface ContributedInterface
          @DependencyGraph(AppScope::class)
          interface AppGraph

          fun example(appGraph: AppGraph) {
            appGraph.asContribution<AppGraph>()
            val contributed = appGraph.asContribution<AppGraph>()
            val contributed2: AppGraph = appGraph.asContribution()
          }
          """
            .trimIndent(),
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: graphs.kt:12:27 `asContribution` type argument 'test.AppGraph' is the same as its receiver type. This is a useless cast.
        e: graphs.kt:13:45 `asContribution` type argument 'test.AppGraph' is the same as its receiver type. This is a useless cast.
        e: graphs.kt:14:41 `asContribution` type argument 'test.AppGraph' is the same as its receiver type. This is a useless cast.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `receiver must be a graph - regular graph - happy path - indirect`() {
    compile(
      source(
        fileNameWithoutExtension = "graphs",
        source =
          """
          interface Base
          @ContributesTo(AppScope::class)
          interface ContributedInterface : Base
          @DependencyGraph(AppScope::class)
          interface AppGraph

          fun example(appGraph: AppGraph) {
            appGraph.asContribution<Base>()
            val contributed = appGraph.asContribution<Base>()
            val contributed2: Base = appGraph.asContribution()
          }
          """
            .trimIndent(),
      )
    )
  }

  @Test
  fun `receiver must be a graph - regular graph - happy path - generic`() {
    compile(
      source(
        fileNameWithoutExtension = "graphs",
        source =
          """
          interface Base<T>
          @ContributesTo(AppScope::class)
          interface ContributedInterface : Base<String>
          @DependencyGraph(AppScope::class)
          interface AppGraph

          fun example(appGraph: AppGraph) {
            appGraph.asContribution<Base<String>>()
            val contributed = appGraph.asContribution<Base<String>>()
            val contributed2: Base<String> = appGraph.asContribution()
          }
          """
            .trimIndent(),
      )
    )
  }

  @Test
  fun `receiver must be a graph - regular graph - error - generic`() {
    compile(
      source(
        fileNameWithoutExtension = "graphs",
        source =
          """
          interface Base<T>
          @ContributesTo(AppScope::class)
          interface ContributedInterface : Base<String>
          @DependencyGraph(AppScope::class)
          interface AppGraph

          fun example(appGraph: AppGraph) {
            appGraph.asContribution<Base<Int>>()
            val contributed = appGraph.asContribution<Base<Int>>()
            val contributed2: Base<Int> = appGraph.asContribution()
          }
          """
            .trimIndent(),
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: graphs.kt:13:27 `asContribution` type argument 'test.Base' is not a merged supertype of test.AppGraph.
        e: graphs.kt:14:45 `asContribution` type argument 'test.Base' is not a merged supertype of test.AppGraph.
        e: graphs.kt:15:42 `asContribution` type argument 'test.Base' is not a merged supertype of test.AppGraph.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `receiver must be a graph - regular graph - errors`() {
    compile(
      source(
        fileNameWithoutExtension = "graphs",
        source =
          """
          @ContributesTo(AppScope::class)
          interface ContributedInterface
          interface AppGraph

          fun example(appGraph: AppGraph) {
            appGraph.asContribution<ContributedInterface>()
            val contributed = appGraph.asContribution<ContributedInterface>()
            val contributed2: ContributedInterface = appGraph.asContribution()
          }
          """
            .trimIndent(),
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: graphs.kt:11:3 `asContribution` receiver must be annotated with a `@DependencyGraph` annotation.
        e: graphs.kt:12:21 `asContribution` receiver must be annotated with a `@DependencyGraph` annotation.
        e: graphs.kt:13:44 `asContribution` receiver must be annotated with a `@DependencyGraph` annotation.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `complex example`() {
    compile(
      source(
        fileNameWithoutExtension = "main",
        source =
          """
          @GraphExtension(Unit::class)
          interface UnitGraph {
            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
              fun createUnitGraph(): UnitGraph
            }
          }

          @DependencyGraph(AppScope::class)
          interface ExampleGraph

          fun main(exampleGraph: ExampleGraph): UnitGraph.Factory {
            return exampleGraph.asContribution<UnitGraph.Factory>()
          }
          """
            .trimIndent(),
      )
    ) {
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val unitGraphFactory = invokeMain<Any>(exampleGraph)
      assertSame(exampleGraph, unitGraphFactory)
      val unitGraph = unitGraphFactory.callFunction<Any>("createUnitGraph")
      assertNotNull(unitGraph)
    }
  }
}
