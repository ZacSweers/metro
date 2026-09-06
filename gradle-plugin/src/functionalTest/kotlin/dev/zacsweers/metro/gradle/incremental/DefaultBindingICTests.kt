// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("UPPER_BOUND_VIOLATED_BASED_ON_JAVA_ANNOTATIONS")

package dev.zacsweers.metro.gradle.incremental

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.gradle.KmpTarget
import dev.zacsweers.metro.gradle.KotlinToolingVersion
import dev.zacsweers.metro.gradle.MetroOptionOverrides
import dev.zacsweers.metro.gradle.MetroProject
import dev.zacsweers.metro.gradle.getTestCompilerToolingVersion
import dev.zacsweers.metro.gradle.source
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Verifies inferred binding types after incremental annotation and supertype changes. */
@RunWith(Parameterized::class)
class DefaultBindingICTests(target: KmpTarget) : BaseIncrementalCompilationTest(target) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun targets(): List<KmpTarget> = KmpTarget.selectedTargets()
  }

  @Test
  fun defaultBindingTypeArgumentChangeDetectedWhenOmittingRedundantMirrors() {
    assumeTrue(getTestCompilerToolingVersion() >= KotlinToolingVersion("2.4.0"))

    val fixture =
      object : MetroProject(metroOptions = MetroOptionOverrides(omitRedundantMirrors = true)) {
        override fun sources() = listOf(baseInterface, impl, graph)

        val baseInterface =
          source(
            """
            @DefaultBinding<BaseFactory<*>>
            interface BaseFactory<T : BaseFactory<T>> : RawFactory
            interface RawFactory
            """
          )

        private val impl =
          source(
            """
            @ContributesBinding(Unit::class)
            @Inject
            class Impl : BaseFactory<Impl>
            """
          )

        private val graph =
          source(
            """
            @DependencyGraph(Unit::class)
            interface AppGraph {
              val base: BaseFactory<*>
            }
            """
          )
      }

    val project = fixture.gradleProject

    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    project.modify(
      fixture.baseInterface,
      """
      @DefaultBinding<RawFactory>
      interface BaseFactory<T : BaseFactory<T>> : RawFactory
      interface RawFactory
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output).contains("[Metro/MissingBinding]")

    project.modify(
      fixture.baseInterface,
      """
      // Restored after the intentionally failing compilation.
      @DefaultBinding<BaseFactory<*>>
      interface BaseFactory<T : BaseFactory<T>> : RawFactory
      interface RawFactory
      """
        .trimIndent(),
    )

    val thirdBuildResult = project.compileKotlin()
    assertThat(thirdBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  /**
   * Tests that changing the `@DefaultBinding` type argument on a supertype triggers recompilation
   * and correctly detects the binding change.
   */
  @Test
  fun changingDefaultBindingTypeDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(baseInterface, impl, graph)

        val baseInterface =
          source(
            """
            @DefaultBinding<BaseFactory<*>>
            interface BaseFactory<T : BaseFactory<T>> : RawFactory
            interface RawFactory
            """
          )

        private val impl =
          source(
            """
            @ContributesBinding(Unit::class)
            @Inject
            class Impl : BaseFactory<Impl>
            """
          )

        private val graph =
          source(
            """
            @DependencyGraph(Unit::class)
            interface AppGraph {
              val base: BaseFactory<*>
            }
            """
          )
      }

    val project = fixture.gradleProject

    // First build should succeed
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Change @DefaultBinding<Base> to @DefaultBinding<Other> — now the implicit binding type
    // is Other, but the graph still requests Base, which should cause a missing binding error
    project.modify(
      fixture.baseInterface,
      """
      @DefaultBinding<RawFactory>
      interface BaseFactory<T : BaseFactory<T>> : RawFactory
      interface RawFactory
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output).contains("[Metro/MissingBinding]")
  }

  /**
   * Tests that removing `@DefaultBinding` from a supertype triggers recompilation and correctly
   * detects the now-ambiguous binding.
   */
  @Test
  fun removingDefaultBindingDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(baseInterface, otherInterface, impl, graph)

        val baseInterface =
          source(
            """
            @DefaultBinding<Base>
            interface Base
            """
          )

        private val otherInterface = source("interface Other")

        private val impl =
          source(
            """
            @ContributesBinding(Unit::class)
            @Inject
            class Impl : Base, Other
            """
          )

        private val graph =
          source(
            """
            @DependencyGraph(Unit::class)
            interface AppGraph {
              val base: Base
            }
            """
          )
      }

    val project = fixture.gradleProject

    // First build should succeed — @DefaultBinding resolves the ambiguity
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Remove @DefaultBinding — now multiple supertypes with no default, should fail
    project.modify(
      fixture.baseInterface,
      """
      interface Base
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output)
      .contains(
        "`@ContributesBinding`-annotated class @dev.zacsweers.metro.ContributesBinding doesn't declare an explicit `binding` type but has multiple supertypes. You must define an explicit bound type in this scenario."
      )
  }

  /**
   * Tests that adding `@DefaultBinding` to a supertype triggers recompilation and correctly
   * resolves a previously ambiguous binding.
   */
  @Test
  fun addingDefaultBindingDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(baseInterface, otherInterface, impl, graph)

        val baseInterface = source("interface Base")

        private val otherInterface = source("interface Other")

        val impl =
          source(
            """
            @ContributesBinding(Unit::class, binding = binding<Base>())
            @Inject
            class Impl : Base, Other
            """
          )

        private val graph =
          source(
            """
            @DependencyGraph(Unit::class)
            interface AppGraph {
              val base: Base
            }
            """
          )
      }

    val project = fixture.gradleProject

    // First build should succeed — explicit binding resolves the ambiguity
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Add @DefaultBinding to Base and remove explicit binding from Impl — should still succeed
    project.modify(
      fixture.baseInterface,
      """
      @DefaultBinding<Base>
      interface Base
      """
        .trimIndent(),
    )
    project.modify(
      fixture.impl,
      """
      @ContributesBinding(Unit::class)
      @Inject
      class Impl : Base, Other
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlin()
    assertThat(secondBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  /**
   * Tests that changing `@DefaultBinding` on a single-supertype interface triggers recompilation.
   *
   * This covers the primary use case: a generic base interface where `@DefaultBinding` specifies a
   * star-projected type so contributors don't need to repeat `binding = binding<Factory<*>>()`.
   * When the default binding type changes, downstream graphs must recompile.
   */
  @Test
  fun changingDefaultBindingOnSingleSupertypeDetected() {
    val fixture =
      object : MetroProject() {
        override fun sources() = listOf(factory, impl, graph)

        val factory =
          source(
            """
            @DefaultBinding<Factory<*>>
            interface Factory<T> {
              fun create(): T
            }
            """
          )

        private val impl =
          source(
            """
            @ContributesBinding(Unit::class)
            @Inject
            class StringFactory : Factory<String> {
              override fun create(): String = "hello"
            }
            """
          )

        private val graph =
          source(
            """
            @DependencyGraph(Unit::class)
            interface AppGraph {
              val factory: Factory<*>
            }
            """
          )
      }

    val project = fixture.gradleProject

    // First build should succeed — @DefaultBinding<Factory<*>> binds as Factory<*>
    val firstBuildResult = project.compileKotlin()
    assertThat(firstBuildResult.task(compileTaskFor())?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    // Change @DefaultBinding<Factory<*>> to @DefaultBinding<Factory<String>> —
    // graph requests Factory<*> but the binding now produces Factory<String>, which should fail
    project.modify(
      fixture.factory,
      """
      @DefaultBinding<Factory<String>>
      interface Factory<T> {
        fun create(): T
      }
      """
        .trimIndent(),
    )

    val secondBuildResult = project.compileKotlinAndFail()
    assertThat(secondBuildResult.output).contains("[Metro/MissingBinding]")
  }
}
