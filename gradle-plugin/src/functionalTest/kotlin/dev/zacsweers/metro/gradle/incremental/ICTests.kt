// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleBuilder.build
import com.autonomousapps.kit.GradleBuilder.buildAndFail
import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Test

class ICTests : BaseIncrementalCompilationTest() {

  /**
   * This test covers an issue where incremental compilation fails to detect when an `@Includes`
   * parameter changes an accessor.
   *
   * Regression test for https://github.com/ZacSweers/metro/issues/314, based on the repro project:
   * https://github.com/kevinguitar/metro-playground/tree/ic-issue-sample
   */
  @Test
  fun removingDependencyPropertyShouldFailOnIc() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, featureGraph, featureScreen)

        private val appGraph =
          source(
            """
          @DependencyGraph(Unit::class)
          interface AppGraph

          @Inject
          @ContributesBinding(Unit::class)
          class DependencyImpl : Dependency
          """
          )

        private val featureGraph =
          source(
            """
          @DependencyGraph
          interface FeatureGraph {
              fun inject(screen: FeatureScreen)

              @DependencyGraph.Factory
              interface Factory {
                  fun create(
                      @Includes serviceProvider: FeatureScreen.ServiceProvider
                  ): FeatureGraph
              }
          }
          """
          )

        val featureScreen =
          source(
            """
            class FeatureScreen {
                @Inject
                lateinit var dependency: Dependency

                @ContributesTo(Unit::class)
                interface ServiceProvider {
                    val dependency: Dependency // comment this line to break incremental
                }
            }

            interface Dependency
          """
          )
      }

    val project = fixture.gradleProject

    // First build should succeed
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Modify the FeatureScreen class to comment out the dependency property
    project.modify(
      fixture.featureScreen,
      """
      class FeatureScreen {
          @Inject
          lateinit var dependency: Dependency

          @ContributesTo(Unit::class)
          interface ServiceProvider {
              // val dependency: Dependency
          }
      }

      interface Dependency
      """
        .trimIndent(),
    )

    // Second build should fail correctly on a missing binding
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")

    // Verify that the build failed with the expected error message
    assertThat(secondBuildResult.output)
      .contains("[Metro/MissingBinding] Missing bindings for: [test.Dependency")
  }

  @Test
  fun includesDependencyWithRemovedAccessorsShouldBeDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(baseGraph, serviceProvider, target)

        private val baseGraph =
          source(
            """
          @DependencyGraph 
          interface BaseGraph {
              val target: Target
          
              @DependencyGraph.Factory
              interface Factory {
                  fun create(@Includes provider: ServiceProvider): BaseGraph
              }
          }
          """
              .trimIndent()
          )

        val serviceProvider =
          source(
            """
          interface ServiceProvider {
            val dependency: String
          }
          """
              .trimIndent()
          )

        private val target = source("@Inject class Target(val string: String)")
      }

    val project = fixture.gradleProject

    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    project.modify(
      fixture.serviceProvider,
      """
      interface ServiceProvider {
          // val dependency: String // Removed accessor
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
        e: [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is injected at
                [test.BaseGraph] test.Target(…, string)
            test.Target is requested at
                [test.BaseGraph] test.BaseGraph#target
      """
          .trimIndent()
      )
  }

  @Test
  fun extendingGraphChangesDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(childGraph, appGraph, target)

        private val childGraph =
          source(
            """
          @DependencyGraph
          interface ChildGraph {
            val target: Target
          
            @DependencyGraph.Factory
            interface Factory {
              fun create(@Extends appGraph: AppGraph): ChildGraph
            }
          }
          """
              .trimIndent()
          )

        val appGraph =
          source(
            """
          @DependencyGraph(isExtendable = true)
          interface AppGraph {
            @Provides
            fun provideString(): String = ""
          }
          """
              .trimIndent()
          )

        private val target = source("@Inject class Target(val string: String)")
      }

    val project = fixture.gradleProject

    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    project.modify(
      fixture.appGraph,
      """
      @DependencyGraph(isExtendable = true)
      interface AppGraph {
        // Removed provider
        // @Provides
        // fun provideString(): String = ""
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
          e: [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String
          
              kotlin.String is injected at
                  [test.ChildGraph] test.Target(…, string)
              test.Target is requested at
                  [test.ChildGraph] test.ChildGraph#target
        """
          .trimIndent()
      )
  }

  @Test
  fun supertypeProviderChangesDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(stringProvider, appGraph, target)

        private val appGraph =
          source(
            """
          @DependencyGraph
          interface AppGraph : StringProvider {
            val target: Target
          }
          """
              .trimIndent()
          )

        val stringProvider =
          source(
            """
          interface StringProvider {
            @Provides
            fun provideString(): String = ""
          }
          """
              .trimIndent()
          )

        private val target = source("@Inject class Target(val string: String)")
      }

    val project = fixture.gradleProject

    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    project.modify(
      fixture.stringProvider,
      """
      interface StringProvider {
        // Removed provider
        // @Provides
        // fun provideString(): String = ""
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
          e: [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String
          
              kotlin.String is injected at
                  [test.AppGraph] test.Target(…, string)
              test.Target is requested at
                  [test.AppGraph] test.AppGraph#target
        """
          .trimIndent()
      )
  }

  @Test
  fun supertypeProviderCompanionChangesDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(stringProvider, appGraph, target)

        private val appGraph =
          source(
            """
          @DependencyGraph
          interface AppGraph : StringProvider {
            val target: Target
          }
          """
              .trimIndent()
          )

        val stringProvider =
          source(
            """
          interface StringProvider {
            companion object {
              @Provides
              fun provideString(): String = ""
            }
          }
          """
              .trimIndent()
          )

        private val target = source("@Inject class Target(val string: String)")
      }

    val project = fixture.gradleProject

    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    project.modify(
      fixture.stringProvider,
      """
      interface StringProvider {
        companion object {
          // Removed provider
          // @Provides
          // fun provideString(): String = ""
        }
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.output)
      .contains(
        """
          e: [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String
          
              kotlin.String is injected at
                  [test.AppGraph] test.Target(…, string)
              test.Target is requested at
                  [test.AppGraph] test.AppGraph#target
        """
          .trimIndent()
      )
  }

  @Test
  fun newContributesIntoSetDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(exampleGraph, contributedInterfaces)

        private val exampleGraph =
          source(
            """
            @DependencyGraph(Unit::class)
            interface ExamplGraph {
              val set: Set<ContributedInterface>
            }
            interface ContributedInterface
          """
              .trimIndent()
          )

        val contributedInterfaces =
          source(
            """
            @Inject
            @ContributesIntoSet(Unit::class)
            class Impl1 : ContributedInterface
          """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    project.modify(
      fixture.contributedInterfaces,
      """
      @Inject
      @ContributesIntoSet(Unit::class)
      class Impl1 : ContributedInterface

      @Inject
      @ContributesIntoSet(Unit::class)
      class NewContribution : ContributedInterface
      """
        .trimIndent(),
    )

    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Verify that the new contribution is included in the interfaces
    val classLoader = project.classLoader()
    val exampleGraph = classLoader.loadClass("test.ExamplGraph")
    assertThat(exampleGraph.interfaces.map { it.name })
      .contains("test.NewContribution$$\$MetroContribution")
  }

  @Test
  fun removedContributesIntoSetDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(exampleGraph, contributedInterfaces)

        private val exampleGraph =
          source(
            """
            @DependencyGraph(Unit::class)
            interface ExamplGraph {
              val set: Set<ContributedInterface>
            }
            interface ContributedInterface
          """
              .trimIndent()
          )

        val contributedInterfaces =
          source(
            """
            @Inject
            @ContributesIntoSet(Unit::class)
            class Impl1 : ContributedInterface
      
            @Inject
            @ContributesIntoSet(Unit::class)
            class Impl2 : ContributedInterface
          """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Verify that the new contribution is included in the interfaces
    with(project.classLoader()) {
      val exampleGraph = loadClass("test.ExamplGraph")
      assertThat(exampleGraph.interfaces.map { it.name })
        .contains("test.Impl2$$\$MetroContribution")
    }

    project.modify(
      fixture.contributedInterfaces,
      """
      @Inject
      @ContributesIntoSet(Unit::class)
      class Impl1 : ContributedInterface
      """
        .trimIndent(),
    )

    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Verify that the removed contribution is removed from supertypes
    val classLoader = project.classLoader()
    val exampleGraph = classLoader.loadClass("test.ExamplGraph")
    assertThat(exampleGraph.interfaces.map { it.name })
      .doesNotContain("test.Impl2$$\$MetroContribution")
  }

  @Test
  fun newContributesToDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(exampleGraph, contributedInterfaces)

        private val exampleGraph =
          source(
            """
          interface ContributedInterface

          @DependencyGraph(Unit::class)
          interface ExamplGraph
          """
              .trimIndent()
          )

        val contributedInterfaces =
          source(
            """
          @ContributesTo(Unit::class)
          interface ContributedInterface1
          """
              .trimIndent()
          )
      }
    val project = fixture.gradleProject

    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    project.modify(
      fixture.contributedInterfaces,
      """
      @ContributesTo(Unit::class)
      interface ContributedInterface1

      @ContributesTo(Unit::class)
      interface ContributedInterface2
      """
        .trimIndent(),
    )

    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Check that ContributedInterface2 was added as a supertype
    val classLoader = project.classLoader()
    val exampleGraph = classLoader.loadClass("test.ExamplGraph")
    assertThat(exampleGraph.interfaces.map { it.name })
      .contains("test.ContributedInterface2$$\$MetroContribution")
  }

  @Test
  fun removedContributesToDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(exampleGraph, contributedInterfaces)

        private val exampleGraph =
          source(
            """
          interface ContributedInterface

          @DependencyGraph(Unit::class)
          interface ExamplGraph
          """
              .trimIndent()
          )

        val contributedInterfaces =
          source(
            """
          @ContributesTo(Unit::class)
          interface ContributedInterface1

          @ContributesTo(Unit::class)
          interface ContributedInterface2
          """
              .trimIndent()
          )
      }
    val project = fixture.gradleProject

    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    with(project.classLoader()) {
      val exampleGraph = loadClass("test.ExamplGraph")
      assertThat(exampleGraph.interfaces.map { it.name })
        .contains("test.ContributedInterface2$$\$MetroContribution")
    }

    project.modify(
      fixture.contributedInterfaces,
      """
      @ContributesTo(Unit::class)
      interface ContributedInterface1
      """
        .trimIndent(),
    )

    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Check that ContributedInterface2 was removed as a supertype
    val classLoader = project.classLoader()
    val exampleGraph = classLoader.loadClass("test.ExamplGraph")
    assertThat(exampleGraph.interfaces.map { it.name })
      .doesNotContain("test.ContributedInterface2$$\$MetroContribution")
  }
}
