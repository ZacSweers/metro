// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedMetroGraphClass
import kotlin.test.Test
import org.jetbrains.kotlin.name.ClassId

class AnvilInteropTest : MetroCompilerTest() {

  @Test
  fun `a library binding can outrank an upstream binding`() {
    val previousCompilation =
      compile(
        source(
          """
          interface ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 100)
          object LibImpl : ContributedInterface
        """
            .trimIndent()
        ),
        options = metroOptions.withAnvilContributesBinding(),
      )

    compile(
      source(
        """
          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class)
          object AppImpl : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            val contributedInterface: ContributedInterface
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = previousCompilation,
      options = metroOptions.withAnvilContributesBinding(),
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.LibImpl")
    }
  }

  @Test
  fun `an upstream binding can outrank a library binding`() {
    val libCompilation =
      compile(
        source(
          """
          interface ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 1)
          object LibImpl : ContributedInterface
        """
            .trimIndent()
        ),
        options = metroOptions.withAnvilContributesBinding(),
      )

    compile(
      source(
        """
          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 100)
          object AppImpl : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            val contributedInterface: ContributedInterface
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = libCompilation,
      options = metroOptions.withAnvilContributesBinding(),
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.AppImpl")
    }
  }

  @Test
  fun `the binding with the highest rank is used when there are more than two`() {
    compile(
      source(
        """
          interface ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class)
          object Impl1 : ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 10)
          object Impl2 : ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 100)
          object Impl3 : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            val contributedInterface: ContributedInterface
          }
        """
          .trimIndent()
      ),
      options = metroOptions.withAnvilContributesBinding(),
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl3")
    }
  }

  @Test
  fun `rank requires an explicit binding type`() {
    compile(
      source(
        """
          interface ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 10)
          object Impl1 : ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 100)
          object Impl2 : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            val contributedInterface: ContributedInterface
          }
        """
          .trimIndent()
      ),
      options = metroOptions.withAnvilContributesBinding(),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:11:1 `@ContributesBinding`-annotated class test.Impl2 sets a rank but doesn't declare its binding type.
          Bindings with non-default ranks must declare explicit binding types. This is because
          we're not able to resolve supertypes to get the implicit binding type when
          processing ranked contributions.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `an outranked binding requires an explicit binding type`() {
    compile(
      source(
        """
          interface ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 10)
          object Impl1 : ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class)
          object Impl2 : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            val contributedInterface: ContributedInterface
          }
        """
          .trimIndent()
      ),
      options = metroOptions.withAnvilContributesBinding(),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      // Duplicate bindings are reported in IR where we no longer have info about 'rank', so it's
      // expected that this use-case results in a generic duplicate binding error. We could update
      // to plumb the rank info down so it's not lost but as a feature that's only supported for
      // interop and making migration easier, it seems better to minimize its code-level exposure.
      assertDiagnostics(
        """
          e: ContributedInterface.kt:14:1 [Metro/DuplicateBinding] Duplicate binding for test.ContributedInterface
          ├─ Binding 1: ContributedInterface.kt:8:1
          ├─ Binding 2: ContributedInterface.kt:11:1
        """
          .trimIndent()
      )
    }
  }

  // Covers the use of Metro's [Qualifier] annotation and a Metro-defined qualifier
  @Test
  fun `rank respects bundled qualifiers`() {
    compile(
      source(
        """
          interface ContributedInterface

          @Named("Bob")
          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class)
          object Impl1 : ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 100)
          object Impl2 : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            val contributedInterface: ContributedInterface

            @Named("Bob")
            val namedContributedInterface: ContributedInterface
          }
        """
          .trimIndent()
      ),
      options = metroOptions.withAnvilContributesBinding(),
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl2")

      val namedContributedInterface = graph.callProperty<Any>("namedContributedInterface")
      assertThat(namedContributedInterface).isNotNull()
      assertThat(namedContributedInterface.javaClass.name).isEqualTo("test.Impl1")
    }
  }

  @Test
  fun `rank respects third-party qualifiers`() {
    val previousCompilation =
      compile(
        source(
          """
            @Target(AnnotationTarget.ANNOTATION_CLASS)
            annotation class ThirdPartyQualifier

            @Target(
              AnnotationTarget.CLASS,
              AnnotationTarget.PROPERTY,
            )
            @ThirdPartyQualifier
            annotation class CompanyFeature
          """
            .trimIndent()
        ),
        options = metroOptions.withAnvilContributesBinding(),
      )

    compile(
      source(
        """
          interface ContributedInterface

          @CompanyFeature
          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class)
          object Impl1 : ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 100)
          object Impl2 : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            val contributedInterface: ContributedInterface

            @CompanyFeature
            val qualifiedContributedInterface: ContributedInterface
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = previousCompilation,
      options =
        metroOptions
          .withAnvilContributesBinding()
          .copy(customQualifierAnnotations = setOf(ClassId.fromString("test/ThirdPartyQualifier"))),
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl2")

      val qualifiedContributedInterface = graph.callProperty<Any>("qualifiedContributedInterface")
      assertThat(qualifiedContributedInterface).isNotNull()
      assertThat(qualifiedContributedInterface.javaClass.name).isEqualTo("test.Impl1")
    }
  }

  @Test
  fun `a lower ranked binding can use 'replaces' to replace a higher ranked binding`() {
    compile(
      source(
        """
          interface ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, replaces = [Impl2::class], rank = 10)
          object Impl1 : ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 100)
          object Impl2 : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            val contributedInterface: ContributedInterface
          }
        """
          .trimIndent()
      ),
      options = metroOptions.withAnvilContributesBinding(),
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl1")
    }
  }

  @Test
  fun `bindings with the same rank are treated like normal duplicate bindings`() {
    compile(
      source(
        """
          interface ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 100)
          object Impl1 : ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class)
          object Impl2 : ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 100)
          object Impl3 : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            val contributedInterface: ContributedInterface
          }
        """
          .trimIndent()
      ),
      options = metroOptions.withAnvilContributesBinding(),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:17:1 [Metro/DuplicateBinding] Duplicate binding for test.ContributedInterface
          ├─ Binding 1: ContributedInterface.kt:8:1
          ├─ Binding 2: ContributedInterface.kt:14:1
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `rank is ignored when dagger anvil interop is not enabled`() {
    compile(
      source(
        """
          interface ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class)
          object Impl1 : ContributedInterface

          @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, boundType = ContributedInterface::class, rank = 10)
          object Impl2 : ContributedInterface

          @DependencyGraph(scope = AppScope::class)
          interface ExampleGraph {
            val contributedInterface: ContributedInterface
          }
        """
          .trimIndent()
      ),
      options =
        metroOptions.copy(
          customContributesBindingAnnotations =
            setOf(ClassId.fromString("com/squareup/anvil/annotations/ContributesBinding")),
          enableDaggerAnvilInterop = false,
        ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: ContributedInterface.kt:14:1 [Metro/DuplicateBinding] Duplicate binding for test.ContributedInterface
          ├─ Binding 1: ContributedInterface.kt:8:1
          ├─ Binding 2: ContributedInterface.kt:11:1
        """
          .trimIndent()
      )
    }
  }

  private fun MetroOptions.withAnvilContributesBinding(): MetroOptions {
    return metroOptions.copy(
      customContributesBindingAnnotations =
        setOf(ClassId.fromString("com/squareup/anvil/annotations/ContributesBinding")),
      enableDaggerAnvilInterop = true,
    )
  }
}
