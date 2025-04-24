// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.AbstractGradleProject
import com.autonomousapps.kit.GradleBuilder.build
import com.autonomousapps.kit.GradleBuilder.buildAndFail
import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.GradleProject.DslKind
import com.autonomousapps.kit.Source.Companion.kotlin
import com.autonomousapps.kit.gradle.Plugin
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
    val fixture = Fixture314()
    val project = fixture.gradleProject

    // First build should succeed
    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Modify the FeatureScreen class to comment out the dependency property
    modifyKotlinFile(
      project.rootDir,
      "com.example",
      "FeatureScreen.kt",
      """
            package com.example

            import dev.zacsweers.metro.ContributesTo
            import dev.zacsweers.metro.Inject

            interface Dependency

            class FeatureScreen {
                @Inject
                lateinit var dependency: Dependency

                @ContributesTo(Unit::class)
                interface ServiceProvider {
                    // val dependency: Dependency
                }
            }
        """
        .trimIndent(),
    )

    // Second build should fail correctly on a missing binding
    val secondBuildResult = buildAndFail(project.rootDir, "compileKotlin")

    // Verify that the build failed with the expected error message
    assertThat(secondBuildResult.output)
      .contains("[Metro/MissingBinding] Missing bindings for: [com.example.Dependency")
  }

  class Fixture314 : AbstractGradleProject() {

    // Injected into functionalTest JVM by the plugin
    // Also available via AbstractGradleProject.PLUGIN_UNDER_TEST_VERSION
    private val pluginVersion = PLUGIN_UNDER_TEST_VERSION

    val gradleProject: GradleProject
      get() = build()

    private fun build(): GradleProject {
      return newGradleProjectBuilder(DslKind.KOTLIN)
        .withRootProject {
          sources = listOf(appGraph, featureGraph, featureScreen)
          withBuildScript {
            plugins(
              Plugin("org.jetbrains.kotlin.jvm", "2.1.20"),
              Plugin("dev.zacsweers.metro", pluginVersion),
            )
          }
        }
        .write()
    }

    private val appGraph =
      kotlin(
          """
          package com.example
        
          import dev.zacsweers.metro.ContributesBinding
          import dev.zacsweers.metro.DependencyGraph
          import dev.zacsweers.metro.Inject
        
          @DependencyGraph(Unit::class)
          interface AppGraph
        
          @Inject
          @ContributesBinding(Unit::class)
          class DependencyImpl : Dependency
          """
        )
        .withPath("com.example", "AppGraph")
        .build()

    private val featureGraph =
      kotlin(
          """
          package com.example
    
          import dev.zacsweers.metro.DependencyGraph
          import dev.zacsweers.metro.Includes
    
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
        .withPath("com.example", "FeatureGraph")
        .build()

    private val featureScreen =
      kotlin(
          """
            package com.example
    
            import dev.zacsweers.metro.ContributesTo
            import dev.zacsweers.metro.Inject
    
            interface Dependency
    
            class FeatureScreen {
                @Inject
                lateinit var dependency: Dependency
    
                @ContributesTo(Unit::class)
                interface ServiceProvider {
                    val dependency: Dependency // comment this line to break incremental
                }
            }
          """
        )
        .withPath("com.example", "FeatureScreen")
        .build()
  }

  @Test
  fun includesDependencyWithRemovedAccessorsShouldBeDetected() {
    val fixture = FixtureIncludes()
    val project = fixture.gradleProject

    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    modifyKotlinFile(
      project.rootDir,
      "com.example",
      "ServiceProvider.kt",
      """
      package com.example
    
      import dev.zacsweers.metro.ContributesTo
  
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
                [com.example.BaseGraph] com.example.Target(…, string)
            com.example.Target is requested at
                [com.example.BaseGraph] com.example.BaseGraph#target
      """
          .trimIndent()
      )
  }

  class FixtureIncludes : AbstractGradleProject() {
    private val pluginVersion = PLUGIN_UNDER_TEST_VERSION

    val gradleProject: GradleProject
      get() = build()

    private fun build(): GradleProject {
      return newGradleProjectBuilder(DslKind.KOTLIN)
        .withRootProject {
          sources = listOf(baseGraph, serviceProvider, target)
          withBuildScript {
            plugins(
              Plugin("org.jetbrains.kotlin.jvm", "2.1.20"),
              Plugin("dev.zacsweers.metro", pluginVersion),
            )
          }
        }
        .write()
    }

    private val baseGraph =
      kotlin(
          """
      package com.example
    
      import dev.zacsweers.metro.DependencyGraph
      import dev.zacsweers.metro.Includes
  
      @DependencyGraph 
      interface BaseGraph {
          val target: Target
  
          @DependencyGraph.Factory
          interface Factory {
              fun create(@Includes provider: ServiceProvider): BaseGraph
          }
      }
    """
        )
        .withPath("com.example", "BaseGraph")
        .build()

    private val serviceProvider =
      kotlin(
          """
      package com.example
    
      import dev.zacsweers.metro.ContributesTo
  
      interface ServiceProvider {
        val dependency: String
      }
    """
        )
        .withPath("com.example", "ServiceProvider")
        .build()

    private val target =
      kotlin(
          """
      package com.example
    
      import dev.zacsweers.metro.Inject
  
      @Inject 
      class Target(val string: String)
    """
        )
        .withPath("com.example", "Target")
        .build()
  }

  // TODO accessor change isn't being detected
  @Test
  fun extendingGraphChangesDetected() {
    val fixture = FixtureExtends()
    val project = fixture.gradleProject

    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    modifyKotlinFile(
      project.rootDir,
      "com.example",
      "AppGraph.kt",
      """
      package com.example
    
      import dev.zacsweers.metro.DependencyGraph
  
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
      .contains("[Metro/MissingBinding] Missing bindings for: [kotlin.String")
  }

  class FixtureExtends : AbstractGradleProject() {
    private val pluginVersion = PLUGIN_UNDER_TEST_VERSION

    val gradleProject: GradleProject
      get() = build()

    private fun build(): GradleProject {
      return newGradleProjectBuilder(DslKind.KOTLIN)
        .withRootProject {
          sources = listOf(childGraph, appGraph, target)
          withBuildScript {
            plugins(
              Plugin("org.jetbrains.kotlin.jvm", "2.1.20"),
              Plugin("dev.zacsweers.metro", pluginVersion),
            )
          }
        }
        .write()
    }

    private val childGraph =
      kotlin(
          """
      package com.example
    
      import dev.zacsweers.metro.DependencyGraph
      import dev.zacsweers.metro.Extends
  
      @DependencyGraph
      interface ChildGraph {
        val target: Target
        
        @DependencyGraph.Factory
        interface Factory {
          fun create(@Extends appGraph: AppGraph): ChildGraph
        }
      }
    """
        )
        .withPath("com.example", "ChildGraph")
        .build()

    private val appGraph =
      kotlin(
          """
      package com.example
    
      import dev.zacsweers.metro.DependencyGraph
      import dev.zacsweers.metro.Provides
  
      @DependencyGraph(isExtendable = true)
      interface AppGraph {
        @Provides
        fun provideString(): String = ""
      }
    """
        )
        .withPath("com.example", "AppGraph")
        .build()

    private val target =
      kotlin(
          """
      package com.example
  
      import dev.zacsweers.metro.Inject
  
      @Inject 
      class Target(val string: String)
    """
        )
        .withPath("com.example", "Target")
        .build()
  }

  @Test
  fun newContributesIntoSetDetected() {
    val fixture = FixtureContributesIntoSet()
    val project = fixture.gradleProject

    val firstBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    modifyKotlinFile(
      project.rootDir,
      "com.example",
      "ContributedInterfaces.kt",
      """
      package com.example
  
      import dev.zacsweers.metro.ContributesIntoSet
      import dev.zacsweers.metro.Inject
    
      @Inject
      @ContributesIntoSet(Unit::class)
      class NewContribution : ContributedInterface
      """
        .trimIndent(),
    )

    val secondBuildResult = build(project.rootDir, "compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // TODO verify the new contribution was added
    assertThat(secondBuildResult.output).contains("Processing contribution: NewContribution")
  }

  class FixtureContributesIntoSet : AbstractGradleProject() {
    private val pluginVersion = PLUGIN_UNDER_TEST_VERSION

    val gradleProject: GradleProject
      get() = build()

    private fun build(): GradleProject {
      return newGradleProjectBuilder(DslKind.KOTLIN)
        .withRootProject {
          sources = listOf(exampleGraph, contributedInterfaces)
          withBuildScript {
            plugins(
              Plugin("org.jetbrains.kotlin.jvm", "2.1.20"),
              Plugin("dev.zacsweers.metro", pluginVersion),
            )
          }
        }
        .write()
    }

    private val exampleGraph =
      kotlin(
          """
      package com.example
    
      import dev.zacsweers.metro.DependencyGraph
      
      interface ContributedInterface
  
      @DependencyGraph(Unit::class)
      interface ExamplGraph {
        val set: Set<ContributedInterface>
      }
    """
        )
        .withPath("com.example", "ExamplGraph")
        .build()

    private val contributedInterfaces =
      kotlin(
          """
      package com.example
      
      import dev.zacsweers.metro.ContributesIntoSet
      import dev.zacsweers.metro.Inject
  
      @Inject
      @ContributesIntoSet(Unit::class)
      class Impl1 : ContributedInterface
    """
        )
        .withPath("com.example", "ContributedInterfaces")
        .build()
  }
}
