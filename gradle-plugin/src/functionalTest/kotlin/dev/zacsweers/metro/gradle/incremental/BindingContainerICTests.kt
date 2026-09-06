// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("UPPER_BOUND_VIOLATED_BASED_ON_JAVA_ANNOTATIONS")

package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroOptionOverrides
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.assertOutputContains
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Verifies incremental binding declarations, scopes, and dynamic container arguments. */
@RunWith(Parameterized::class)
class BindingContainerICTests(target: KmpTarget) : BaseIncrementalCompilationTest(target) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun targets(): List<KmpTarget> = KmpTarget.selectedTargets()
  }

  @Test
  fun addingNewBindingToExistingBindingContainer() {
    val fixture =
      object :
        MetroProject(
          metroOptions =
            MetroOptionOverrides(
              // Enable full validation for this case to ensure we pick up and store the unused B
              // binding
              enableFullBindingGraphValidation = true
            )
        ) {
        override fun sources() = listOf(appGraph, bindingContainer, implementations, target)

        private val appGraph =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              val target: Target

              @DependencyGraph.Factory
              interface Factory {
                fun create(@Includes bindings: MyBindingContainer): AppGraph
              }
            }
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            @BindingContainer
            interface MyBindingContainer {
              @Binds
              fun ImplA.bindA(): InterfaceA
            }
            """
              .trimIndent()
          )

        private val implementations =
          source(
            """
            interface InterfaceA
            interface InterfaceB

            @Inject
            class ImplA : InterfaceA

            @Inject
            class ImplB : InterfaceB
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val a: InterfaceA)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Add a new binding to the container
    project.modify(
      fixture.bindingContainer,
      """
      @BindingContainer
      interface MyBindingContainer {
        @Binds
        fun ImplA.bindA(): InterfaceA

        @Binds
        fun ImplB.bindB(): InterfaceB
      }
      """
        .trimIndent(),
    )
    assertThat(project.asMetroProject.appGraphReports.keysPopulated).doesNotContain("InterfaceB")

    // Second build should succeed with the new binding available
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.asMetroProject.appGraphReports.keysPopulated)
      .containsAtLeastElementsIn(setOf("test.InterfaceB", "test.ImplB"))
  }

  @Test
  fun removingBindingFromBindingContainer() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, bindingContainer, implementations, target)

        private val appGraph =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              val target: Target

              @DependencyGraph.Factory
              interface Factory {
                fun create(@Includes bindings: MyBindingContainer): AppGraph
              }
            }
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            @BindingContainer
            interface MyBindingContainer {
              @Binds
              fun ImplA.bindA(): InterfaceA

              @Binds
              fun ImplB.bindB(): InterfaceB
            }
            """
              .trimIndent()
          )

        private val implementations =
          source(
            """
            interface InterfaceA
            interface InterfaceB

            @Inject
            class ImplA : InterfaceA

            @Inject
            class ImplB : InterfaceB
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val a: InterfaceA, val b: InterfaceB)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Remove a binding that's being used
    project.modify(
      fixture.bindingContainer,
      """
      @BindingContainer
      interface MyBindingContainer {
        @Binds
        fun ImplA.bindA(): InterfaceA

        // Removed @Binds for InterfaceB
      }
      """
        .trimIndent(),
    )

    // Second build should fail due to missing binding
    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output)
      .contains(
        """
        [Metro/MissingBinding] No binding found for InterfaceB

          test.AppGraph.target -> Target -> InterfaceB

          trace (in test.AppGraph):
              InterfaceB is injected at test.Target(…, b)
              Target is requested at test.AppGraph.target
        """
          .trimIndent()
      )
  }

  @Test
  fun changingBindsMethodSignature() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, bindingContainer, implementations, target)

        private val appGraph =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              val target: Target

              @DependencyGraph.Factory
              interface Factory {
                fun create(@Includes bindings: MyBindingContainer): AppGraph
              }
            }
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            @BindingContainer
            interface MyBindingContainer {
              @Binds
              fun ImplA.bindA(): InterfaceA
            }
            """
              .trimIndent()
          )

        val implementations =
          source(
            """
            interface InterfaceA
            interface InterfaceB

            @Inject
            class ImplA : InterfaceA, InterfaceB
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val a: InterfaceA)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.asMetroProject.appGraphReports.keysPopulated)
      .doesNotContain("test.InterfaceB")

    // Change the binding return type
    project.modify(
      fixture.bindingContainer,
      """
      @BindingContainer
      interface MyBindingContainer {
        @Binds
        fun ImplA.bindA(): InterfaceB // Changed from InterfaceA to InterfaceB
      }
      """
        .trimIndent(),
    )

    // Second build should fail due to missing InterfaceA binding
    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output)
      .contains(
        """
        [Metro/MissingBinding] No binding found for InterfaceA

          test.AppGraph.target -> Target -> InterfaceA

          trace (in test.AppGraph):
              InterfaceA is injected at test.Target(…, a)
              Target is requested at test.AppGraph.target
        """
          .trimIndent()
      )
  }

  @Test
  fun scopingChangesOnBindingContainer() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, bindingContainer, target)

        private val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val target: Target

              @DependencyGraph.Factory
              interface Factory {
                fun create(@Includes bindings: MyBindingContainer): AppGraph
              }
            }
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            @BindingContainer
            class MyBindingContainer {
              @Provides
              fun provideString(): String = "hello"
            }
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val string: String)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.asMetroProject.appGraphReports.scopedProviderPropertyKeys).isEmpty()

    // Add scope to the provider method
    project.modify(
      fixture.bindingContainer,
      """
      @BindingContainer
      class MyBindingContainer {
        @SingleIn(AppScope::class)
        @Provides
        fun provideString(): String = "hello"
      }
      """
        .trimIndent(),
    )

    // Second build should succeed with the scoped provider
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.asMetroProject.appGraphReports.scopedProviderPropertyKeys)
      .contains("kotlin.String")
  }

  @Test
  fun bindingContainerWithContributesTo() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, bindingContainer, target)

        private val appGraph =
          source(
            """
            @DependencyGraph(Unit::class)
            interface AppGraph {
              val target: Target
            }
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            @ContributesTo(Unit::class)
            @BindingContainer
            interface MyBindingContainer {
              @Binds
              fun ImplA.bindA(): InterfaceA
            }

            interface InterfaceA

            @Inject
            class ImplA : InterfaceA
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val a: InterfaceA)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Remove the binding from the container
    project.modify(
      fixture.bindingContainer,
      """
      @ContributesTo(Unit::class)
      @BindingContainer
      interface MyBindingContainer {
        // Removed binding
      }

      interface InterfaceA

      @Inject
      class ImplA : InterfaceA
      """
        .trimIndent(),
    )

    // Second build should fail
    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output).contains("[Metro/MissingBinding] No binding found for")
  }

  @Test
  fun multiModuleBindingContainerChanges() {
    val fixture =
      object : MetroProject() {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph, featureGraph, target)
            dependencies(Dependency.implementation(":lib"))
          }
          subproject("lib") { sources(bindingContainer) }
        }

        private val appGraph =
          source(
            """
            @DependencyGraph(Unit::class)
            interface AppGraph
            """
              .trimIndent()
          )

        private val featureGraph =
          source(
            """
            @GraphExtension
            interface FeatureGraph {
              val target: Target

              @ContributesTo(Unit::class)
              @GraphExtension.Factory
              interface Factory {
                fun create(
                  @Includes bindings: MyBindingContainer
                ): FeatureGraph
              }
            }
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            @BindingContainer
            interface MyBindingContainer {
              @Binds
              fun ImplA.bindA(): InterfaceA
            }

            interface InterfaceA
            interface InterfaceB

            @Inject
            class ImplA : InterfaceA, InterfaceB
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val a: InterfaceA)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    // First build should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Change the binding in the container
    libProject.modify(
      project.rootDir,
      fixture.bindingContainer,
      """
      @BindingContainer
      interface MyBindingContainer {
        // Changed: now binds to a different interface
        @Binds
        fun ImplA.bindA(): InterfaceB
      }

      interface InterfaceA
      interface InterfaceB

      @Inject
      class ImplA : InterfaceA, InterfaceB
      """
        .trimIndent(),
    )

    // Second build should fail - InterfaceA is no longer bound
    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output)
      .contains(
        """
        [Metro/MissingBinding] No binding found for InterfaceA

          test.FeatureGraph.target -> Target -> InterfaceA

          trace (in test.AppGraph.Impl.FeatureGraphImpl):
              InterfaceA is injected at test.Target(…, a)
              Target is requested at test.FeatureGraph.target
        """
          .trimIndent()
      )
  }

  @Test
  fun bindingContainerWithProvidesChanges() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, bindingContainer, target)

        private val appGraph =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              val target: Target

              @DependencyGraph.Factory
              interface Factory {
                fun create(@Includes container: MixedContainer): AppGraph
              }
            }
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            @BindingContainer
            interface MixedContainer {
              @Binds
              fun ImplA.bindA(): InterfaceA

              companion object {
                @Provides
                fun provideString(): String = "hello"
              }
            }

            interface InterfaceA

            @Inject
            class ImplA : InterfaceA
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val string: String, val a: InterfaceA)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Change the provides method
    project.modify(
      fixture.bindingContainer,
      """
      @BindingContainer
      interface MixedContainer {
        @Binds
        fun ImplA.bindA(): InterfaceA

        companion object {
          @Provides
          fun provideInt(): Int = 42 // Changed from String to Int
        }
      }

      interface InterfaceA

      @Inject
      class ImplA : InterfaceA
      """
        .trimIndent(),
    )

    // Second build should fail - String is no longer provided
    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output)
      .contains(
        """
        [Metro/MissingBinding] No binding found for String

          test.AppGraph.target -> Target -> String

          trace (in test.AppGraph):
              String is injected at test.Target(…, string)
              Target is requested at test.AppGraph.target
        """
          .trimIndent()
      )
  }

  @Test
  fun dynamicGraphWithScopeChangeInDynamicBindingContainer() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, testBindingContainer, target, testClass)

        private val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val target: Target

              @Provides
              fun provideString(): String = "default"
            }
            """
              .trimIndent()
          )

        val testBindingContainer =
          source(
            """
            @BindingContainer
            class TestBindingContainer {
              @Provides
              fun provideString(): String = "test"
            }
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val string: String)
            """
              .trimIndent()
          )

        private val testClass =
          source(
            """
            class AppTest {
              val testGraph = createDynamicGraph<AppGraph>(TestBindingContainer())
            }
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed with unscoped provider
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Add scope to the provider in the binding container
    project.modify(
      fixture.testBindingContainer,
      """
      @BindingContainer
      class TestBindingContainer {
        @SingleIn(AppScope::class)
        @Provides
        fun provideString(): String = "test"
      }
      """
        .trimIndent(),
    )

    // Second build should succeed with scoped provider
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Remove scope from the provider
    project.modify(
      fixture.testBindingContainer,
      """
      @BindingContainer
      class TestBindingContainer {
        @Provides
        fun provideString(): String = "test"
      }
      """
        .trimIndent(),
    )

    // Third build should succeed with unscoped provider again
    val thirdBuildResult = project.compileKotlin()
    assertThat(thirdBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun dynamicGraphWithChangingArguments() {
    val fixture =
      object : MetroProject() {
        override fun sources() =
          listOf(appGraph, bindingContainerA, bindingContainerB, target, testClass)

        private val appGraph =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              val target: Target

              @Provides
              fun provideString(): String = "default"
            }
            """
              .trimIndent()
          )

        private val bindingContainerA =
          source(
            """
            @BindingContainer
            class BindingContainerA {
              @Provides
              fun provideString(): String = "A"
            }
            """
              .trimIndent()
          )

        private val bindingContainerB =
          source(
            """
            @BindingContainer
            class BindingContainerB {
              @Provides
              fun provideString(): String = "B"

              @Provides
              fun provideInt(): Int = 42
            }
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val string: String)
            """
              .trimIndent()
          )

        val testClass =
          source(
            """
            class AppTest {
              val testGraph = createDynamicGraph<AppGraph>(BindingContainerA())
            }
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed with BindingContainerA
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Change to use BindingContainerB
    project.modify(
      fixture.testClass,
      """
      class AppTest {
        val testGraph = createDynamicGraph<AppGraph>(BindingContainerB())
      }
      """
        .trimIndent(),
    )

    // Second build should succeed with BindingContainerB
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Change to use both containers (should fail due to duplicate String binding)
    project.modify(
      fixture.testClass,
      """
      class AppTest {
        val testGraph = createDynamicGraph<AppGraph>(BindingContainerA(), BindingContainerB())
      }
      """
        .trimIndent(),
    )

    // Third build should fail - duplicate String binding
    val thirdBuildResult = project.compileKotlinAndFail()

    thirdBuildResult.assertOutputContains(
      """
      [Metro/DuplicateBinding] Multiple bindings found for String

            BindingContainerA.kt:8:3
              @Provides fun provideString(): String
                                             ~~~~~~

            BindingContainerB.kt:8:3
              @Provides fun provideString(): String
                                             ~~~~~~
      """
        .trimIndent()
    )
  }
}
