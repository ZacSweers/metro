// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("UPPER_BOUND_VIOLATED_BASED_ON_JAVA_ANNOTATIONS")

package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleBuilder.build
import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.GradleProject.DslKind
import com.autonomousapps.kit.gradle.Dependency.Companion.implementation
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.GradlePlugins
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.buildAndAssertThat
import dev.zacsweers.metro.gradle.classLoader
import dev.zacsweers.metro.gradle.cleanOutputLine
import dev.zacsweers.metro.gradle.invokeMain
import dev.zacsweers.metro.gradle.source
import java.io.File
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Verifies incremental changes to injection sites and dependency metadata.
 *
 * Keep incremental tests in small classes so Gradle can distribute fixture builds across workers.
 */
@RunWith(Parameterized::class)
class ICTests(target: KmpTarget) : BaseIncrementalCompilationTest(target) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun targets(): List<KmpTarget> = KmpTarget.selectedTargets()
  }

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
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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
    val secondBuildResult = project.compileKotlinAndFail()

    // Verify that the build failed with the expected error message
    assertThat(secondBuildResult.output.cleanOutputLine())
      .contains(
        """
        e: FeatureScreen.kt:7:18 [Metro/MissingBinding] No binding found for Dependency

          FeatureScreen -> Dependency

          trace (in test.FeatureGraph):
              Dependency is injected at test.FeatureScreen.dependency
              FeatureScreen is injected at test.FeatureGraph.inject()

          help: ensure Dependency has an @Inject constructor or is provided by an @Provides or @Binds
                declaration visible to FeatureGraph
          docs: https://zacsweers.github.io/metro/latest/diagnostics/#missingbinding
        """
          .trimIndent()
      )
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

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    project.modify(
      fixture.serviceProvider,
      """
      interface ServiceProvider {
          // val dependency: String // Removed accessor
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output)
      .contains(
        """
        [Metro/MissingBinding] No binding found for String

          test.BaseGraph.target -> Target -> String

          trace (in test.BaseGraph):
              String is injected at test.Target(…, string)
              Target is requested at test.BaseGraph.target
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

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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

  @Ignore("Not working yet, pending https://youtrack.jetbrains.com/issue/KT-77938")
  @Test
  fun classVisibilityChangeDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(exampleGraph, contributedClass)

        private val exampleGraph =
          source(
            """
            interface ContributedInterface

            @DependencyGraph(Unit::class)
            interface ExampleGraph
            """
              .trimIndent()
          )

        val contributedClass =
          source(
            """
            @Inject
            @ContributesBinding(Unit::class)
            class ContributedInterfaceImpl : ContributedInterface
            """
              .trimIndent()
          )
      }
    val project = fixture.gradleProject

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    project.modify(
      fixture.contributedClass,
      """
      @Inject
      @ContributesBinding(Unit::class)
      internal class ContributedInterfaceImpl : ContributedInterface
      """
        .trimIndent(),
    )

    // Second build should fail correctly on class visibility
    val secondBuildResult = project.compileKotlinAndFail()

    // Verify that the build failed with the expected error message
    assertThat(secondBuildResult.output)
      .contains(
        "ContributedInterface.kt:8:11 DependencyGraph declarations may not extend declarations with narrower visibility. Contributed supertype 'test.ContributedInterfaceImpl' is internal but graph declaration 'test.ExampleGraph' is public."
      )
  }

  @Test
  fun fieldWrappedWithLazyIsDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(exampleGraph, exampleClass, main)

        private val exampleGraph =
          source(
            """
            @DependencyGraph
            interface ExampleGraph {
              fun inject(exampleClass: ExampleClass)

              @Provides fun provideString(): String = "Hello, world!"
            }
            """
              .trimIndent()
          )

        val exampleClass =
          source(
            """
            class ExampleClass {
              @Inject lateinit var string: String
            }
            """
              .trimIndent()
          )

        val main =
          source(
            """
            fun main(): String {
              val graph = createGraph<ExampleGraph>()
              val exampleClass = ExampleClass()
              graph.inject(exampleClass)
              return exampleClass.string
            }
            """
              .trimIndent()
          )
      }
    val project = fixture.gradleProject

    fun buildAndAssertOutput() {
      val buildResult = project.compileKotlin()
      assertThat(buildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

      ifJvmTarget {
        val mainClass = project.classLoader().loadClass("test.MainKt")
        val string = mainClass.declaredMethods.first { it.name == "main" }.invoke(null) as String
        assertThat(string).isEqualTo("Hello, world!")
      }
    }

    buildAndAssertOutput()

    project.modify(
      fixture.exampleClass,
      """
      class ExampleClass {
        @Inject lateinit var string: Lazy<String>
      }
      """
        .trimIndent(),
    )

    project.modify(
      fixture.main,
      """
      fun main(): String {
        val graph = createGraph<ExampleGraph>()
        val exampleClass = ExampleClass()
        graph.inject(exampleClass)
        return exampleClass.string.value
      }
      """
        .trimIndent(),
    )

    buildAndAssertOutput()
  }

  @Test
  fun icWorksWhenAddingAParamToExistingInjectedTypeWithScopeWithZeroToOneParams() {
    val fixture =
      object : MetroProject() {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph, main)
            dependencies(implementation(":common"), implementation(":lib"))
          }
          subproject("common") { sources(bar) }
          subproject("lib") {
            sources(foo)
            dependencies(implementation(":common"))
          }
        }

        private val bar =
          source(
            """
            interface Bar

            @Inject
            @ContributesBinding(AppScope::class)
            class BarImpl : Bar
            """
              .trimIndent()
          )

        val foo =
          source(
            """
            interface Foo

            @SingleIn(AppScope::class)
            @Inject
            @ContributesBinding(AppScope::class)
            class FooImpl : Foo
            """
              .trimIndent()
          )

        private val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph
            """
              .trimIndent()
          )

        private val main =
          source(
            """
            fun main(): Any {
              return createGraph<AppGraph>()
            }
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    fun buildAndAssertOutput() {
      val buildResult = project.compileKotlin()
      assertThat(buildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

      ifJvmTarget {
        val mainClass = project.classLoader().loadClass("test.MainKt")
        val graph = mainClass.declaredMethods.first { it.name == "main" }.invoke(null) as Any
        assertThat(graph).isNotNull()
      }
    }

    buildAndAssertOutput()

    // Adding a bar param to FooImpl, FooImpl.MetroFactory should be regenerated with member field
    libProject.modify(
      project.rootDir,
      fixture.foo,
      """
      interface Foo

      @SingleIn(AppScope::class)
      @Inject
      @ContributesBinding(AppScope::class)
      class FooImpl(bar: Bar) : Foo
      """
        .trimIndent(),
    )

    buildAndAssertOutput()
  }

  @Test
  fun icWorksWhenAddingAParamToExistingInjectedTypeWithScopeWithMultipleParams() {
    val fixture =
      object : MetroProject() {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph, main)
            dependencies(implementation(":common"), implementation(":lib"))
          }
          subproject("common") { sources(bar) }
          subproject("lib") {
            sources(foo)
            dependencies(implementation(":common"))
          }
        }

        private val bar =
          source(
            """
            interface Bar

            @Inject
            @ContributesBinding(AppScope::class)
            class BarImpl : Bar
            """
              .trimIndent()
          )

        val foo =
          source(
            """
            interface Foo

            @SingleIn(AppScope::class)
            @Inject
            @ContributesBinding(AppScope::class)
            class FooImpl(int: Int) : Foo
            """
              .trimIndent()
          )

        private val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              @Provides fun provideInt(): Int = 0
            }
            """
              .trimIndent()
          )

        private val main =
          source(
            """
            fun main(): Any {
              return createGraph<AppGraph>()
            }
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    fun buildAndAssertOutput() {
      val buildResult = project.compileKotlin()
      assertThat(buildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

      ifJvmTarget {
        val mainClass = project.classLoader().loadClass("test.MainKt")
        val graph = mainClass.declaredMethods.first { it.name == "main" }.invoke(null) as Any
        assertThat(graph).isNotNull()
      }
    }

    buildAndAssertOutput()

    // Adding a bar param to FooImpl, FooImpl.MetroFactory should be regenerated with member field
    libProject.modify(
      project.rootDir,
      fixture.foo,
      """
      interface Foo

      @SingleIn(AppScope::class)
      @Inject
      @ContributesBinding(AppScope::class)
      class FooImpl(int: Int, bar: Bar) : Foo
      """
        .trimIndent(),
    )

    buildAndAssertOutput()
  }

  @Test
  fun multiModuleNonAbiChangeDoesNotTriggerRootRecompilation() {
    // Metro's downstream-skip-on-non-ABI-change IC story is JVM-specific today; non-JVM targets
    // currently recompile downstream. Run only on JVM until that's covered separately.
    assumeTrue(target == KmpTarget.JVM)
    val fixture =
      object : MetroProject() {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph, target)
            dependencies(implementation(":lib"))
          }
          subproject("lib") { sources(provider, unrelatedClass) }
        }

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

        val provider =
          source(
            """
            @ContributesTo(Unit::class)
            interface StringProvider {
              @Provides
              fun provideString(): String = "Hello"

              // Internal implementation detail
              private fun internalHelper(): String = "internal"
            }
            """
              .trimIndent()
          )

        val unrelatedClass =
          source(
            """
            // Unrelated class not part of the dependency graph
            class UnrelatedUtility {
              fun doSomething(): String = "original"
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
    val libProject = project.subprojects.first { it.name == "lib" }

    // First build
    buildAndAssertThat(project.rootDir, compileTaskFor()) {
      task(compileTaskFor()).succeeded()
      task(compileTaskFor("lib")).succeeded()
    }

    // Make a private change in the lib module in the same file still triggers IC because IC is
    // unfortunately per-file
    libProject.modify(
      project.rootDir,
      fixture.provider,
      """
      @ContributesTo(Unit::class)
      interface StringProvider {
        @Provides
        fun provideString(): String = "Hello"

        // Internal implementation detail
        private fun internalHelper(): String = "internal"
      }

      private fun privateUtilInFile(): Int = 3
      """
        .trimIndent(),
    )

    buildAndAssertThat(project.rootDir, compileTaskFor()) {
      // Lib module should be recompiled due to the change
      task(compileTaskFor("lib")).succeeded()
      // Root module isn't UP-TO-DATE because IC operates on the file
      task(compileTaskFor()).succeeded()
    }

    // Make a non-ABI change to a function body.
    libProject.modify(
      project.rootDir,
      fixture.provider,
      """
      @ContributesTo(Unit::class)
      interface StringProvider {
        @Provides
        fun provideString(): String = "Hello"

        // Modified internal implementation detail - non-ABI change
        private fun internalHelper(): String = "modified internal"
      }

      private fun privateUtilInFile(): Int = 3
      """
        .trimIndent(),
    )

    buildAndAssertThat(project.rootDir, compileTaskFor()) {
      // Lib module should be recompiled due to the change
      task(compileTaskFor("lib")).succeeded()
      // Root module isn't UP-TO-DATE because IC operates on the file
      task(compileTaskFor()).upToDate()
    }

    // Modify an unrelated file in the lib module, should not trigger IC
    libProject.modify(
      project.rootDir,
      fixture.unrelatedClass,
      """
      // Unrelated class not part of the dependency graph
      class UnrelatedUtility {
        fun doSomething(): String = "modified"
      }
      """
        .trimIndent(),
    )

    buildAndAssertThat(project.rootDir, compileTaskFor()) {
      // Lib module should be recompiled due to the change
      task(compileTaskFor("lib")).succeeded()
      // Root module should be UP-TO-DATE since the changed file is not part of the dependency graph
      task(compileTaskFor()).upToDate()
    }

    // Verify the application still works correctly
    ifJvmTarget {
      val classLoader = project.classLoader()
      val appGraphClass = classLoader.loadClass("test.AppGraph")
      assertThat(appGraphClass).isNotNull()
    }
  }

  @Test
  fun multiplatformAndroidPluginWithReportsEnabledShouldNotFailWithFileExistsException() {
    // AGP-KMP regression test; the fixture overrides buildGradleProject() with a custom
    // jvm()+android() KMP setup, so it doesn't share the parameter matrix with the rest of the
    // suite. Run it once (under JVM) instead of repeating the same Android assemble for every
    // parameter.
    assumeTrue(target == KmpTarget.JVM)
    val fixture =
      object : MetroProject(reportsEnabled = true) {
        override fun sources() =
          listOf(
            source(
              """
              data class DummyClass(val abc: Int, val xyz: String)
              """
                .trimIndent(),
              packageName = "com.example.test",
            )
          )

        override fun buildGradleProject(): GradleProject {
          val projectSources = sources()
          return newGradleProjectBuilder(DslKind.KOTLIN)
            .withRootProject {
              sources = projectSources
              withBuildScript {
                plugins(
                  GradlePlugins.Kotlin.multiplatform(),
                  GradlePlugins.agpKmp,
                  GradlePlugins.metro,
                )
                withKotlin(
                  """
                    kotlin {
                      jvm()

                      android {
                        namespace = "com.example.test"
                        minSdk = 36
                        compileSdk = ${System.getProperty("metro.androidCompileSdk")}
                      }
                    }

                    ${buildMetroBlock()}
                  """
                    .trimIndent()
                )
              }

              withMetroSettings()

              val androidHome = System.getProperty("metro.androidHome")
              assumeTrue(androidHome != null) // skip if environment not set up for Android
              // Use invariantSeparatorsPath for cross-platform .properties file compatibility
              val sdkDir = File(androidHome).invariantSeparatorsPath
              withFile("local.properties", "sdk.dir=$sdkDir")
            }
            .write()
        }
      }

    val project = fixture.gradleProject
    val numRuns = 3

    repeat(numRuns) { i ->
      println("Running build ${i + 1}/$numRuns...")
      build(project.rootDir, "assemble", "--no-configuration-cache", "--rerun-tasks")
    }
  }

  /**
   * Tests that we can properly reload member injections info during IC from metro metadata
   *
   * Regression test for https://github.com/ZacSweers/metro/issues/1607
   */
  @Test
  fun memberInjectionsCanReloadFromMetadataInIC() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(appGraph, demoClass, anotherInjectedClass, main)

        private val appGraph =
          source(
            """
            @Suppress("SUSPICIOUS_MEMBER_INJECT_FUNCTION")
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              @Provides
              fun provideString(): String = "Demo"
              fun createAnotherInjectedClass(): AnotherInjectedClass
              fun injectDemoClassMembers(target: DemoClass)
            }
            """
              .trimIndent()
          )

        private val demoClass =
          source(
            """
            @Inject
            class DemoClass {
              @Inject
              lateinit var injectedString: String
            }
            """
              .trimIndent()
          )

        val anotherInjectedClass =
          source(
            """
            @Inject
            class AnotherInjectedClass {
              init {
                println("1")
              }
            }
            """
              .trimIndent()
          )

        private val main =
          source(
            """
            fun main(): String {
              val graph = createGraph<AppGraph>()
              val demoClass = DemoClass()
              graph.injectDemoClassMembers(demoClass)
              return demoClass.injectedString
            }
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // First build should succeed and member injection should work
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("Demo") }
    // Modify AnotherInjectedClass (unrelated to DemoClass member injection)
    project.modify(
      fixture.anotherInjectedClass,
      """
      @Inject
      class AnotherInjectedClass {
        init {
          println("2")
        }
      }
      """
        .trimIndent(),
    )

    // Second build should succeed and member injection should still work
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // This is the key assertion - member injection should still work after IC
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("Demo") }
  }

  @Test
  fun removingInheritedMemberInjectionsDoesNotBreakIncrementalCompilation() {
    assumeTrue(target == KmpTarget.JVM)

    val fixture =
      object : MetroProject(multiplatform = false) {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph, concreteViewModel)
            dependencies(implementation(":lib"))
          }
          subproject("lib") { sources(baseViewModel) }
          subproject("licenses") {
            sources(licensesViewModel)
            dependencies(implementation(":lib"))
          }
        }

        val baseViewModel = source("abstract class BaseViewModel")

        private val concreteViewModel = source("@Inject class ConcreteViewModel : BaseViewModel()")

        private val licensesViewModel = source("abstract class LicensesViewModel : BaseViewModel()")

        private val appGraph =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              @Provides fun provideString(): String = "injected"

              val viewModel: ConcreteViewModel
            }
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }
    val rootCompileTask = ":compileKotlin"
    val libCompileTask = ":lib:compileKotlin"
    val licensesCompileTask = ":licenses:compileKotlin"

    val firstBuildResult =
      project.compileKotlin(rootCompileTask, false, licensesCompileTask, "--no-build-cache")
    assertThat(firstBuildResult.task(libCompileTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(firstBuildResult.task(rootCompileTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(firstBuildResult.task(licensesCompileTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    libProject.modify(
      project.rootDir,
      fixture.baseViewModel,
      """
      abstract class BaseViewModel {
        @Inject
        fun injectMessage(message: String) {}
      }
      """
        .trimIndent(),
      sourceSet = "main",
    )

    val invalidBaseBuildResult =
      project.compileKotlinAndFail(libCompileTask, false, "--no-build-cache")
    assertThat(invalidBaseBuildResult.output)
      .contains("Non-final class 'BaseViewModel' has declared member injections")

    libProject.modify(
      project.rootDir,
      fixture.baseViewModel,
      """
      @HasMemberInjections
      abstract class BaseViewModel {
        @Inject
        fun injectMessage(message: String) {}
      }
      """
        .trimIndent(),
      sourceSet = "main",
    )

    val failedSiblingBuildResult =
      project.compileKotlinAndFail(
        rootCompileTask,
        false,
        licensesCompileTask,
        "--continue",
        "--no-build-cache",
      )
    assertThat(failedSiblingBuildResult.task(libCompileTask)?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
    assertThat(failedSiblingBuildResult.task(rootCompileTask)?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
    assertThat(failedSiblingBuildResult.task(licensesCompileTask)?.outcome)
      .isEqualTo(TaskOutcome.FAILED)
    assertThat(failedSiblingBuildResult.output)
      .contains("Non-final class 'LicensesViewModel' extends a class with member injections")
    assertThat(failedSiblingBuildResult.output).doesNotContain("Incremental compilation failed")

    libProject.modify(
      project.rootDir,
      fixture.baseViewModel,
      "abstract class BaseViewModel",
      sourceSet = "main",
    )

    val recoveryBuildResult = project.compileKotlin(rootCompileTask, false, "--no-build-cache")
    assertThat(recoveryBuildResult.task(libCompileTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(recoveryBuildResult.task(rootCompileTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(recoveryBuildResult.output).doesNotContain("Incremental compilation failed")
  }

  /**
   * Tests that having a graph and its injected dependencies in the same file doesn't cause IC
   * issues. Previously, `linkDeclarationsInCompilation` would link a file to itself via the
   * expect/actual tracker, which could cause incorrect IC behavior.
   *
   * https://github.com/ZacSweers/metro/pull/883
   */
  @Test
  fun sameFileDeclarationsDoNotCauseSelfReferentialICTracking() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(graphAndDeps, unrelated)

        private val graphAndDeps =
          source(
            """
            @Inject class Target(val string: String)

            @DependencyGraph
            interface AppGraph {
              val target: Target

              @Provides fun provideString(): String = "Hello"
            }
            """
              .trimIndent()
          )

        val unrelated =
          source(
            """
            class Unrelated {
              fun doSomething(): String = "original"
            }
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    project.modify(
      fixture.unrelated,
      """
      class Unrelated {
        fun doSomething(): String = "modified"
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }
}
