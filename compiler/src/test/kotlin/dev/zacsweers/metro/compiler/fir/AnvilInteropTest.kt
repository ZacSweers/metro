// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedImpl
import kotlin.test.Test
import org.jetbrains.kotlin.name.ClassId
import org.junit.Ignore

class AnvilInteropTest : MetroCompilerTest() {

  @Ignore(
    "Can enable once type arguments are saved to metadata - https://youtrack.jetbrains.com/issue/KT-76954/Some-type-arguments-are-not-saved-to-metadata-in-FIR",
  )
  @Test
  fun `ranked binding processing supports outranked bindings using Metro's @ContributesBinding from downstream module`() {
    val libCompilation =
      compile(
        source(
          """
          interface ContributedInterface

          interface OtherInterface

          // Having two supertypes and an explicit binding type with Metro's @ContributesBinding
          // annotation is the key piece of this repro
          @ContributesBinding(AppScope::class, binding<ContributedInterface>())
          @Inject
          class Impl1 : ContributedInterface, OtherInterface
          """
            .trimIndent(),
        ),
      )

    compile(
      source(
        """
        @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 10)
        @Inject
        class Impl2 : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface
        }

        """
          .trimIndent(),
      ),
      previousCompilationResult = libCompilation,
      options = metroOptions.withAnvilInterop(),
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl2")
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
            .trimIndent(),
        ),
        options = metroOptions.withAnvilInterop(),
      )

    compile(
      source(
        """
        interface ContributedInterface

        @CompanyFeature
        @com.squareup.anvil.annotations.ContributesBinding(AppScope::class)
        object Impl1 : ContributedInterface

        @com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 100)
        object Impl2 : ContributedInterface

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributedInterface: ContributedInterface

          @CompanyFeature
          val qualifiedContributedInterface: ContributedInterface
        }
        """
          .trimIndent(),
      ),
      previousCompilationResult = previousCompilation,
      options =
        metroOptions
          .withAnvilInterop()
          .toBuilder()
          .customQualifierAnnotations(setOf(ClassId.fromString("test/ThirdPartyQualifier")))
          .build(),
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val contributedInterface = graph.callProperty<Any>("contributedInterface")
      assertThat(contributedInterface).isNotNull()
      assertThat(contributedInterface.javaClass.name).isEqualTo("test.Impl2")

      val qualifiedContributedInterface = graph.callProperty<Any>("qualifiedContributedInterface")
      assertThat(qualifiedContributedInterface).isNotNull()
      assertThat(qualifiedContributedInterface.javaClass.name).isEqualTo("test.Impl1")
    }
  }

  private fun MetroOptions.withAnvilInterop(): MetroOptions {
    return toBuilder()
      .customContributesBindingAnnotations(
        setOf(ClassId.fromString("com/squareup/anvil/annotations/ContributesBinding")),
      )
      .customGraphExtensionAnnotations(
        setOf(ClassId.fromString("com/squareup/anvil/annotations/ContributesSubcomponent")),
      )
      .enableDaggerAnvilInterop(true)
      .build()
  }
}
