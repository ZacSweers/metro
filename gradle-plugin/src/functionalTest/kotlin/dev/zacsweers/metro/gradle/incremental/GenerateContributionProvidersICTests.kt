// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency.Companion.implementation
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.FileSnapshot
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroOptionOverrides
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.getTestCompilerVersion
import dev.zacsweers.metro.gradle.invokeMain
import dev.zacsweers.metro.gradle.snapshot
import dev.zacsweers.metro.gradle.toKotlinVersion
import java.io.File
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class GenerateContributionProvidersICTests(target: KmpTarget) :
  BaseIncrementalCompilationTest(target) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun targets(): List<KmpTarget> = KmpTarget.entries
  }

  @Before
  fun setup() {
    // Requires FIR hint generation which is available in Kotlin 2.3.20+
    assumeTrue(getTestCompilerVersion().toKotlinVersion() >= KotlinVersion(2, 3, 20))
  }

  /**
   * Tests that `generateContributionProviders` allows an implementation class to be `internal`
   * without causing recompilation of downstream modules when its ABI changes.
   *
   * Setup:
   * - `:common` - holds `Base` interface and `AppScope`
   * - `:lib` - holds `internal class Impl(...) : Base` with `@ContributesBinding`
   * - `:` (root) - holds the graph at AppScope and exposes an accessor for `Base`
   *
   * The test asserts that after an ABI-breaking change to `Impl` (which is internal), the root
   * project does _not_ recompile.
   */
  @Test
  fun contributionProvidersAllowInternalImplWithoutDownstreamRecompilation() {
    val fixture =
      object :
        MetroProject(metroOptions = MetroOptionOverrides(generateContributionProviders = true)) {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph, main)
            dependencies(implementation(":common"), implementation(":lib"))
          }
          subproject("common") { sources(base) }
          subproject("lib") {
            sources(impl)
            dependencies(implementation(":common"))
          }
        }

        val base =
          source(
            """
            interface Base {
              fun value(): String
            }
            """
              .trimIndent()
          )

        val implSource =
          """
          @ContributesBinding(AppScope::class)
          @Inject
          internal class Impl : Base {
            override fun value(): String = "original"
          }
          """
            .trimIndent()

        val impl = source(implSource)

        val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val base: Base
            }
            """
              .trimIndent()
          )

        val main =
          source(
            """
            fun main(): String {
              val graph = createGraph<AppGraph>()
              return graph.base.value()
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
    assertThat(firstBuildResult.task(compileTaskFor("lib"))?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Verify first build runs correctly
    ifJvmTarget {
      val firstOutput = project.invokeMain<String>()
      assertThat(firstOutput).isEqualTo("original")
    }

    // Modify the internal Impl class with an ABI change (add a public method).
    // If Impl were public, this would cause downstream recompilation because the class's ABI
    // changed. But since Impl is internal and only exposed through the generated provider,
    // the root project should not need to recompile.
    libProject.modify(
      project.rootDir,
      fixture.impl,
      """
      @ContributesBinding(AppScope::class)
      @Inject
      internal class Impl : Base {
        override fun value(): String = "modified" + newPublicMethod()
        fun newPublicMethod(): Int = 42
      }
      """
        .trimIndent(),
    )

    // Snapshot root project class file identity before the second build.
    // We track both the file key (inode) and last modified time to detect if files were
    // deleted and recreated (same content/timestamp but different inode).
    val rootClassesDir = project.rootDir.resolve("build/classes/kotlin/jvm/main")
    val classSnapshotsBefore = rootClassesDir.classFileSnapshot()

    // Second build: lib should recompile. The Kotlin compiler's IC should determine
    // that no root project source files need recompilation (only internal class changed).
    // Gradle may still run the task (classpath snapshot path changed), but no actual
    // source recompilation should happen.
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor("lib"))?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)

    // Verify no root project class files were recompiled by checking inode + timestamp
    val classSnapshotsAfter = rootClassesDir.classFileSnapshot()
    assertThat(classSnapshotsAfter).isEqualTo(classSnapshotsBefore)

    // Verify second build still runs correctly with the modified impl
    ifJvmTarget {
      val secondOutput = project.invokeMain<String>()
      assertThat(secondOutput).isEqualTo("modified42")
    }
  }

  /**
   * Tests that changing the scope of a contributed binding correctly triggers recompilation of the
   * consuming graph. When `Impl` moves from `AppScope` to a different scope, the graph that
   * previously consumed it should detect the change and fail with a missing binding.
   */
  @Test
  fun scopeChangeInContributionProviderTriggersDownstreamRecompilation() {
    val fixture =
      object :
        MetroProject(metroOptions = MetroOptionOverrides(generateContributionProviders = true)) {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph, main)
            dependencies(implementation(":common"), implementation(":lib"))
          }
          subproject("common") { sources(base) }
          subproject("lib") {
            sources(impl)
            dependencies(implementation(":common"))
          }
        }

        val base =
          source(
            """
            interface Base
            """
              .trimIndent()
          )

        val impl =
          source(
            """
            @ContributesBinding(AppScope::class)
            @Inject
            internal class Impl : Base
            """
              .trimIndent()
          )

        val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val base: Base
            }
            """
              .trimIndent()
          )

        val main =
          source(
            """
            fun main(): String {
              val graph = createGraph<AppGraph>()
              return graph.base::class.simpleName!!
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

    ifJvmTarget {
      val firstOutput = project.invokeMain<String>()
      assertThat(firstOutput).isEqualTo("Impl")
    }

    // Change the scope from AppScope to Unit (a different scope).
    // The root graph at AppScope should no longer find the binding.
    libProject.modify(
      project.rootDir,
      fixture.impl,
      """
      @ContributesBinding(Unit::class)
      @Inject
      internal class Impl : Base
      """
        .trimIndent(),
    )

    // Second build should fail — the binding moved to a different scope
    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output).contains("[Metro/MissingBinding]")
  }

  /**
   * Tests that scoped contribution providers correctly share a single instance across multiple
   * accessors in a cross-module setup. The scoped binding from `lib` should produce the same
   * instance when accessed via different graph accessors in `main`.
   */
  @Test
  fun scopedContributionProviderSharesInstanceAcrossModules() {
    val fixture =
      object :
        MetroProject(metroOptions = MetroOptionOverrides(generateContributionProviders = true)) {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph, main)
            dependencies(implementation(":common"), implementation(":lib"))
          }
          subproject("common") { sources(base) }
          subproject("lib") {
            sources(impl)
            dependencies(implementation(":common"))
          }
        }

        val base =
          source(
            """
            interface Base
            """
              .trimIndent()
          )

        val impl =
          source(
            """
            @ContributesBinding(AppScope::class)
            @SingleIn(AppScope::class)
            @Inject
            class Impl : Base
            """
              .trimIndent()
          )

        val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val base1: Base
              val base2: Base
            }
            """
              .trimIndent()
          )

        val main =
          source(
            """
            fun main(): String {
              val graph = createGraph<AppGraph>()
              return if (graph.base1 === graph.base2) "same" else "different"
            }
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    val buildResult = project.compileKotlin()
    assertThat(buildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    ifJvmTarget {
      val output = project.invokeMain<String>()
      assertThat(output).isEqualTo("same")
    }
  }

  /**
   * Tests that all contribution types (`@ContributesBinding`, `@ContributesIntoSet`,
   * `@ContributesIntoMap`, `@ContributesTo`) work correctly with IC when
   * `generateContributionProviders` is enabled. This is a regression test for a bug where generated
   * top-level holder classes were incorrectly reported to the `ExpectActualTracker`, poisoning the
   * complementary-files IC cache and causing compilation failures.
   */
  @Test
  fun allContributionTypesWorkWithIC() {
    val fixture =
      object :
        MetroProject(metroOptions = MetroOptionOverrides(generateContributionProviders = true)) {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph, main)
            dependencies(implementation(":common"), implementation(":lib"))
          }
          subproject("common") { sources(base, contributedInterface, moduleInterface) }
          subproject("lib") {
            sources(binding, intoSet, intoMap, contributesToModule)
            dependencies(implementation(":common"))
          }
        }

        val base =
          source(
            """
            interface Base {
              fun value(): String
            }
            """
              .trimIndent()
          )

        val contributedInterface =
          source(
            """
            interface ContributedInterface
            """
              .trimIndent()
          )

        val moduleInterface =
          source(
            """
            interface ModuleInterface {
              @Provides fun provideTag(): String = "from-module"
            }
            """
              .trimIndent()
          )

        val binding =
          source(
            """
            @ContributesBinding(AppScope::class)
            @Inject
            internal class Binding : Base {
              override fun value(): String = "binding"
            }
            """
              .trimIndent()
          )

        val intoSet =
          source(
            """
            @ContributesIntoSet(AppScope::class)
            @Inject
            internal class IntoSet : ContributedInterface
            """
              .trimIndent()
          )

        val intoMap =
          source(
            """
            @ContributesIntoMap(AppScope::class)
            @StringKey("key1")
            @Inject
            internal class IntoMap : ContributedInterface
            """
              .trimIndent()
          )

        val contributesToModule =
          source(
            """
            @ContributesTo(AppScope::class)
            interface ContributesToModule : ModuleInterface
            """
              .trimIndent()
          )

        val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val base: Base
              val set: Set<ContributedInterface>
              val map: Map<String, ContributedInterface>
              val tag: String
            }
            """
              .trimIndent()
          )

        val main =
          source(
            """
            fun main(): String {
              val graph = createGraph<AppGraph>()
              return listOf(
                graph.base.value(),
                graph.set.size.toString(),
                graph.map.keys.first(),
                graph.tag,
              ).joinToString(",")
            }
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    // First build
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    ifJvmTarget {
      val firstOutput = project.invokeMain<String>()
      assertThat(firstOutput).isEqualTo("binding,1,key1,from-module")
    }

    // Modify the binding impl — should trigger incremental recompilation without IC errors
    libProject.modify(
      project.rootDir,
      fixture.binding,
      """
      @ContributesBinding(AppScope::class)
      @Inject
      internal class Binding : Base {
        override fun value(): String = "modified"
      }
      """
        .trimIndent(),
    )

    // Second build should succeed (not fail with IC cache errors)
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor("lib"))?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)

    ifJvmTarget {
      val secondOutput = project.invokeMain<String>()
      assertThat(secondOutput).isEqualTo("modified,1,key1,from-module")
    }
  }

  /**
   * Regression test for https://github.com/ZacSweers/metro/issues/2150
   *
   * Reproduces an IC issue with a multibinding that uses `@DefaultBinding` on an abstract supertype
   * while `generateContributionProviders` is enabled. Sequence from the linked repro:
   * 1. Build with `Set<ViewModel<*, *>>` accessor and its usage in main — succeeds.
   * 2. Comment out the accessor and its usage — rebuild succeeds.
   * 3. Restore both — rebuild should succeed but IC fails.
   */
  @Test
  fun defaultBindingIntoSetWithContributionProvidersIc() {
    val fixture =
      object :
        MetroProject(metroOptions = MetroOptionOverrides(generateContributionProviders = true)) {
        override fun sources() = listOf(viewModel, baseViewModel, mainViewModel, appGraph, main)

        val viewModel =
          source(
            """
            interface ViewModel<S, E>
            """
              .trimIndent()
          )

        val baseViewModel =
          source(
            """
            @DefaultBinding<ViewModel<*, *>>
            abstract class BaseViewModel<S, E> : ViewModel<S, E>
            """
              .trimIndent()
          )

        private val mainViewModel =
          source(
            """
            @Inject
            @ContributesIntoSet(AppScope::class)
            class MainViewModel : BaseViewModel<String, Int>()
            """
              .trimIndent()
          )

        val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val viewModels: Set<ViewModel<*, *>>
            }
            """
              .trimIndent()
          )

        val main =
          source(
            """
            fun main(): Int {
              val graph = createGraph<AppGraph>()
              return graph.viewModels.size
            }
            """
              .trimIndent()
          )
      }

    val project = fixture.gradleProject

    // 1. Initial build with accessor + usage — should succeed and see one ViewModel.
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<Int>()).isEqualTo(1) }
    // 2. Comment out the `viewModels` accessor and its use in main.
    project.modify(
      fixture.appGraph,
      """
      @DependencyGraph(AppScope::class)
      interface AppGraph {
        // val viewModels: Set<ViewModel<*, *>>
      }
      """
        .trimIndent(),
    )
    project.modify(
      fixture.main,
      """
      fun main(): Int {
        val graph = createGraph<AppGraph>()
        // return graph.viewModels.size
        return 0
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<Int>()).isEqualTo(0) }
    // 3. Restore both — IC rebuild should still succeed and see the ViewModel again.
    project.modify(
      fixture.appGraph,
      """
      @DependencyGraph(AppScope::class)
      interface AppGraph {
        val viewModels: Set<ViewModel<*, *>>
      }
      """
        .trimIndent(),
    )
    project.modify(
      fixture.main,
      """
      fun main(): Int {
        val graph = createGraph<AppGraph>()
        return graph.viewModels.size
      }
      """
        .trimIndent(),
    )

    val thirdBuildResult = project.compileKotlin()
    assertThat(thirdBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<Int>()).isEqualTo(1) }
  }

  private fun File.classFileSnapshot(): Map<String, FileSnapshot> {
    val root = this
    return walkTopDown()
      .filter { it.isFile && it.extension == "class" }
      .associate { it.relativeTo(root).path to it.toPath().snapshot }
  }
}
