// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("UPPER_BOUND_VIOLATED_BASED_ON_JAVA_ANNOTATIONS")

package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.KotlinToolingVersion
import dev.zacsweers.metro.gradle.MetroOptionOverrides
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.cleanOutputLine
import dev.zacsweers.metro.gradle.getTestCompilerToolingVersion
import dev.zacsweers.metro.gradle.getTestCompilerVersion
import dev.zacsweers.metro.gradle.getTestOmitRedundantMirrorsOverride
import dev.zacsweers.metro.gradle.invokeMain
import dev.zacsweers.metro.gradle.toKotlinVersion
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Verifies incremental multibindings and contributions from external modules. */
@RunWith(Parameterized::class)
class BindingContainerMultibindingICTests(target: KmpTarget) :
  BaseIncrementalCompilationTest(target) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun targets(): List<KmpTarget> = KmpTarget.selectedTargets()
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
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output)
      .contains(
        """
        [Metro/MissingBinding] No binding found for Set<String>

          test.AppGraph.target -> Target -> Set<String>

          trace (in test.AppGraph):
              Set<String> is injected at test.Target(…, strings)
              Target is requested at test.AppGraph.target
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
    val firstBuildResult = project.compileKotlinAndFail()
    assertThat(firstBuildResult.output.cleanOutputLine())
      .contains(
        """
        e: Target.kt:6:14 [Metro/MissingBinding] No binding found for Set<String>

          test.AppGraph.target -> Target -> Set<String>

          trace (in test.AppGraph):
              Set<String> is injected at test.Target(…, strings)
              Target is requested at test.AppGraph.target

          help: ensure Set<String> has an @Inject constructor or is provided by an @Provides or @Binds
                declaration visible to AppGraph
          docs: https://zacsweers.github.io/metro/latest/diagnostics/#missingbinding
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
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
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
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output)
      .contains(
        """
        [Metro/MissingBinding] No binding found for Set<String>

          test.AppGraph.target -> Target -> Set<String>

          trace (in test.AppGraph):
              Set<String> is injected at test.Target(…, strings)
              Target is requested at test.AppGraph.target
        """
          .trimIndent()
      )
  }

  @Test
  fun multibindsQualifierArgumentChangeDetectedWhenOmittingRedundantMirrors() {
    assumeTrue(getTestCompilerToolingVersion() >= KotlinToolingVersion("2.4.0"))

    val fixture =
      object : MetroProject(metroOptions = MetroOptionOverrides(omitRedundantMirrors = true)) {
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
              @Named("expected")
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
            class Target(@Named("expected") val strings: Set<String>)
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    project.modify(
      fixture.bindingContainer,
      """
      @BindingContainer
      interface MyBindingContainer {
        @Named("changed")
        @Multibinds(allowEmpty = true)
        fun provideStrings(): Set<String>
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output)
      .contains("No binding found for @Named(\"expected\") Set<String>")

    project.modify(
      fixture.bindingContainer,
      """
      @BindingContainer
      interface MyBindingContainer {
        // Restored after the intentionally failing compilation.
        @Named("expected")
        @Multibinds(allowEmpty = true)
        fun provideStrings(): Set<String>
      }
      """
        .trimIndent(),
    )

    val thirdBuildResult = project.compileKotlin()
    assertThat(thirdBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
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
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val secondBuildResult = project.compileKotlinAndFail()
    val expectedColumn =
      if (
        getTestOmitRedundantMirrorsOverride() == true &&
          getTestCompilerToolingVersion() >= KotlinToolingVersion("2.4.0")
      ) {
        7
      } else {
        3
      }
    assertThat(secondBuildResult.output.cleanOutputLine())
      .contains(
        """
        e: MyBindingContainer.kt:8:$expectedColumn [Metro/EmptyMultibinding] Multibinding Set<String> was unexpectedly empty

          help: annotate its declaration with `@Multibinds(allowEmpty = true)` if it can legitimately be
                empty
          docs: https://zacsweers.github.io/metro/latest/diagnostics/#emptymultibinding
        """
          .trimIndent()
      )
  }

  @Test
  fun restoredMultibindingContributionFromExternalModuleIsDetected() {
    val fixture =
      object : MetroProject() {
        val multibindings =
          source(
            """
            interface Multibinding

            class AppMultibinding @Inject constructor(): Multibinding {
                override fun toString(): String = "AppMultibinding"
            }
            """
              .trimIndent()
          )

        val appModuleContent =
          """
          @BindingContainer
          @ContributesTo(Unit::class)
          interface AppModule {
            @Binds
            @IntoSet
            fun bindMultibinding(multibinding: AppMultibinding): Multibinding
          }
          """
            .trimIndent()

        val appModule = source(appModuleContent)

        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(main, appGraph)
            dependencies(Dependency.implementation(":lib"))
          }
          subproject("lib") { sources(multibindings, appModule) }
        }

        private val appGraph =
          source(
            """
            @DependencyGraph(Unit::class)
            interface AppGraph {
              val multibindings: Set<Multibinding>
            }

            @BindingContainer
            @ContributesTo(Unit::class)
            interface PrimeModule {
              @Multibinds(allowEmpty = true)
              fun bindMultibinding(): Set<Multibinding>
            }
              """
          )

        val main =
          source(
            """
            fun main(): String {
              val appGraph = createGraph<AppGraph>()
              return appGraph.multibindings.toString()
            }
            """
          )
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    // First build should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("[AppMultibinding]") }
    // Remove contributing module from the build
    libProject.delete(project.rootDir, fixture.appModule)

    // Second build should succeed
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("[]") }
    // Restore contributing module to the build
    libProject.modify(project.rootDir, fixture.appModule, fixture.appModuleContent)

    // Third build should succeed
    val thirdBuildResult = project.compileKotlin()
    assertThat(thirdBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("[AppMultibinding]") }
  }

  @Test
  fun contributionScopeChangeInMultiModuleProject() {
    val fixture =
      object : MetroProject() {
        val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val target: Target
            }

            @Inject
            class Target(val string: String)
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            class AnotherScope

            @BindingContainer
            @ContributesTo(AppScope::class)
            class StringModule {
              @Provides
              fun provideString(): String = "test"
            }
            """
              .trimIndent()
          )

        val changedContribution =
          """
          class AnotherScope

          @BindingContainer
          @ContributesTo(AnotherScope::class)
          class StringModule {
            @Provides
            fun provideString(): String = "test"
          }
          """
            .trimIndent()

        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph)
            dependencies(Dependency.implementation(":lib"))
          }
          subproject("lib") { sources(bindingContainer) }
        }
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    // First build succeed and caches hint about StringModule
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Change contribution target scope, which should stop contributing to AppGraph
    libProject.modify(project.rootDir, fixture.bindingContainer, fixture.changedContribution)

    // Build is expected to fail, because module is contributed to wrong scope
    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output).contains("[Metro/MissingBinding]")
  }

  @Test
  fun contributionWasRemovedInMultiModuleProject() {
    val fixture =
      object : MetroProject() {
        val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val target: Target
            }

            @Inject
            class Target(val string: String)
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            @BindingContainer
            @ContributesTo(AppScope::class)
            class StringModule {
              @Provides
              fun provideString(): String = "test"
            }
            """
              .trimIndent()
          )

        val removedContribution =
          """
          @BindingContainer
          class StringModule {
            @Provides
            fun provideString(): String = "test"
          }
          """
            .trimIndent()

        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph)
            dependencies(Dependency.implementation(":lib"))
          }
          subproject("lib") { sources(bindingContainer) }
        }
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    // First build succeed and caches hint about StringModule
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Remove contribution
    libProject.modify(project.rootDir, fixture.bindingContainer, fixture.removedContribution)

    // Build is expected to fail due to missing contribution
    project.compileKotlinAndFail()
  }

  @Test
  fun contributesToScopeChangeWithInterfaceBindingMultimodule() {
    // Requires FIR hint generation which is available in Kotlin 2.3.20+
    assumeTrue(getTestCompilerVersion().toKotlinVersion() >= KotlinVersion(2, 3, 20))

    val fixture =
      object : MetroProject() {
        val userApi =
          source(
            """
            interface UserApi {
              fun getCurrentUser(): String
            }
            """
              .trimIndent()
          )

        val userService =
          source(
            """
            interface UserService {
              fun doWork(): String
            }
            """
              .trimIndent()
          )

        val userServiceImpl =
          source(
            """
            @ContributesBinding(AppScope::class)
            class UserServiceImpl @Inject constructor(
              private val userApi: UserApi
            ) : UserService {
              override fun doWork() = userApi.getCurrentUser()
            }
            """
              .trimIndent()
          )

        val bindingContainer =
          source(
            """
            @BindingContainer
            @ContributesTo(AppScope::class)
            object UserApiModule {
              @Provides
              fun provideUserApi(): UserApi = object : UserApi {
                override fun getCurrentUser() = "user"
              }
            }
            """
              .trimIndent()
          )

        val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val userService: UserService
            }
            """
              .trimIndent()
          )

        override fun sources() = listOf(appGraph)

        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph)
            dependencies(Dependency.implementation(":lib"))
          }
          subproject("lib") { sources(userApi, userService, userServiceImpl, bindingContainer) }
        }
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    project.compileKotlin()

    // Change UserApiModule to a different scope to trigger the change
    // This should break UserServiceImpl (which is in AppScope)
    libProject.modify(
      project.rootDir,
      fixture.bindingContainer,
      """
      @BindingContainer
      @ContributesTo(Unit::class)
      object UserApiModule {
        @Provides
        fun provideUserApi(): UserApi = object : UserApi {
          override fun getCurrentUser() = "user"
        }
      }
      """
        .trimIndent(),
    )

    // Expect failure: UserServiceImpl needs UserApi, but UserApi is now in Unit scope
    project.compileKotlinAndFail()

    // Remove @ContributesTo entirely
    libProject.modify(
      project.rootDir,
      fixture.bindingContainer,
      """
      @BindingContainer
      object UserApiModule {
        @Provides
        fun provideUserApi(): UserApi = object : UserApi {
          override fun getCurrentUser() = "user"
        }
      }
      """
        .trimIndent(),
    )

    // Expect failure: UserApi is not in any scope at all
    project.compileKotlinAndFail()
  }
}
