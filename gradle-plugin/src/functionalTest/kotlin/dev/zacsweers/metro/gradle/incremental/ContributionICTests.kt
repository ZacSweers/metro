// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("UPPER_BOUND_VIOLATED_BASED_ON_JAVA_ANNOTATIONS")

package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.gradle.Dependency
import com.autonomousapps.kit.gradle.Dependency.Companion.implementation
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.KotlinToolingVersion
import dev.zacsweers.metro.gradle.MetroOptionOverrides
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.classLoader
import dev.zacsweers.metro.gradle.cleanOutputLine
import dev.zacsweers.metro.gradle.getTestCompilerToolingVersion
import dev.zacsweers.metro.gradle.getTestOmitRedundantMirrorsOverride
import dev.zacsweers.metro.gradle.invokeMain
import dev.zacsweers.metro.gradle.source
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Verifies incremental contribution, scope, and replacement changes. */
@RunWith(Parameterized::class)
class ContributionICTests(target: KmpTarget) : BaseIncrementalCompilationTest(target) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun targets(): List<KmpTarget> = KmpTarget.selectedTargets()
  }

  private val generateClassesInIrEnabled =
    getTestCompilerToolingVersion() >= KotlinToolingVersion("2.4.20-dev-6138")

  private fun someRepositoryProviderRequestPath(): String {
    return if (generateClassesInIrEnabled) {
      "test.SomeRepositoryProvider.someRepository"
    } else {
      "test.SomeRepositoryProvider.MetroContributionToLoggedInScope.someRepository"
    }
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
            interface ExampleGraph {
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

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

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

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
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
            interface ExampleGraph {
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

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    project.modify(
      fixture.contributedInterfaces,
      """
      @Inject
      @ContributesIntoSet(Unit::class)
      class Impl1 : ContributedInterface
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun internalBindingsWithRedundantMirrors() {
    internalBindings(omitRedundantMirrors = false)
  }

  @Test
  fun internalBindingsWithoutRedundantMirrors() {
    assumeTrue(getTestCompilerToolingVersion() >= KotlinToolingVersion("2.4.0"))
    internalBindings(omitRedundantMirrors = true)
  }

  private fun internalBindings(omitRedundantMirrors: Boolean) {
    val fixture =
      object :
        MetroProject(
          metroOptions = MetroOptionOverrides(omitRedundantMirrors = omitRedundantMirrors)
        ) {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(exampleGraph)
            dependencies(
              implementation(":lib:impl"),
              implementation(":scopes"),
              implementation(":graphs"),
            )
          }
          subproject("scopes") { sources(scopes) }
          subproject("graphs") {
            sources(graphs)
            dependencies(implementation(":scopes"))
          }
          subproject("lib") {
            sources(repo)
            dependencies(implementation(":scopes"))
          }
          subproject("lib:impl") {
            sources(repoImpl)
            dependencies(implementation(":scopes"), Dependency.api(":lib"))
          }
        }

        private val scopes =
          source(
            """
          abstract class LoggedInScope private constructor()
        """
          )

        private val graphs =
          source(
            """
          @GraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            @ContributesTo(AppScope::class)
            @GraphExtension.Factory
            interface Factory {
              fun create(): LoggedInGraph
            }
          }
        """
          )

        private val exampleGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface ExampleGraph {
              val loggedInGraphFactory: LoggedInGraph.Factory
            }
          """
          )

        val repo =
          source(
            """
            interface SomeRepository

            @ContributesTo(LoggedInScope::class)
            interface SomeRepositoryProvider {
              val someRepository: SomeRepository
            }
          """
          )

        val repoImpl =
          source(
            """
            @ContributesBinding(LoggedInScope::class)
            @Inject
            internal class SomeRepositoryImpl : SomeRepository
          """
          )
      }
    val project = fixture.gradleProject

    val firstBuildResult = project.compileKotlinAndFail()

    // Asserted in pieces: the trace line wraps (or not) at 100 columns depending on the
    // version-dependent request path length.
    val output = firstBuildResult.output.cleanOutputLine()
    assertThat(output).contains("e: ExampleGraph.kt:6:11")
    assertThat(output).contains("[Metro/MissingBinding] No binding found for SomeRepository")
    assertThat(output).contains("trace (in test.ExampleGraph.Impl.LoggedInGraphImpl):")
    assertThat(output).contains("SomeRepository is requested at")
    assertThat(output).contains(someRepositoryProviderRequestPath())
    assertThat(output).contains("similar bindings:")
    assertThat(output)
      .contains(
        "- SomeRepository (Contributed by 'test.SomeRepositoryImpl' but that class is internal to its"
      )
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
            interface ExampleGraph
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

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    ifJvmTarget {
      with(project.classLoader()) {
        val exampleGraph = loadClass("test.ExampleGraph")
        val contributedInterface2 = loadClass("test.ContributedInterface2")
        assertThat(contributedInterface2.isAssignableFrom(exampleGraph)).isTrue()
      }
    }

    project.modify(
      fixture.contributedInterfaces,
      """
      @ContributesTo(Unit::class)
      interface ContributedInterface1
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Check that ContributedInterface2 was removed as a supertype
    ifJvmTarget {
      val classLoader = project.classLoader()
      val exampleGraph = classLoader.loadClass("test.ExampleGraph")
      val interfaceNames = exampleGraph.interfaces.map { it.name }
      assertThat(interfaceNames).doesNotContain("test.ContributedInterface2")
      assertThat(interfaceNames)
        .doesNotContain("test.ContributedInterface2\$MetroContributionToUnit")
    }
  }

  @Test
  fun scopingChangeOnProviderIsDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(exampleGraph, main)

        val exampleGraph =
          source(
            """
            @DependencyGraph(Unit::class)
            abstract class ExampleGraph {
              abstract val int: Int

              private var count: Int = 0

              @Provides fun provideInt(): Int = count++
            }
            """
              .trimIndent()
          )

        private val main =
          source(
            """
            fun main(): Int {
              val graph = createGraph<ExampleGraph>()
              return graph.int + graph.int
            }
            """
              .trimIndent()
          )
      }
    val project = fixture.gradleProject

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    ifJvmTarget { assertThat(project.invokeMain<Int>()).isEqualTo(1) }
    project.modify(
      fixture.exampleGraph,
      """
      @DependencyGraph(Unit::class)
      abstract class ExampleGraph {
        abstract val int: Int

        private var count: Int = 0

        @Provides @SingleIn(Unit::class) fun provideInt(): Int = count++
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Check that count is scoped now and never increments
    ifJvmTarget { assertThat(project.invokeMain<Int>()).isEqualTo(0) }
    project.modify(
      fixture.exampleGraph,
      """
      @DependencyGraph(Unit::class)
      abstract class ExampleGraph {
        abstract val int: Int

        private var count: Int = 0

        @Provides fun provideInt(): Int = count++
      }
      """
        .trimIndent(),
    )

    val thirdBuildResult = project.compileKotlin()
    assertThat(thirdBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Check that count is unscoped again and increments
    ifJvmTarget { assertThat(project.invokeMain<Int>()).isEqualTo(1) }
  }

  @Test
  fun scopingChangeOnContributedClassIsDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(exampleClass, exampleGraph, main)

        val exampleClass =
          source(
            """
            @ContributesBinding(Unit::class)
            @Inject
            class ExampleClass : Counter {
              override var count: Int = 0
            }
            """
              .trimIndent()
          )

        private val exampleGraph =
          source(
            """
                interface Counter {
                  var count: Int
                }
            @SingleIn(AppScope::class)
            @DependencyGraph(Unit::class)
            interface ExampleGraph {
              val counter: Counter
            }
            """
              .trimIndent()
          )

        private val main =
          source(
            """
            fun main(): Int {
              val graph = createGraph<ExampleGraph>()
              return graph.counter.count++ + graph.counter.count++
            }
            """
              .trimIndent()
          )
      }
    val project = fixture.gradleProject

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    ifJvmTarget { assertThat(project.invokeMain<Int>()).isEqualTo(0) }
    project.modify(
      fixture.exampleClass,
      """
      @SingleIn(AppScope::class)
      @ContributesBinding(Unit::class)
      @Inject
      class ExampleClass : Counter {
        override var count: Int = 0
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Check that count is scoped now and never increments
    ifJvmTarget { assertThat(project.invokeMain<Int>()).isEqualTo(1) }
    project.modify(
      fixture.exampleClass,
      """
      @ContributesBinding(Unit::class)
      @Inject
      class ExampleClass : Counter {
        override var count: Int = 0
      }
      """
        .trimIndent(),
    )

    val thirdBuildResult = project.compileKotlin()
    assertThat(thirdBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Check that count is unscoped again and increments
    ifJvmTarget { assertThat(project.invokeMain<Int>()).isEqualTo(0) }
  }

  @Test
  fun scopingChangeOnNonContributedClassIsDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() =
          listOf(unusedScope, exampleClass, exampleGraph, loggedInGraph, main)

        val unusedScope =
          source(
            """
            interface UnusedScope
            """
              .trimIndent()
          )

        val exampleClass =
          source(
            """
            @Inject
            @SingleIn(UnusedScope::class)
            class ExampleClass
            """
              .trimIndent()
          )

        private val exampleGraph =
          source(
            """
            @DependencyGraph(scope = AppScope::class)
            interface ExampleGraph
            """
              .trimIndent()
          )

        private val loggedInGraph =
          source(
            """
            sealed interface LoggedInScope

            @GraphExtension(LoggedInScope::class)
            interface LoggedInGraph {
              val exampleClass: ExampleClass

                @ContributesTo(AppScope::class)
                @GraphExtension.Factory
                interface Factory {
                    fun createLoggedInGraph(): LoggedInGraph
                }
            }
            """
              .trimIndent()
          )

        private val main =
          source(
            """
            fun main(): Any {
              val graph = createGraph<ExampleGraph>().createLoggedInGraph()
              return graph.exampleClass
            }
            """
              .trimIndent()
          )
      }
    val project = fixture.gradleProject

    // First build should fail because [ExampleClass] is scoped incompatibly with both graph nodes
    val firstBuildResult = project.compileKotlinAndFail()

    assertThat(firstBuildResult.output.cleanOutputLine())
      .contains(
        """
        e: LoggedInScope.kt:8:11 [Metro/IncompatiblyScopedBindings] test.ExampleGraph.Impl.LoggedInGraphImpl (scopes
            '@SingleIn(LoggedInScope::class)') may not reference bindings from different scopes

          trace (in test.ExampleGraph.Impl.LoggedInGraphImpl):
              ExampleClass (scoped to '@SingleIn(UnusedScope::class)')
              ExampleClass is requested at test.LoggedInGraph.exampleClass

          note: LoggedInGraphImpl is contributed by 'test.LoggedInGraph' to 'test.ExampleGraph'
          docs: https://zacsweers.github.io/metro/latest/diagnostics/#incompatiblyscopedbindings
        """
          .trimIndent()
      )

    project.modify(
      fixture.exampleClass,
      """
      @Inject
      @SingleIn(AppScope::class)
      class ExampleClass
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    ifJvmTarget {
      with(project.classLoader()) {
        val mainClass = loadClass("test.MainKt")
        val scopedDep = mainClass.declaredMethods.first { it.name == "main" }.invoke(null) as Any
        assertThat(scopedDep).isNotNull()
      }
    }

    val omitRedundantMirrorsEnabled = getTestOmitRedundantMirrorsOverride() == true
    val annotationArgumentChangesSupported =
      getTestCompilerToolingVersion() >= KotlinToolingVersion("2.4.0")
    val requiresAnnotationRemovalWorkaround =
      !omitRedundantMirrorsEnabled || !annotationArgumentChangesSupported
    if (requiresAnnotationRemovalWorkaround) {
      project.modify(
        fixture.exampleClass,
        """
        @Inject
        class ExampleClass
        """
          .trimIndent(),
      )

      val workaroundBuildResult = project.compileKotlin()
      assertThat(workaroundBuildResult.task(compileTaskFor())?.outcome)
        .isEqualTo(TaskOutcome.SUCCESS)
    }

    project.modify(
      fixture.exampleClass,
      """
      @Inject
      @SingleIn(UnusedScope::class)
      class ExampleClass
      """
        .trimIndent(),
    )

    // We expect that changing the source back to what we started with should again give us the
    // original error
    val finalBuildResult = project.compileKotlinAndFail()
    assertThat(finalBuildResult.output.cleanOutputLine())
      .contains(
        """
        [Metro/IncompatiblyScopedBindings] test.ExampleGraph.Impl.LoggedInGraphImpl (scopes
            '@SingleIn(LoggedInScope::class)') may not reference bindings from different scopes

          trace (in test.ExampleGraph.Impl.LoggedInGraphImpl):
              ExampleClass (scoped to '@SingleIn(UnusedScope::class)')
              ExampleClass is requested at test.LoggedInGraph.exampleClass

          note: LoggedInGraphImpl is contributed by 'test.LoggedInGraph' to 'test.ExampleGraph'
        """
          .trimIndent()
      )
  }

  @Test
  fun icWorksWhenChangingAContributionScope() {
    val fixture =
      object : MetroProject() {
        override fun sources() =
          listOf(unusedScope, exampleClass, exampleGraph, loggedInGraph, main)

        val unusedScope =
          source(
            """
            interface UnusedScope
            interface Foo
            """
              .trimIndent()
          )

        val exampleClass =
          source(
            """
            @Inject
            @ContributesBinding(UnusedScope::class)
            class ExampleClass : Foo
            """
              .trimIndent()
          )

        private val exampleGraph =
          source(
            """
            @DependencyGraph(scope = AppScope::class)
            interface ExampleGraph
            """
              .trimIndent()
          )

        private val loggedInGraph =
          source(
            """
            sealed interface LoggedInScope

            @GraphExtension(LoggedInScope::class)
            interface LoggedInGraph {
              val childDependency: Foo

                @ContributesTo(AppScope::class)
                @GraphExtension.Factory
                interface Factory {
                    fun createLoggedInGraph(): LoggedInGraph
                }
            }
            """
              .trimIndent()
          )

        private val main =
          source(
            """
            fun main(): Any {
              val graph = createGraph<ExampleGraph>().createLoggedInGraph()
              return graph.childDependency
            }
            """
              .trimIndent()
          )
      }
    val project = fixture.gradleProject

    // First build should fail because `ExampleClass` is not contributed to the scopes of either
    // graph
    val firstBuildResult = project.compileKotlinAndFail()

    assertThat(firstBuildResult.output.cleanOutputLine())
      .contains(
        """
        e: LoggedInScope.kt:9:7 [Metro/MissingBinding] No binding found for Foo

          trace (in test.ExampleGraph.Impl.LoggedInGraphImpl):
              Foo is requested at test.LoggedInGraph.childDependency

          help: ensure Foo has an @Inject constructor or is provided by an @Provides or @Binds declaration
                visible to LoggedInGraphImpl
          docs: https://zacsweers.github.io/metro/latest/diagnostics/#missingbinding
        """
          .trimIndent()
      )

    // Change to contribute to the scope of the root graph node -- will pass
    project.modify(
      fixture.exampleClass,
      """
      @Inject
      @ContributesBinding(AppScope::class)
      class ExampleClass : Foo
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    ifJvmTarget {
      with(project.classLoader()) {
        val mainClass = loadClass("test.MainKt")
        val scopedDep = mainClass.declaredMethods.first { it.name == "main" }.invoke(null) as Any
        assertThat(scopedDep).isNotNull()
      }
    }

    // Change back to the original state -- should fail again for a missing binding
    project.modify(
      fixture.exampleClass,
      """
      @Inject
      @ContributesBinding(UnusedScope::class)
      class ExampleClass : Foo
      """
        .trimIndent(),
    )

    val thirdBuildResult = project.compileKotlinAndFail()
    assertThat(thirdBuildResult.output.cleanOutputLine())
      // Omit 'e: ExampleGraph.kt:6:11 ' prefix until 2.3.0+ as we report a more accurate location
      // there
      .contains(
        """
        [Metro/MissingBinding] No binding found for Foo

          trace (in test.ExampleGraph.Impl.LoggedInGraphImpl):
              Foo is requested at test.LoggedInGraph.childDependency
        """
          .trimIndent()
      )
  }

  @Test
  fun multipleBindingReplacementsAreRespectedWhenAddingNewContribution() {
    val fixture =
      object : MetroProject(debug = true) {
        override fun buildGradleProject() = multiModuleProject {
          root {
            sources(appGraph, fakeImpl, main)
            dependencies(implementation(":common"), implementation(":lib"))
          }
          subproject("common") { sources(fooBar) }
          subproject("lib") {
            sources(realImpl)
            dependencies(implementation(":common"))
          }
        }

        private val appGraph =
          source(
            """
            @DependencyGraph(AppScope::class)
            interface AppGraph {
              val bar: Bar
            }
            """
              .trimIndent()
          )

        private val fooBar =
          source(
            """
            interface Foo
            interface Bar : Foo {
              val str: String
            }
            """
              .trimIndent()
          )

        val realImpl =
          source(
            """
            @Inject
            @ContributesBinding(AppScope::class, binding = binding<Foo>())
            @ContributesBinding(AppScope::class, binding = binding<Bar>())
            class RealImpl : Bar {
              override val str: String = "real"
            }
            """
              .trimIndent()
          )

        private val fakeImpl =
          source(
            """
            @Inject
            @ContributesBinding(AppScope::class, binding = binding<Foo>(), replaces = [RealImpl::class])
            @ContributesBinding(AppScope::class, binding = binding<Bar>(), replaces = [RealImpl::class])
            class FakeImpl : Bar {
              override val str: String = "fake"
            }
            """
              .trimIndent()
          )

        val placeholder = source("")

        val main =
          source(
            """
            fun main(): String {
              val graph = createGraph<AppGraph>()
              return graph.bar.str
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
        val string = mainClass.declaredMethods.first { it.name == "main" }.invoke(null) as String
        assertThat(string).isEqualTo("fake")
      }
    }

    buildAndAssertOutput()

    // Adding a new binding contribution should be alright
    libProject.modify(
      project.rootDir,
      fixture.placeholder,
      """
      interface Baz

      @Inject
      @ContributesBinding(AppScope::class)
      class BazImpl : Baz
      """
        .trimIndent(),
    )

    buildAndAssertOutput()
  }

  @Test
  fun mapKeyArgumentChangeDetectedWhenOmittingRedundantMirrors() {
    assumeTrue(getTestCompilerToolingVersion() >= KotlinToolingVersion("2.4.0"))

    val fixture =
      object : MetroProject(metroOptions = MetroOptionOverrides(omitRedundantMirrors = true)) {
        override fun sources() = listOf(bindingContainer, graph)

        val bindingContainer =
          source(
            """
            @BindingContainer
            object MapBindings {
              @Provides
              @IntoMap
              @StringKey("first")
              fun provideFirst(): String = "first"

              @Provides
              @IntoMap
              @StringKey("second")
              fun provideSecond(): String = "second"
            }
            """
              .trimIndent()
          )

        private val graph =
          source(
            """
            @DependencyGraph(bindingContainers = [MapBindings::class])
            interface AppGraph {
              val values: Map<String, String>
            }
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
      object MapBindings {
        @Provides
        @IntoMap
        @StringKey("second")
        fun provideFirst(): String = "first"

        @Provides
        @IntoMap
        @StringKey("second")
        fun provideSecond(): String = "second"
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output).contains("[Metro/DuplicateMapKeys]")

    project.modify(
      fixture.bindingContainer,
      """
      @BindingContainer
      object MapBindings {
        // Restored after the intentionally failing compilation.
        @Provides
        @IntoMap
        @StringKey("first")
        fun provideFirst(): String = "first"

        @Provides
        @IntoMap
        @StringKey("second")
        fun provideSecond(): String = "second"
      }
      """
        .trimIndent(),
    )

    val thirdBuildResult = project.compileKotlin()
    assertThat(thirdBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }
}
