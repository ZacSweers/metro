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

  // TODO
  //  - @Includes dep adding an accessor should be detected
  //  - @Extends dep adding/removing a provider not used in extended graph should be detected
  //  - Adding a new contributesinto* should be detected
}
