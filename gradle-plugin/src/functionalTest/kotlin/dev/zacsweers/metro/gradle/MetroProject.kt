// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.AbstractGradleProject
import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.GradleProject.DslKind
import com.autonomousapps.kit.RootProject
import com.autonomousapps.kit.Source
import com.autonomousapps.kit.Source.Companion.DEFAULT_SOURCE_SET
import com.autonomousapps.kit.gradle.BuildScript
import com.autonomousapps.kit.gradle.Dependency
import com.autonomousapps.kit.gradle.DependencyResolutionManagement
import com.autonomousapps.kit.gradle.Plugin
import com.autonomousapps.kit.gradle.PluginManagement
import com.autonomousapps.kit.gradle.Repositories
import com.autonomousapps.kit.gradle.Repository
import com.google.errorprone.annotations.CanIgnoreReturnValue
import org.intellij.lang.annotations.Language

abstract class MetroProject(
  private val debug: Boolean = false,
  private val metroOptions: MetroOptionOverrides = MetroOptionOverrides(),
  private val reportsEnabled: Boolean = true,
  private val kotlinVersion: String? = null,
  private val multiplatform: Boolean = true,
) : AbstractGradleProject() {

  /** Source set this project's [source] declarations target by default. */
  private val defaultSourceSet: String = if (multiplatform) "commonMain" else DEFAULT_SOURCE_SET

  /**
   * Mirror of the top-level [dev.zacsweers.metro.gradle.source] helper that picks the appropriate
   * source set ([defaultSourceSet]) for this project. Shadows the top-level helper for fixture
   * subclasses so call sites don't have to repeat `sourceSet = ...`.
   */
  protected fun source(
    @Language("kotlin") source: String,
    fileNameWithoutExtension: String? = null,
    packageName: String = "test",
    includeDefaultImports: Boolean = true,
    vararg extraImports: String,
  ): Source =
    source(
      source = source,
      fileNameWithoutExtension = fileNameWithoutExtension,
      packageName = packageName,
      sourceSet = defaultSourceSet,
      includeDefaultImports = includeDefaultImports,
      extraImports = extraImports,
    )

  /**
   * Sources for the default single-module project. Not used when [buildGradleProject] is
   * overridden.
   */
  protected open fun sources(): List<Source> = emptyList()

  open fun StringBuilder.onBuildScript() {}

  /**
   * The gradle project for this test fixture. By default, creates a single-module project using
   * [sources]. Override [buildGradleProject] for custom project configurations.
   */
  val gradleProject: GradleProject by lazy { buildGradleProject() }

  /**
   * Override this to customize the project structure. For simple single-module projects, just
   * override [sources] instead. For multi-module projects, use [multiModuleProject].
   *
   * Example for multi-module:
   * ```
   * override fun buildGradleProject() = multiModuleProject {
   *   root {
   *     sources(appGraph, main)
   *     dependencies(Dependency.implementation(":lib"))
   *   }
   *   subproject("lib") {
   *     sources(libSource)
   *   }
   * }
   * ```
   */
  protected open fun buildGradleProject(): GradleProject =
    newGradleProjectBuilder(DslKind.KOTLIN)
      .withRootProject {
        sources = this@MetroProject.sources()
        withBuildScript { applyMetroDefault() }
        withMetroSettings()
      }
      .write()

  /**
   * Creates a multi-module project with the common Metro setup automatically applied. Each module
   * gets [applyMetroDefault] called on its build script, and the root project gets
   * [withMetroSettings] applied.
   *
   * Example:
   * ```
   * multiModuleProject {
   *   root {
   *     sources(appGraph)
   *     dependencies(Dependency.implementation(":lib"))
   *   }
   *   subproject("lib") {
   *     sources(dependency)
   *   }
   *   subproject("lib:impl") {
   *     sources(dependencyImpl)
   *     dependencies(Dependency.api(":lib"))
   *   }
   * }
   * ```
   */
  protected fun multiModuleProject(configure: MultiModuleProjectBuilder.() -> Unit): GradleProject {
    val builder = MultiModuleProjectBuilder()
    builder.configure()
    return builder.build()
  }

  protected inner class MultiModuleProjectBuilder {
    private val projects = mutableListOf<ProjectModuleConfig>()

    /** Configure the root project. */
    fun root(configure: ProjectModuleBuilder.() -> Unit) {
      val builder = ProjectModuleBuilder(":")
      builder.configure()
      projects.add(0, builder.build())
    }

    /** Add a subproject with the given path (e.g., "lib" or "lib:impl"). */
    fun subproject(path: String, configure: ProjectModuleBuilder.() -> Unit) {
      val builder = ProjectModuleBuilder(path)
      builder.configure()
      projects += builder.build()
    }

    fun build(): GradleProject {
      val rootConfig = projects.firstOrNull { it.path == ":" }
      val subprojectConfigs = projects.filter { it.path != ":" }
      return newGradleProjectBuilder(DslKind.KOTLIN)
        .withRootProject {
          rootConfig?.let { config ->
            sources = config.sources
            withBuildScript {
              applyMetroDefault()
              if (config.dependencies.isNotEmpty()) {
                dependencies(*config.dependencies.mappedForTarget().toTypedArray())
              }
              config.buildScriptExtra?.invoke(this)
            }
          }
            ?: run {
              // Default empty root project with just Metro settings
              withBuildScript { applyMetroDefault() }
            }
          withMetroSettings()
        }
        .apply {
          for (config in subprojectConfigs) {
            withSubproject(config.path) {
              sources.addAll(config.sources)
              withBuildScript {
                if (config.plugins.isNotEmpty()) {
                  plugins(*config.plugins.toTypedArray())
                } else {
                  applyMetroDefault()
                }
                if (config.dependencies.isNotEmpty()) {
                  dependencies(*config.dependencies.mappedForTarget().toTypedArray())
                }
                config.buildScriptExtra?.invoke(this)
              }
            }
          }
        }
        .write()
    }

    // KMP build scripts can't use the bare `implementation(...)` configuration; rewrite to the
    // source-set-scoped variant (e.g. `commonMainImplementation`). No-op for plain JVM projects.
    private fun List<Dependency>.mappedForTarget(): List<Dependency> {
      if (!multiplatform) return this
      return map { dep ->
        dep.copy(
          configuration = "commonMain" + dep.configuration.replaceFirstChar { it.titlecase() }
        )
      }
    }
  }

  protected class ProjectModuleBuilder(private val path: String) {
    private var sources: List<Source> = emptyList()
    private var dependencies: List<Dependency> = emptyList()
    private var plugins: List<Plugin> = emptyList()
    private var buildScriptExtra: (BuildScript.Builder.() -> Unit)? = null

    /** Set the sources for this project module. */
    @CanIgnoreReturnValue
    fun sources(vararg sources: Source) {
      this.sources += sources.toList()
    }

    /** Set the sources for this project module. */
    @CanIgnoreReturnValue
    fun sources(sources: List<Source>) {
      this.sources = sources
    }

    /** Add dependencies for this project module. */
    @CanIgnoreReturnValue
    fun dependencies(vararg deps: Dependency) {
      this.dependencies += deps.toList()
    }

    /**
     * Override the default plugins. By default, [applyMetroDefault] is used. If you call this, you
     * must specify all plugins including Kotlin and Metro.
     */
    @CanIgnoreReturnValue
    fun plugins(vararg plugins: Plugin) {
      this.plugins += plugins.toList()
    }

    /** Additional build script configuration beyond the Metro defaults. */
    @CanIgnoreReturnValue
    fun buildScript(configure: BuildScript.Builder.() -> Unit) {
      this.buildScriptExtra = configure
    }

    fun build() = ProjectModuleConfig(path, sources, dependencies, plugins, buildScriptExtra)
  }

  data class ProjectModuleConfig(
    val path: String,
    val sources: List<Source>,
    val dependencies: List<Dependency>,
    val plugins: List<Plugin>,
    val buildScriptExtra: (BuildScript.Builder.() -> Unit)?,
  )

  protected fun RootProject.Builder.withMetroSettings() {
    withSettingsScript {
      pluginManagement = PluginManagement(metroRepositories(Repository.DEFAULT_PLUGINS))
      dependencyResolutionManagement =
        DependencyResolutionManagement(metroRepositories(Repository.DEFAULT))
    }
  }

  private fun metroRepositories(defaults: List<Repository>): Repositories =
    Repositories(
      mutableListOf<Repository>().apply {
        addAll(defaults)
        add(Repository.ofMaven("https://packages.jetbrains.team/maven/p/kt/bootstrap"))
        add(Repository.ofMaven("https://packages.jetbrains.team/maven/p/kt/dev/"))
        add(Repository.ofMaven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/"))
      }
    )

  /** Generates just the `metro { ... }` block content for use in custom build scripts. */
  fun buildMetroBlock(): String = buildString {
    appendLine(
      "@OptIn(dev.zacsweers.metro.gradle.DelicateMetroGradleApi::class, dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi::class)"
    )
    appendLine("metro {")
    appendLine("  debug.set($debug)")
    if (reportsEnabled) {
      appendLine("  reportsDestination.set(layout.buildDirectory.dir(\"metro\"))")
    }
    val options = buildList {
      metroOptions.enableFullBindingGraphValidation?.let {
        add("compilerOptions.enable(\"enable-full-binding-graph-validation\")")
      }
      metroOptions.generateContributionProviders?.let {
        add("generateContributionProviders.set($it)")
      }
    }
    if (options.isNotEmpty()) {
      options.joinTo(this, separator = "\n", prefix = "  ")
    }
    appendLine("\n}")
  }

  /**
   * Default setup for simple projects. JVM-only by default, or KMP with the targets emitted by
   * [multiplatformTargetsBlock] when [multiplatform] is true. For more custom setups, override
   * [buildGradleProject].
   */
  fun BuildScript.Builder.applyMetroDefault() {
    if (multiplatform) {
      plugins(GradlePlugins.Kotlin.multiplatform(kotlinVersion), GradlePlugins.metro)
      withKotlin(
        buildString {
          onBuildScript()
          append(multiplatformTargetsBlock())
          append(buildMetroBlock())
        }
      )
    } else {
      plugins(GradlePlugins.Kotlin.jvm(kotlinVersion), GradlePlugins.metro)
      withKotlin(
        buildString {
          onBuildScript()
          append(buildMetroBlock())
        }
      )
    }
  }

  /**
   * Returns the `kotlin { ... }` targets block written into multiplatform projects. Override to
   * scope down the target set; the default emits every [KmpTarget] entry so a single project can be
   * exercised against the full parameter matrix.
   */
  protected open fun multiplatformTargetsBlock(): String = buildString {
    appendLine("kotlin {")
    appendLine("  jvm()")
    appendLine("  js { nodejs() }")
    appendLine("  wasmJs { nodejs() }")
    appendLine("  iosSimulatorArm64()")
    appendLine("}")
  }
}
