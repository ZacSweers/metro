// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("UPPER_BOUND_VIOLATED_BASED_ON_JAVA_ANNOTATIONS")

package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleBuilder.build
import com.autonomousapps.kit.GradleBuilder.buildAndFail
import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.GradleProject.DslKind
import com.autonomousapps.kit.gradle.Dependency
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.MetroOptionOverrides
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.assertOutputContainsOnDifferentKotlinVersions
import dev.zacsweers.metro.gradle.source
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test

class BindingContainerICTests : BaseIncrementalCompilationTest() {

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
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    assertThat(project.appGraphReports.keysPopulated).doesNotContain("InterfaceB")

    // Second build should succeed with the new binding available
    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.appGraphReports.keysPopulated)
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
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
        AppGraph.kt:7:11 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: test.InterfaceB

            test.InterfaceB is injected at
                [test.AppGraph] test.Target(…, b)
            test.Target is requested at
                [test.AppGraph] test.AppGraph.target
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
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.appGraphReports.keysPopulated).doesNotContain("test.InterfaceB")

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
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
        AppGraph.kt:7:11 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: test.InterfaceA

            test.InterfaceA is injected at
                [test.AppGraph] test.Target(…, a)
            test.Target is requested at
                [test.AppGraph] test.AppGraph.target
        """
          .trimIndent()
      )
  }

  @Test
  fun addingBindingContainerToGraphInclusion() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, bindingContainer, impl, target)

        val appGraph =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              val target: Target
            }
            """
              .trimIndent()
          )

        private val bindingContainer =
          source(
            """
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

        private val impl =
          source(
            """
            @Inject
            class ImplB : InterfaceA
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

    // First build should fail - no binding for InterfaceA
    val firstBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.output).contains("Cannot find an @Inject constructor")

    // Add the binding container to the graph
    project.modify(
      fixture.appGraph,
      """
      @DependencyGraph(bindingContainers = [MyBindingContainer::class])
      interface AppGraph {
        val target: Target
      }
      """
        .trimIndent(),
    )

    // Second build should succeed with the binding container included
    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun removingBindingContainerFromGraphInclusion() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, bindingContainer, target)

        val appGraph =
          source(
            """
            @DependencyGraph(bindingContainers = [MyBindingContainer::class])
            interface AppGraph {
              val target: Target
            }
            """
              .trimIndent()
          )

        private val bindingContainer =
          source(
            """
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
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Remove the binding container from the graph
    project.modify(
      fixture.appGraph,
      """
      @DependencyGraph
      interface AppGraph {
        val target: Target
      }
      """
        .trimIndent(),
    )

    // Second build should fail - no binding for InterfaceA
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
        AppGraph.kt:7:11 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: test.InterfaceA

            test.InterfaceA is injected at
                [test.AppGraph] test.Target(…, a)
            test.Target is requested at
                [test.AppGraph] test.AppGraph.target
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
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.appGraphReports.scopedProviderPropertyKeys).isEmpty()

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
    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.appGraphReports.scopedProviderPropertyKeys).contains("kotlin.String")
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
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output).contains("Cannot find an @Inject constructor")
  }

  @Test
  fun multiModuleBindingContainerChanges() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, featureGraph, target)

        override val gradleProject: GradleProject
          get() =
            newGradleProjectBuilder(DslKind.KOTLIN)
              .withRootProject {
                withBuildScript {
                  sources = sources()
                  applyMetroDefault()
                  dependencies(Dependency.implementation(":lib"))
                }
              }
              .withSubproject("lib") {
                sources.add(bindingContainer)
                withBuildScript { applyMetroDefault() }
              }
              .write()

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
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
        AppGraph.kt:7:11 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: test.InterfaceA

            test.InterfaceA is injected at
                [test.AppGraph.Impl.FeatureGraphImpl] test.Target(…, a)
            test.Target is requested at
                [test.AppGraph.Impl.FeatureGraphImpl] test.FeatureGraph.target
        """
          .trimIndent()
      )
  }

  @Test
  fun bindingContainerIncludingOtherContainers() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, parentContainer, childContainer, impls, target)

        private val appGraph =
          source(
            """
            @DependencyGraph(bindingContainers = [ChildContainer::class])
            interface AppGraph {
              val target: Target
            }
            """
              .trimIndent()
          )

        val parentContainer =
          source(
            """
            @BindingContainer
            interface ParentContainer {
              @Binds
              fun ImplA.bindA(): InterfaceA
            }
            """
              .trimIndent()
          )

        val childContainer =
          source(
            """
            @BindingContainer(includes = [ParentContainer::class])
            interface ChildContainer {
              @Binds
              fun ImplB.bindB(): InterfaceB
            }
            """
              .trimIndent()
          )

        private val impls =
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
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Remove a binding from the parent container
    project.modify(
      fixture.parentContainer,
      """
      @BindingContainer
      interface ParentContainer {
        // Removed binding for InterfaceA
      }
      """
        .trimIndent(),
    )

    // Second build should fail - InterfaceA binding is missing
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
        AppGraph.kt:7:11 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: test.InterfaceA

            test.InterfaceA is injected at
                [test.AppGraph] test.Target(…, a)
            test.Target is requested at
                [test.AppGraph] test.AppGraph.target
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
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
        AppGraph.kt:7:11 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is injected at
                [test.AppGraph] test.Target(…, string)
            test.Target is requested at
                [test.AppGraph] test.AppGraph.target
        """
          .trimIndent()
      )
  }

  @Test
  fun changingBindingContainersArrayInDependencyGraphAnnotation() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, containerA, containerB, impls, target)

        val appGraph =
          source(
            """
            @DependencyGraph(bindingContainers = [ContainerA::class])
            interface AppGraph {
              val target: Target
            }
            """
              .trimIndent()
          )

        private val containerA =
          source(
            """
            @BindingContainer
            interface ContainerA {
              @Binds
              fun ImplA.bindA(): InterfaceA
            }
            """
              .trimIndent()
          )

        private val containerB =
          source(
            """
            @BindingContainer
            interface ContainerB {
              @Binds
              fun ImplB.bindB(): InterfaceB
            }
            """
              .trimIndent()
          )

        private val impls =
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

    // First build should succeed with only ContainerA
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Add ContainerB to the array
    project.modify(
      fixture.appGraph,
      """
      @DependencyGraph(bindingContainers = [ContainerA::class, ContainerB::class])
      interface AppGraph {
        val target: Target
      }
      """
        .trimIndent(),
    )

    // Second build should still succeed with both containers
    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Remove ContainerA from the array
    project.modify(
      fixture.appGraph,
      """
      @DependencyGraph(bindingContainers = [ContainerB::class])
      interface AppGraph {
        val target: Target
      }
      """
        .trimIndent(),
    )

    // Third build should fail - InterfaceA is no longer bound
    val thirdBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(thirdBuildResult.output)
      .contains(
        """
        AppGraph.kt:7:11 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: test.InterfaceA

            test.InterfaceA is injected at
                [test.AppGraph] test.Target(…, a)
            test.Target is requested at
                [test.AppGraph] test.AppGraph.target
        """
          .trimIndent()
      )
  }

  @Test
  fun changingIncludesArrayInBindingContainerAnnotation() {
    val fixture =
      object : MetroProject() {
        override fun sources() =
          listOf(appGraph, parentContainerA, parentContainerB, childContainer, impls, target)

        private val appGraph =
          source(
            """
            @DependencyGraph(bindingContainers = [ChildContainer::class])
            interface AppGraph {
              val target: Target
            }
            """
              .trimIndent()
          )

        private val parentContainerA =
          source(
            """
            @BindingContainer
            interface ParentContainerA {
              @Binds
              fun ImplA.bindA(): InterfaceA
            }
            """
              .trimIndent()
          )

        private val parentContainerB =
          source(
            """
            @BindingContainer
            interface ParentContainerB {
              @Binds
              fun ImplB.bindB(): InterfaceB
            }
            """
              .trimIndent()
          )

        val childContainer =
          source(
            """
            @BindingContainer(includes = [ParentContainerA::class])
            interface ChildContainer {
              @Binds
              fun ImplC.bindC(): InterfaceC
            }
            """
              .trimIndent()
          )

        private val impls =
          source(
            """
            interface InterfaceA
            interface InterfaceB
            interface InterfaceC

            @Inject
            class ImplA : InterfaceA

            @Inject
            class ImplB : InterfaceB

            @Inject
            class ImplC : InterfaceC
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val a: InterfaceA, val c: InterfaceC)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed with ParentContainerA included
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Add ParentContainerB to the includes array
    project.modify(
      fixture.childContainer,
      """
      @BindingContainer(includes = [ParentContainerA::class, ParentContainerB::class])
      interface ChildContainer {
        @Binds
        fun ImplC.bindC(): InterfaceC
      }
      """
        .trimIndent(),
    )

    // Second build should still succeed with both parents included
    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Remove ParentContainerA from the includes array
    project.modify(
      fixture.childContainer,
      """
      @BindingContainer(includes = [ParentContainerB::class])
      interface ChildContainer {
        @Binds
        fun ImplC.bindC(): InterfaceC
      }
      """
        .trimIndent(),
    )

    // Third build should fail - InterfaceA is no longer bound
    val thirdBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(thirdBuildResult.output)
      .contains(
        """
        AppGraph.kt:7:11 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: test.InterfaceA

            test.InterfaceA is injected at
                [test.AppGraph] test.Target(…, a)
            test.Target is requested at
                [test.AppGraph] test.AppGraph.target
        """
          .trimIndent()
      )
  }

  @Test
  fun addingAndRemovingMultipleContainersViaAnnotations() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, containerA, containerB, containerC, impls, target)

        val appGraph =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              val target: Target
            }
            """
              .trimIndent()
          )

        private val containerA =
          source(
            """
            @BindingContainer
            interface ContainerA {
              @Binds
              fun ImplA.bindA(): InterfaceA
            }
            """
              .trimIndent()
          )

        private val containerB =
          source(
            """
            @BindingContainer
            interface ContainerB {
              @Binds
              fun ImplB.bindB(): InterfaceB
            }
            """
              .trimIndent()
          )

        private val containerC =
          source(
            """
            @BindingContainer
            interface ContainerC {
              @Binds
              fun ImplC.bindC(): InterfaceC
            }
            """
              .trimIndent()
          )

        private val impls =
          source(
            """
            interface InterfaceA
            interface InterfaceB
            interface InterfaceC

            @Inject
            class ImplA : InterfaceA

            @Inject
            class ImplB : InterfaceB

            @Inject
            class ImplC : InterfaceC
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

    // First build should fail - no containers included
    val firstBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.output).contains("Cannot find an @Inject constructor")

    // Add multiple containers at once
    project.modify(
      fixture.appGraph,
      """
      @DependencyGraph(bindingContainers = [ContainerA::class, ContainerB::class, ContainerC::class])
      interface AppGraph {
        val target: Target
      }
      """
        .trimIndent(),
    )

    // Second build should succeed with all containers
    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Remove multiple containers at once, keeping only ContainerA
    project.modify(
      fixture.appGraph,
      """
      @DependencyGraph(bindingContainers = [ContainerA::class])
      interface AppGraph {
        val target: Target
      }
      """
        .trimIndent(),
    )

    // Third build should fail - InterfaceB is no longer bound
    val thirdBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(thirdBuildResult.output)
      .contains(
        """
        AppGraph.kt:7:11 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: test.InterfaceB

            test.InterfaceB is injected at
                [test.AppGraph] test.Target(…, b)
            test.Target is requested at
                [test.AppGraph] test.AppGraph.target
        """
          .trimIndent()
      )
  }

  @Test
  fun nestedIncludesChanges() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, containerA, containerB, containerC, impls, target)

        private val appGraph =
          source(
            """
            @DependencyGraph(bindingContainers = [ContainerA::class])
            interface AppGraph {
              val target: Target
            }
            """
              .trimIndent()
          )

        val containerA =
          source(
            """
            @BindingContainer(includes = [ContainerB::class])
            interface ContainerA {
              @Binds
              fun ImplA.bindA(): InterfaceA
            }
            """
              .trimIndent()
          )

        val containerB =
          source(
            """
            @BindingContainer(includes = [ContainerC::class])
            interface ContainerB {
              @Binds
              fun ImplB.bindB(): InterfaceB
            }
            """
              .trimIndent()
          )

        val containerC =
          source(
            """
            @BindingContainer
            interface ContainerC {
              @Binds
              fun ImplC.bindC(): InterfaceC
            }
            """
              .trimIndent()
          )

        private val impls =
          source(
            """
            interface InterfaceA
            interface InterfaceB
            interface InterfaceC

            @Inject
            class ImplA : InterfaceA

            @Inject
            class ImplB : InterfaceB

            @Inject
            class ImplC : InterfaceC
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val a: InterfaceA, val b: InterfaceB, val c: InterfaceC)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed - A includes B, B includes C
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Remove ContainerC from ContainerB's includes
    project.modify(
      fixture.containerB,
      """
      @BindingContainer
      interface ContainerB {
        @Binds
        fun ImplB.bindB(): InterfaceB
      }
      """
        .trimIndent(),
    )

    // Second build should fail - InterfaceC is no longer available through the chain
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
        AppGraph.kt:7:11 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: test.InterfaceC

            test.InterfaceC is injected at
                [test.AppGraph] test.Target(…, c)
            test.Target is requested at
                [test.AppGraph] test.AppGraph.target
        """
          .trimIndent()
      )

    // Add ContainerC directly to ContainerA to restore the binding via a different path
    project.modify(
      fixture.containerA,
      """
      @BindingContainer(includes = [ContainerB::class, ContainerC::class])
      interface ContainerA {
        @Binds
        fun ImplA.bindA(): InterfaceA
      }
      """
        .trimIndent(),
    )

    // Third build should succeed again - ContainerC is now directly included in ContainerA
    val thirdBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(thirdBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun multibindsOnlyContainerRemoved() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, bindingContainer, target)

        private val appGraph =
          source(
            """
            @DependencyGraph(bindingContainers = [MyBindingContainer::class])
            interface AppGraph {
              val target: Target
            }
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            @BindingContainer
            interface MyBindingContainer {
              @Multibinds(allowEmpty = true)
              fun provideStrings(): Set<String>
            }
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val strings: Set<String>)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed with empty set
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Remove the binding
    project.modify(
      fixture.bindingContainer,
      """
      @BindingContainer
      interface MyBindingContainer {
      }
      """
        .trimIndent(),
    )

    // Second build should fail - Set<String> is no longer available
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
        AppGraph.kt:7:11 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.collections.Set<kotlin.String>

            kotlin.collections.Set<kotlin.String> is injected at
                [test.AppGraph] test.Target(…, strings)
            test.Target is requested at
                [test.AppGraph] test.AppGraph.target
        """
          .trimIndent()
      )
  }

  @Test
  fun multibindsOnlyContainerAdded() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, bindingContainer, target)

        private val appGraph =
          source(
            """
            @DependencyGraph(bindingContainers = [MyBindingContainer::class])
            interface AppGraph {
              val target: Target
            }
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            @BindingContainer
            interface MyBindingContainer {
            }
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val strings: Set<String>)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should fail - Set<String> is not available
    val firstBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.output)
      .contains(
        """
        Target.kt:7:14 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.collections.Set<kotlin.String>

            kotlin.collections.Set<kotlin.String> is injected at
                [test.AppGraph] test.Target(…, strings)
            test.Target is requested at
                [test.AppGraph] test.AppGraph.target
        """
          .trimIndent()
      )

    // Add the binding
    project.modify(
      fixture.bindingContainer,
      """
      @BindingContainer
      interface MyBindingContainer {
        @Multibinds(allowEmpty = true)
        fun provideStrings(): Set<String>
      }
      """
        .trimIndent(),
    )

    // Second build should succeed with empty set
    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun multibindsOnlyContainerWithQualifierChanges() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, bindingContainer, target)

        private val appGraph =
          source(
            """
            @DependencyGraph(bindingContainers = [MyBindingContainer::class])
            interface AppGraph {
              val target: Target
            }
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            @BindingContainer
            interface MyBindingContainer {
              @Multibinds(allowEmpty = true)
              fun provideStrings(): Set<String>
            }
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val strings: Set<String>)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed with empty set
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Add a qualifier annotation to the multibinds method
    project.modify(
      fixture.bindingContainer,
      """
      @BindingContainer
      interface MyBindingContainer {
        @Named("qualified")
        @Multibinds(allowEmpty = true)
        fun provideStrings(): Set<String>
      }
      """
        .trimIndent(),
    )

    // Second build should fail - unqualified Set<String> is no longer available
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
        AppGraph.kt:7:11 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.collections.Set<kotlin.String>

            kotlin.collections.Set<kotlin.String> is injected at
                [test.AppGraph] test.Target(…, strings)
            test.Target is requested at
                [test.AppGraph] test.AppGraph.target
        """
          .trimIndent()
      )
  }

  @Test
  fun multibindsOnlyContainerWithAllowEmptyChanges() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, bindingContainer, target)

        private val appGraph =
          source(
            """
            @DependencyGraph(bindingContainers = [MyBindingContainer::class])
            interface AppGraph {
              val target: Target
            }
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            @BindingContainer
            interface MyBindingContainer {
              @Multibinds(allowEmpty = true)
              fun provideStrings(): Set<String>
            }
            """
              .trimIndent()
          )

        private val target =
          source(
            """
            @Inject
            class Target(val strings: Set<String>)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed with empty set
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Remove allowEmpty
    project.modify(
      fixture.bindingContainer,
      """
      @BindingContainer
      interface MyBindingContainer {
        @Multibinds
        fun provideStrings(): Set<String>
      }
      """
        .trimIndent(),
    )

    // Second build should fail - Set is now empty and not allowed
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
        MyBindingContainer.kt:9:3 [Metro/EmptyMultibinding] Multibinding 'kotlin.collections.Set<kotlin.String>' was unexpectedly empty.

        If you expect this multibinding to possibly be empty, annotate its declaration with `@Multibinds(allowEmpty = true)`.
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
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val thirdBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(thirdBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
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
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val thirdBuildResult = buildAndFail(project.rootDir, "compileKotlin")

    thirdBuildResult.assertOutputContainsOnDifferentKotlinVersions(
      mapOf(
        "2.2.20" to
          """
          AppTest.kt:6:7 [Metro/DuplicateBinding] Multiple bindings found for kotlin.String

            test.AppGraph
              fun provideString(): kotlin.String
                                   ~~~~~~~~~~~~~
            test.BindingContainerA
              fun provideString(): kotlin.String
                                   ~~~~~~~~~~~~~
          """
            .trimIndent(),
        "2.3.0" to
          """
          e: AppTest.kt:6:7 [Metro/DuplicateBinding] Multiple bindings found for kotlin.String

            AppGraph.kt:10:3
              @Provides fun provideString(): kotlin.String
                                             ~~~~~~~~~~~~~
            test.BindingContainerA
              fun provideString(): kotlin.String
                                   ~~~~~~~~~~~~~
          """
            .trimIndent(),
      )
    )
  }
}
