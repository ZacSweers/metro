// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("UPPER_BOUND_VIOLATED_BASED_ON_JAVA_ANNOTATIONS")

package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.GradleBuilder.build
import com.autonomousapps.kit.gradle.Dependency
import com.autonomousapps.kit.gradle.Dependency.Companion.implementation
import com.autonomousapps.kit.gradle.Plugin
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.GradlePlugins
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.getTestCompilerVersion
import dev.zacsweers.metro.gradle.invokeMain
import dev.zacsweers.metro.gradle.source
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Verifies assisted factories and their consuming graphs after incremental changes. */
@RunWith(Parameterized::class)
class AssistedInjectionICTests(target: KmpTarget) : BaseIncrementalCompilationTest(target) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun targets(): List<KmpTarget> = KmpTarget.selectedTargets()
  }

  /**
   * Tests that adding a new injected (non-assisted) parameter to an @AssistedInject class is
   * correctly detected during incremental compilation. The factory consumer should see that the
   * underlying target class has changed and regenerate the factory accordingly.
   */
  @Test
  fun `adding non-assisted param to an assisted inject class is detected in IC with the factory`() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(assistedClass, graphAndMain)

        val assistedClass =
          source(
            """
            @AssistedInject
            class AssistedClass(
              @Assisted val id: String,
              val message: String,
            ) {
              fun call(): String = message + id

              @AssistedFactory
              fun interface Factory {
                fun create(id: String): AssistedClass
              }
            }
            """
              .trimIndent()
          )

        val graphAndMain =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              val factory: AssistedClass.Factory

              @Provides fun provideString(): String = "Hello, "
              @Provides fun provideInt(): Int = 42
            }

            fun main(): String {
              val graph = createGraph<AppGraph>()
              return graph.factory.create("world").call()
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "Main",
          )
      }

    val project = fixture.gradleProject

    // First build should succeed and run correctly
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("Hello, world") }
    // Add a new non-assisted parameter (count: Int) to the assisted class
    project.modify(
      fixture.assistedClass,
      """
      @AssistedInject
      class AssistedClass(
        @Assisted val id: String,
        val message: String,
        val count: Int,
      ) {
        fun call(): String = message + id + count

        @AssistedFactory
        fun interface Factory {
          fun create(id: String): AssistedClass
        }
      }
      """
        .trimIndent(),
    )

    // Second build should succeed and the factory should pick up the new parameter
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("Hello, world42") }
  }

  @Test
  fun `adding non-assisted param to an assisted inject class is detected in IC with the factory in a separate file`() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(assistedClass, assistedFactory, graphAndMain)

        val assistedClass =
          source(
            """
            @AssistedInject
            class AssistedClass(
              @Assisted val id: String,
              val message: String,
            ) {
              fun call(): String = message + id
            }
            """
              .trimIndent()
          )

        val assistedFactory =
          source(
            """
            @AssistedFactory
            fun interface AssistedClassFactory {
              fun create(id: String): AssistedClass
            }
            """
              .trimIndent()
          )

        val graphAndMain =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              val factory: AssistedClassFactory

              @Provides fun provideString(): String = "Hello, "
              @Provides fun provideInt(): Int = 42
            }

            fun main(): String {
              val graph = createGraph<AppGraph>()
              return graph.factory.create("world").call()
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "Main",
          )
      }

    val project = fixture.gradleProject

    // First build should succeed and run correctly
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("Hello, world") }
    // Add a new non-assisted parameter (count: Int) to the assisted class
    project.modify(
      fixture.assistedClass,
      """
      @AssistedInject
      class AssistedClass(
        @Assisted val id: String,
        val message: String,
        val count: Int,
      ) {
        fun call(): String = message + id + count
      }
      """
        .trimIndent(),
    )

    // Second build should succeed and the factory should pick up the new parameter
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("Hello, world42") }
  }

  @Test
  fun `adding non-assisted param to an assisted inject class in a separate module is detected in IC`() {
    val fixture =
      object : MetroProject() {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(graphAndMain)
            dependencies(implementation(":lib"))
          }
          subproject("lib") { sources(assistedClass) }
        }

        val assistedClass =
          source(
            """
            @AssistedInject
            class AssistedClass(
              @Assisted val id: String,
              val message: String,
            ) {
              fun call(): String = message + id

              @AssistedFactory
              fun interface Factory {
                fun create(id: String): AssistedClass
              }
            }
            """
              .trimIndent()
          )

        val graphAndMain =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              val factory: AssistedClass.Factory

              @Provides fun provideString(): String = "Hello, "
              @Provides fun provideInt(): Int = 42
            }

            fun main(): String {
              val graph = createGraph<AppGraph>()
              return graph.factory.create("world").call()
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "Main",
          )
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    // First build should succeed and run correctly
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("Hello, world") }
    // Add a new non-assisted parameter (count: Int) to the assisted class in the lib module
    libProject.modify(
      project.rootDir,
      fixture.assistedClass,
      """
      @AssistedInject
      class AssistedClass(
        @Assisted val id: String,
        val message: String,
        val count: Int,
      ) {
        fun call(): String = message + id + count

        @AssistedFactory
        fun interface Factory {
          fun create(id: String): AssistedClass
        }
      }
      """
        .trimIndent(),
    )

    // Second build should succeed and the factory should pick up the new parameter
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("Hello, world42") }
  }

  @Test
  fun `adding non-assisted param to an assisted inject class is detected across three modules`() {
    val fixture =
      object : MetroProject() {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(graphAndMain)
            dependencies(implementation(":factory"), implementation(":lib"))
          }
          subproject("factory") {
            sources(assistedFactory)
            dependencies(implementation(":lib"))
          }
          subproject("lib") { sources(assistedClass) }
        }

        val assistedClass =
          source(
            """
            @AssistedInject
            class AssistedClass(
              @Assisted val id: String,
              val message: String,
            ) {
              fun call(): String = message + id
            }
            """
              .trimIndent()
          )

        val assistedFactory =
          source(
            """
            @AssistedFactory
            fun interface AssistedClassFactory {
              fun create(id: String): AssistedClass
            }
            """
              .trimIndent()
          )

        val graphAndMain =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              val factory: AssistedClassFactory

              @Provides fun provideString(): String = "Hello, "
              @Provides fun provideInt(): Int = 42
            }

            fun main(): String {
              val graph = createGraph<AppGraph>()
              return graph.factory.create("world").call()
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "Main",
          )
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    // First build should succeed and run correctly
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("Hello, world") }
    // Add a new non-assisted parameter (count: Int) to the assisted class in the lib module
    libProject.modify(
      project.rootDir,
      fixture.assistedClass,
      """
      @AssistedInject
      class AssistedClass(
        @Assisted val id: String,
        val message: String,
        val count: Int,
      ) {
        fun call(): String = message + id + count
      }
      """
        .trimIndent(),
    )

    // Second build should succeed and the factory should pick up the new parameter
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("Hello, world42") }
  }

  /**
   * Tests that removing a non-assisted parameter from an @AssistedInject class' constructor is
   * correctly detected during incremental compilation when the factory is contributed into a set
   * via a @BindingContainer.
   *
   * The three-module layout is critical:
   * - `lib` owns AssistedClass (@AssistedInject with multiple non-assisted params).
   * - `middle` owns BaseFactory and the @BindingContainer that @Provides @IntoSet the factory; its
   *   ABI does not change when AssistedClass loses a constructor param because
   *   AssistedClass.Factory (the interface) is unchanged.
   * - `root` depends only on `middle` (directly), so Metro reads AssistedClass metadata during
   *   root's recompilation from a stale cache and regenerates AppGraph$Impl with the old Provider
   *   arity, producing a NoSuchMethodError at runtime.
   */
  @Test
  fun `removing non-assisted param from an assisted inject class is detected in IC`() {
    val fixture =
      object : MetroProject() {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(graphAndMain)
            dependencies(implementation(":middle"))
          }
          subproject("middle") {
            sources(baseFactory, assistedModule)
            // api so that AssistedClass is on root's compile classpath (Metro needs it to resolve
            // the AssistedFactory binding). Root's Kotlin *source* never references AssistedClass
            // directly, so Kotlin IC won't recompile root when AssistedClass's ABI changes —
            // only Metro's own IC tracking can detect and propagate the change.
            dependencies(Dependency.api(":lib"))
          }
          subproject("lib") { sources(assistedClass) }
        }

        val assistedClass =
          source(
            """
            @AssistedInject
            class AssistedClass(
              @Assisted val id: String,
              val message: String,
              val count: Int,
            ) {
              @AssistedFactory
              fun interface Factory {
                fun create(id: String): AssistedClass
              }
            }
            """
              .trimIndent()
          )

        // BaseFactory lives in :middle so that root's sources never reference :lib at all.
        val baseFactory =
          source(
            """
            interface BaseFactory {
              fun create(id: String): Any
            }
            """
              .trimIndent()
          )

        val assistedModule =
          source(
            """
            @BindingContainer
            @ContributesTo(AppScope::class)
            interface AssistedModule {
              companion object {
                @Provides
                @IntoSet
                fun bindFactory(impl: AssistedClass.Factory): BaseFactory {
                  return object : BaseFactory {
                    override fun create(id: String) = impl.create(id)
                  }
                }
              }

              @Multibinds(allowEmpty = true)
              fun bindFactories(): Set<BaseFactory>
            }
            """
              .trimIndent()
          )

        val graphAndMain =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val factories: Set<BaseFactory>

              @Provides fun provideString(): String = "Hello, "
              @Provides fun provideInt(): Int = 42
            }

            fun main(): Int {
              val graph = createGraph<AppGraph>()
              return graph.factories.size
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "Main",
          )
      }

    val project = fixture.gradleProject
    val libProject = project.subprojects.first { it.name == "lib" }

    // First build should succeed: 1 factory contributed into the set
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<Int>()).isEqualTo(1) }
    // Remove the non-assisted parameter (count: Int) from AssistedClass in lib.
    // This changes AssistedClass.MetroFactory.Companion.create() from a 2-Provider overload
    // to a 1-Provider overload. Kotlin IC does recompile root (via the api dep chain), but
    // Metro re-generates AppGraph$Impl using stale cached metadata for AssistedClass and
    // still emits a call to the old 2-Provider create() → NoSuchMethodError at runtime.
    libProject.modify(
      project.rootDir,
      fixture.assistedClass,
      """
      @AssistedInject
      class AssistedClass(
        @Assisted val id: String,
        val message: String,
      ) {
        @AssistedFactory
        fun interface Factory {
          fun create(id: String): AssistedClass
        }
      }
      """
        .trimIndent(),
    )

    // Second build compiles successfully but Metro uses stale metadata for AssistedClass and
    // generates the wrong create() arity. invokeMain throws NoSuchMethodError until the fix.
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<Int>()).isEqualTo(1) }
  }

  /**
   * Regression test for https://github.com/ZacSweers/metro/issues/2531. With the Compose compiler
   * plugin applied, IC reports the assisted target and generated factory as changed classes rather
   * than changed members. Metro must record a class lookup to invalidate the consuming graph.
   */
  @Test
  fun `removing assisted inject dependency updates graph extension with Compose plugin in IC`() {
    assumeTrue(target == KmpTarget.JVM)

    val fixture =
      object : MetroProject(multiplatform = false) {
        private val composePlugin =
          Plugin("org.jetbrains.kotlin.plugin.compose", getTestCompilerVersion())
        private val composeRuntimeDependency =
          """
          dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.10.3")
          }
          """
            .trimIndent()

        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(graphAndMain)
            plugins(GradlePlugins.Kotlin.jvm(), composePlugin, GradlePlugins.metro)
            dependencies(implementation(":feature"))
            buildScript { withKotlin(composeRuntimeDependency) }
          }
          subproject("feature") {
            sources(featureTypes, assistedViewModel)
            plugins(GradlePlugins.Kotlin.jvm(), composePlugin, GradlePlugins.metro)
            buildScript { withKotlin(composeRuntimeDependency) }
          }
        }

        private val featureTypes =
          source(
            """
            abstract class ActivityRetainedScope private constructor()

            interface FirstRepository {
              val name: String
            }

            interface SecondRepository {
              val name: String
            }

            @Inject
            class DefaultFirstRepository : FirstRepository {
              override val name = "first"
            }

            @Inject
            class DefaultSecondRepository : SecondRepository {
              override val name = "second"
            }

            @BindingContainer
            @ContributesTo(AppScope::class)
            interface RepositoryModule {
              @Binds fun bindFirst(impl: DefaultFirstRepository): FirstRepository
              @Binds fun bindSecond(impl: DefaultSecondRepository): SecondRepository
            }

            interface ManualFactory {
              fun create(screenName: String): Any
            }

            @MapKey(implicitClassKey = true)
            annotation class ManualFactoryKey(
              val value: kotlin.reflect.KClass<out ManualFactory> = Nothing::class
            )
            """
              .trimIndent(),
            fileNameWithoutExtension = "FeatureTypes",
          )

        val assistedViewModel =
          source(
            """
            @AssistedInject
            class SampleViewModel(
              @param:Assisted private val screenName: String,
              firstRepository: FirstRepository,
              secondRepository: SecondRepository,
            ) {
              private val message =
                "${'$'}screenName:${'$'}{firstRepository.name}:${'$'}{secondRepository.name}"

              override fun toString(): String = message

              @AssistedFactory
              @ManualFactoryKey
              @ContributesIntoMap(ActivityRetainedScope::class)
              interface Factory : ManualFactory {
                override fun create(screenName: String): SampleViewModel
              }
            }
            """
              .trimIndent()
          )

        private val graphAndMain =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph

            @GraphExtension(ActivityRetainedScope::class)
            interface ActivityRetainedGraph {
              val factories: Map<kotlin.reflect.KClass<out ManualFactory>, () -> ManualFactory>

              @ContributesTo(AppScope::class)
              @GraphExtension.Factory
              fun interface Factory {
                fun createActivityRetainedGraph(): ActivityRetainedGraph
              }
            }

            fun main(): String {
              val appGraph = createGraph<AppGraph>()
              val retainedGraph =
                appGraph
                  .asContribution<ActivityRetainedGraph.Factory>()
                  .createActivityRetainedGraph()
              return retainedGraph.factories.values.single().invoke().create("home").toString()
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "Main",
          )
      }

    val project = fixture.gradleProject
    val featureProject = project.subprojects.first { it.name == "feature" }

    val firstBuildResult = build(project.rootDir, ":compileKotlin")
    assertThat(firstBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.invokeMain<String>(target = null)).isEqualTo("home:first:second")

    featureProject.modify(
      project.rootDir,
      fixture.assistedViewModel,
      """
      @AssistedInject
      class SampleViewModel(
        @param:Assisted private val screenName: String,
        firstRepository: FirstRepository,
      ) {
        private val message = "${'$'}screenName:${'$'}{firstRepository.name}"

        override fun toString(): String = message

        @AssistedFactory
        @ManualFactoryKey
        @ContributesIntoMap(ActivityRetainedScope::class)
        interface Factory : ManualFactory {
          override fun create(screenName: String): SampleViewModel
        }
      }
      """
        .trimIndent(),
      sourceSet = "main",
    )

    val secondBuildResult = build(project.rootDir, ":compileKotlin")
    assertThat(secondBuildResult.task(":compileKotlin")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(project.invokeMain<String>(target = null)).isEqualTo("home:first")
  }

  /**
   * Tests that auto-generated assisted factories (via `generateAssistedFactories.set(true)`) work
   * correctly under incremental compilation when only the graph file changes.
   *
   * The auto-generated Factory interface and its `create()` function are produced by
   * `AssistedFactoryFirGenerator` during FIR. Under IC, if the file containing the
   * `@AssistedInject` class is not dirty, the Factory is loaded from the IC cache. The IR phase
   * must still be able to find the abstract `create()` function on the cached Factory class.
   *
   * Regression test for https://github.com/ZacSweers/metro/issues/1887
   */
  @Test
  fun `auto-generated assisted factory works under IC when only graph file changes`() {
    val fixture =
      object : MetroProject() {
        override fun StringBuilder.onBuildScript() {
          appendLine(
            """
            metro {
              generateAssistedFactories.set(true)
            }
            """
              .trimIndent()
          )
        }

        val assistedClass =
          source(
            """
            @AssistedInject
            class AssistedClass(
              @Assisted val id: String,
              val message: String,
            ) {
              fun call(): String = message + id
            }
            """
              .trimIndent()
          )

        // main() is in a separate file so it is not dirty when only the graph changes.
        // This avoids FIR re-resolution of .create() in the dirty file; the IC bug
        // manifests at the IR level (singleAbstractFunction) when processing the graph.
        val mainFile =
          source(
            """
            fun main(): String {
              val graph = createGraph<AppGraph>()
              return graph.factory.create("world").call()
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "Main",
          )

        val graphFile =
          source(
            """
            @DependencyGraph
            interface AppGraph {
              val factory: AssistedClass.Factory

              @Provides fun provideString(): String = "Hello, "
            }
            """
              .trimIndent(),
            fileNameWithoutExtension = "AppGraph",
          )

        override fun sources() = listOf(assistedClass, graphFile, mainFile)
      }

    val project = fixture.gradleProject

    // First build (clean) should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("Hello, world") }
    // Modify only the graph file — the @AssistedInject class file is not dirty.
    // Under IC, the auto-generated Factory is loaded from cache.
    project.modify(
      fixture.graphFile,
      """
      @DependencyGraph
      interface AppGraph {
        val factory: AssistedClass.Factory

        @Provides fun provideString(): String = "Hi, "
      }
      """
        .trimIndent(),
    )

    // Second build (incremental) should succeed — the IC-cached Factory must still
    // have its abstract create() function visible to the IR phase.
    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    ifJvmTarget { assertThat(project.invokeMain<String>()).isEqualTo("Hi, world") }
  }
}
