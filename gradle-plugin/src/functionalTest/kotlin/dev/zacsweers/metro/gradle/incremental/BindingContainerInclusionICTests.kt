// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("UPPER_BOUND_VIOLATED_BASED_ON_JAVA_ANNOTATIONS")

package dev.zacsweers.metro.gradle.incremental

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Verifies incremental changes to included binding containers and their annotations. */
@RunWith(Parameterized::class)
class BindingContainerInclusionICTests(target: KmpTarget) : BaseIncrementalCompilationTest(target) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun targets(): List<KmpTarget> = KmpTarget.selectedTargets()
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
    val firstBuildResult = project.compileKotlinAndFail()
    assertThat(firstBuildResult.output).contains("[Metro/MissingBinding] No binding found for")

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
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
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
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val thirdBuildResult = project.compileKotlinAndFail()
    assertThat(thirdBuildResult.output)
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
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val thirdBuildResult = project.compileKotlinAndFail()
    assertThat(thirdBuildResult.output)
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
    val firstBuildResult = project.compileKotlinAndFail()
    assertThat(firstBuildResult.output).contains("[Metro/MissingBinding] No binding found for")

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
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val thirdBuildResult = project.compileKotlinAndFail()
    assertThat(thirdBuildResult.output)
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
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output)
      .contains(
        """
        [Metro/MissingBinding] No binding found for InterfaceC

          test.AppGraph.target -> Target -> InterfaceC

          trace (in test.AppGraph):
              InterfaceC is injected at test.Target(…, c)
              Target is requested at test.AppGraph.target
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
    val thirdBuildResult = project.compileKotlin()
    assertThat(thirdBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }
}
