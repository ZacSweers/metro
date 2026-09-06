// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("UPPER_BOUND_VIOLATED_BASED_ON_JAVA_ANNOTATIONS")

package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.GradleProject.DslKind
import com.autonomousapps.kit.gradle.Dependency
import com.autonomousapps.kit.gradle.Dependency.Companion.implementation
import com.autonomousapps.kit.gradle.Plugin
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.GradlePlugins
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.classLoader
import dev.zacsweers.metro.gradle.getTestCompilerVersion
import dev.zacsweers.metro.gradle.invokeMain
import dev.zacsweers.metro.gradle.source
import java.io.File
import java.net.URLClassLoader
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Verifies incremental graph extension changes across module boundaries. */
@RunWith(Parameterized::class)
class GraphExtensionICTests(target: KmpTarget) : BaseIncrementalCompilationTest(target) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun targets(): List<KmpTarget> = KmpTarget.selectedTargets()
  }

  @Test
  fun extendingGraphChangesDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(childGraph, appGraph, target)

        private val childGraph =
          source(
            """
            @GraphExtension
            interface ChildGraph {
              val target: Target

              @GraphExtension.Factory
              interface Factory {
                fun create(): ChildGraph
              }
            }
            """
              .trimIndent()
          )

        val appGraph =
          source(
            """
            @DependencyGraph
            interface AppGraph : ChildGraph.Factory {
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
      fixture.appGraph,
      """
      @DependencyGraph
      interface AppGraph : ChildGraph.Factory {
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

          test.ChildGraph.target -> Target -> String

          trace (in test.AppGraph.Impl.ChildGraphImpl):
              String is injected at test.Target(…, string)
              Target is requested at test.ChildGraph.target
        """
          .trimIndent()
      )
  }

  /**
   * Tests that external contribution changes are detected even when multiple graphs depend on the
   * same scope. This verifies the fix where we track lookups before checking the cache, ensuring
   * all callers register their dependency on scope hints (not just the first one that populates the
   * cache).
   *
   * https://github.com/ZacSweers/metro/issues/1512
   */
  @Test
  fun contributedProviderExternalChangeInGraphExtensionDetected() {
    val fixture =
      object : MetroProject() {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph, appGraph2)
            dependencies(implementation(":lib"))
          }
          subproject("lib") { sources(dependency, dependencyProvider) }
        }

        // First graph with a StringGraph extension
        val appGraph =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              val stringGraph: StringGraph
            }

            @GraphExtension(String::class)
            interface StringGraph
            """
              .trimIndent()
          )

        // Second graph also using String::class scope - tests that cache hits still record lookups
        val appGraph2 =
          source(
            """
            @DependencyGraph
            interface AppGraph2 {
              val stringGraph2: StringGraph2
            }

            @GraphExtension(String::class)
            interface StringGraph2
            """
              .trimIndent()
          )

        private val dependency =
          source(
            """
            interface Dependency
            """
              .trimIndent()
          )

        val dependencyProviderSource =
          """
          @ContributesTo(String::class)
          interface DependencyProvider {
            val dependency: Dependency
          }
          """
            .trimIndent()
        val dependencyProvider = source(dependencyProviderSource)
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }
    val failureMessage =
      """
      [Metro/MissingBinding] No binding found for Dependency
      """
        .trimIndent()

    // First build should fail for both graphs due to missing binding
    // Both graphs use String::class scope, so both should see the contributed DependencyProvider
    val firstBuildResult = project.compileKotlinAndFail()
    assertThat(firstBuildResult.output).contains(failureMessage)

    // Both graphs should report the error (StringGraph and StringGraph2)
    assertThat(firstBuildResult.output).contains("StringGraph")
    assertThat(firstBuildResult.output).contains("StringGraph2")

    // Remove dependencyProvider to fix the build
    libProject.modify(project.rootDir, fixture.dependencyProvider, "")

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Restore dependencyProvider to break the build - both graphs should detect this change
    // This is the key assertion: even though the second graph's lookup hits the internal cache
    // within a single compilation, it should still register its IC dependency and be recompiled
    libProject.modify(project.rootDir, fixture.dependencyProvider, fixture.dependencyProviderSource)

    val thirdBuildResult = project.compileKotlinAndFail()
    assertThat(thirdBuildResult.output).contains(failureMessage)

    // Both graphs should still report the error after incremental recompilation
    assertThat(thirdBuildResult.output).contains("StringGraph")
    assertThat(thirdBuildResult.output).contains("StringGraph2")
  }

  @Test
  fun contributesToAddedInApiDependencyIsDetectedButNotAddedAsSupertype() {
    val fixture =
      object : MetroProject() {
        override fun buildGradleProject() = multiModuleProject {
          subproject("app") {
            sources(appGraph)
            dependencies(implementation(":lib:impl"))
          }
          subproject("lib") { sources(dummy) }
          subproject("lib:impl") {
            sources(source("class LibImpl"))
            dependencies(Dependency.api(":lib"))
          }
        }

        private val appGraph =
          source(
            """
          @DependencyGraph(AppScope::class)
          interface AppGraph
          """
          )

        val dummy =
          source(
            """
          @Inject
          class Dummy
          """
          )

        val dummyWithContributionSource =
          """
          @Inject
          class Dummy

          @ContributesTo(AppScope::class)
          internal interface DummyBindings {
            val dummy: Dummy
          }
        """
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name.removePrefix(":") == "lib" }

    fun appClassLoader(): ClassLoader {
      val urls =
        project.subprojects.mapNotNull { subproject ->
          val projectPath = subproject.name.removePrefix(":").replace(":", "/")
          val classesDir = project.rootDir.resolve("$projectPath/build/classes/kotlin/jvm/main")
          if (classesDir.exists()) classesDir.toURI().toURL() else null
        }
      return URLClassLoader(urls.toTypedArray(), this::class.java.classLoader)
    }

    val firstBuildResult = project.compileKotlin(compileTaskFor("app"))
    assertThat(firstBuildResult.task(compileTaskFor("app"))?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    libProject.modify(project.rootDir, fixture.dummy, fixture.dummyWithContributionSource)

    val secondBuildResult = project.compileKotlin(compileTaskFor("app"))
    assertThat(secondBuildResult.task(compileTaskFor("app"))?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)

    ifJvmTarget {
      val secondClassLoader = appClassLoader()
      val secondAppGraph = secondClassLoader.loadClass("test.AppGraph")
      assertThat(secondAppGraph.interfaces.map { it.name })
        .doesNotContain("test.DummyBindings\$MetroContributionToAppScope")
    }
  }

  @Test
  fun graphExtensionFactoryContributionExternalChangeIsDetected() {
    val fixture =
      object : MetroProject() {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(main)
            dependencies(implementation(":lib"))
          }
          subproject("lib") { sources(appGraph, featureGraph) }
        }

        private val appGraph =
          source(
            """
      @DependencyGraph(Unit::class)
      interface AppGraph
      """
          )

        val main =
          source(
            """
                    fun main() {
                        val appGraph = createGraph<AppGraph>()
                        val featureGraph = appGraph.asContribution<FeatureGraph.ParentBindings>().featureGraphFactory.create()
                    }
                """
          )

        val featureGraph =
          source(
            """
      @GraphExtension(String::class)
      interface FeatureGraph {
          @GraphExtension.Factory
          interface Factory {
              fun create(): FeatureGraph
          }

          @ContributesTo(Unit::class)
          interface ParentBindings {
              val featureGraphFactory: FeatureGraph.Factory
          }
      }
      """
          )
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    // First build should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Modify the FeatureGraph class to contribute the factory directly but leave ParentBindings
    libProject.modify(
      project.rootDir,
      fixture.featureGraph,
      """
      @GraphExtension(String::class)
      interface FeatureGraph {
          @GraphExtension.Factory
          @ContributesTo(Unit::class)
          interface Factory {
              fun create(): FeatureGraph
          }

          interface ParentBindings {
              val featureGraphFactory: FeatureGraph.Factory
          }
      }
      """
        .trimIndent(),
    )

    // Update asContribution type argument
    project.modify(
      fixture.main,
      """
      fun main() {
          val appGraph = createGraph<AppGraph>()
          val featureGraph = appGraph.asContribution<FeatureGraph.Factory>().create()
      }
      """
        .trimIndent(),
    )

    // Second build is still marked as success so we have to check the output
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.output).doesNotContain("Incremental compilation failed")
  }

  @Test
  fun graphExtensionFactoryContributionInternalChangeIsDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(main, appGraph, featureGraph)

        private val appGraph =
          source(
            """
      @DependencyGraph(Unit::class)
      interface AppGraph
      """
          )

        val main =
          source(
            """
                    fun main() {
                        val appGraph = createGraph<AppGraph>()
                        val featureGraph = appGraph.asContribution<FeatureGraph.ParentBindings>().featureGraphFactory.create()
                    }
                """
          )

        val featureGraph =
          source(
            """
      @GraphExtension(String::class)
      interface FeatureGraph {
          @GraphExtension.Factory
          interface Factory {
              fun create(): FeatureGraph
          }

          @ContributesTo(Unit::class)
          interface ParentBindings {
              val featureGraphFactory: FeatureGraph.Factory
          }
      }
      """
          )
      }

    val project = fixture.gradleProject

    // First build should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Modify the FeatureGraph class to contribute the factory directly but leave ParentBindings
    project.modify(
      fixture.featureGraph,
      """
      @GraphExtension(String::class)
      interface FeatureGraph {
          @GraphExtension.Factory
          @ContributesTo(Unit::class)
          interface Factory {
              fun create(): FeatureGraph
          }

          interface ParentBindings {
              val featureGraphFactory: FeatureGraph.Factory
          }
      }
      """
        .trimIndent(),
    )

    // Update asContribution type argument
    project.modify(
      fixture.main,
      """
      fun main() {
          val appGraph = createGraph<AppGraph>()
          val featureGraph = appGraph.asContribution<FeatureGraph.Factory>().create()
      }
      """
        .trimIndent(),
    )

    // Second build is still marked as success so we have to check the output
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun changingScopeForContributedInterfaceInGraphExtensionIsDetected() {
    val fixture =
      object : MetroProject() {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(main, appGraph, stringProvider)
            dependencies(implementation(":lib"))
          }
          subproject("lib") { sources(myActivity, myActivityInjector) }
        }

        private val appGraph =
          source(
            """
                        @DependencyGraph(Unit::class)
                        interface RootGraph {
                          val appGraph: AppGraph
                        }

                        @GraphExtension(AppScope::class)
                        interface AppGraph {
                          val featureGraph: FeatureGraph
                        }

                        @GraphExtension(String::class)
                        interface FeatureGraph
                    """
          )

        private val stringProvider =
          source(
            """
            @ContributesTo(AppScope::class)
            interface StringProvider {
              @Provides
              fun provideString(
                @Named("Feature") featureString: String? = null
              ) : String = featureString ?: "App"
            }

            @ContributesTo(String::class)
            interface FeatureStringProvider {
              @Provides @Named("Feature")
              fun provideFeatureString() : String = "Feature"

              @Binds @Named("Feature")
              fun bindAsNullable(@Named("Feature") featureString: String): String?
            }
            """
              .trimIndent()
          )

        val main =
          source(
            """
            fun main(): String {
                val rootGraph = createGraph<RootGraph>()
                val injector = listOf(rootGraph, rootGraph.appGraph, rootGraph.appGraph.featureGraph)
                  .filterIsInstance<MyActivityInjector>().first()
                val myActivity = MyActivity().apply {
                    injector.inject(this)
                }
                return myActivity.string
            }
            """
              .trimIndent()
          )

        val myActivity =
          source(
            """
            class MyActivity {
              @Inject
              lateinit var string: String
            }
            """
              .trimIndent()
          )

        val myActivityInjector =
          source(
            """
            @ContributesTo(String::class)
            interface MyActivityInjector {
              fun inject(whatever: MyActivity)
            }
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    // First build should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("Feature") }
    // Modify the MyActivityInjector to contribute itself to the AppScope
    libProject.modify(
      project.rootDir,
      fixture.myActivityInjector,
      """
      @ContributesTo(AppScope::class)
      interface MyActivityInjector {
        fun inject(whatever: MyActivity)
      }
      """
        .trimIndent(),
    )

    // Second build is still marked as success so we have to check the output
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("App") }
  }

  @Test
  fun `adding a member to a graph extension invalidates the parent graph on JVM`() {
    assumeTrue(target == KmpTarget.JVM)

    val fixture =
      object : MetroProject(multiplatform = false) {
        override fun sources() = listOf(main, appGraph)

        private val appGraph =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              @Provides fun message(): String = "hello"

              val childGraphFactory: ChildGraph.Factory
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "AppGraph",
          )

        private val main =
          source(
            """
            @GraphExtension
            interface ChildGraph {
              @GraphExtension.Factory
              interface Factory {
                fun create(): ChildGraph
              }
            }

            fun main(): Boolean {
              createGraph<AppGraph>().childGraphFactory.create()
              return true
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "Main",
          )
      }

    val project = fixture.gradleProject
    val compileTask = ":compileKotlin"

    fun modifyMainSource(content: String) {
      val newSource = source(content, fileNameWithoutExtension = "Main", sourceSet = "main")
      project.rootDir.resolve("src/main/kotlin/test/Main.kt").writeText(newSource.source)
    }

    val firstBuildResult = project.compileKotlin(compileTask)
    assertThat(firstBuildResult.task(compileTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.invokeMain<Boolean>(target = null)).isTrue()

    modifyMainSource(
      """
      @GraphExtension
      interface ChildGraph {
        val message: String

        @GraphExtension.Factory
        interface Factory {
          fun create(): ChildGraph
        }
      }

      fun main(): Boolean =
        createGraph<AppGraph>().childGraphFactory.create().message == "hello"
      """
        .trimIndent()
    )

    val secondBuildResult = project.compileKotlin(compileTask)
    assertThat(secondBuildResult.task(compileTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.invokeMain<Boolean>(target = null)).isTrue()
  }

  /**
   * Regression test for https://github.com/ZacSweers/metro/issues/2660 using AGP's built-in Kotlin
   * compilation path.
   *
   * Adding a member to a `@GraphExtension` must invalidate the parent graph file.
   */
  @Test
  fun `adding a member to a graph extension invalidates the parent graph`() {
    assumeTrue(target == KmpTarget.JVM)

    val fixture =
      object : MetroProject(multiplatform = false) {
        override fun sources() = listOf(main, appGraph)

        override fun buildGradleProject(): GradleProject {
          val projectSources = sources()
          return newGradleProjectBuilder(DslKind.KOTLIN)
            .withRootProject {
              sources = projectSources
              withBuildScript {
                plugins(
                  Plugin("com.android.application", System.getProperty("metro.agpVersion")),
                  // AGP 9 supplies Kotlin itself and rejects `org.jetbrains.kotlin.android`, but
                  // Metro's compiler plugin is only contributed to the compilation when some KGP
                  // plugin is applied. Parcelize is the smallest one that does that.
                  Plugin("org.jetbrains.kotlin.plugin.parcelize", getTestCompilerVersion()),
                  GradlePlugins.metro,
                )
                withKotlin(
                  """
                  android {
                    namespace = "test"
                    compileSdk = ${System.getProperty("metro.androidCompileSdk")}
                  }

                  ${buildMetroBlock()}
                  """
                    .trimIndent()
                )
              }
              withMetroSettings()

              val androidHome = System.getProperty("metro.androidHome")
              assumeTrue(androidHome != null)
              val sdkDir = File(androidHome).invariantSeparatorsPath
              withFile("local.properties", "sdk.dir=$sdkDir")
            }
            .write()
        }

        private val appGraph =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              @Provides fun message(): String = "hello"

              val childGraphFactory: ChildGraph.Factory
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "AppGraph",
          )

        private val main =
          source(
            """
            @GraphExtension
            interface ChildGraph {
              @GraphExtension.Factory
              interface Factory {
                fun create(): ChildGraph
              }
            }

            fun main(): Boolean {
              createGraph<AppGraph>().childGraphFactory.create()
              return true
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "Main",
          )
      }

    val project = fixture.gradleProject
    val compileTask = ":compileDebugKotlin"

    fun invokeAndroidMain(): Boolean {
      val classesDir =
        project.rootDir.resolve(
          "build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
        )
      return URLClassLoader(arrayOf(classesDir.toURI().toURL()), this::class.java.classLoader)
        .use { classLoader ->
          classLoader
            .loadClass("test.MainKt")
            .declaredMethods
            .first { it.name == "main" }
            .invoke(null) as Boolean
        }
    }

    fun modifyMainSource(content: String) {
      val newSource = source(content, fileNameWithoutExtension = "Main", sourceSet = "main")
      project.rootDir.resolve("src/main/kotlin/test/Main.kt").writeText(newSource.source)
    }

    val firstBuildResult = project.compileKotlin(compileTask)
    assertThat(firstBuildResult.task(compileTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(invokeAndroidMain()).isTrue()

    // Add a graph extension member, which should invalidate the parent graph.
    modifyMainSource(
      """
      @GraphExtension
      interface ChildGraph {
        val message: String

        @GraphExtension.Factory
        interface Factory {
          fun create(): ChildGraph
        }
      }

      fun main(): Boolean =
        createGraph<AppGraph>().childGraphFactory.create().message == "hello"
      """
        .trimIndent()
    )

    val secondBuildResult = project.compileKotlin(compileTask)
    assertThat(secondBuildResult.task(compileTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(invokeAndroidMain()).isTrue()
  }
}
