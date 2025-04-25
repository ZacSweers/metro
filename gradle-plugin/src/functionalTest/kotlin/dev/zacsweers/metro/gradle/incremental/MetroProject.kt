package dev.zacsweers.metro.gradle.incremental

import com.autonomousapps.kit.AbstractGradleProject
import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.GradleProject.DslKind
import com.autonomousapps.kit.Source

abstract class MetroProject(private val debug: Boolean = false) : AbstractGradleProject() {
  protected abstract fun sources(): List<Source>

  val gradleProject: GradleProject
    get() =
      newGradleProjectBuilder(DslKind.KOTLIN)
        .withRootProject {
          sources = this@MetroProject.sources()
          withBuildScript {
            plugins(GradlePlugins.Kotlin.jvm, GradlePlugins.metro)
            if (debug) {
              withKotlin(
                """
                metro { debug.set(true) }
                """
                  .trimIndent()
              )
            }
          }
        }
        .write()
}
