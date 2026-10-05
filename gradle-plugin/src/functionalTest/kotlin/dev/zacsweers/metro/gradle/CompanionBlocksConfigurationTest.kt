// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import com.autonomousapps.kit.GradleBuilder.build
import com.autonomousapps.kit.GradleBuilder.buildAndFail
import com.google.common.truth.Truth.assertThat
import java.net.URLClassLoader
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises companion-block flags through Kotlin's free compiler arguments. */
class CompanionBlocksConfigurationTest {
  @Test
  fun companionObjectMode() {
    compileMode("companion-object", "AppGraph.create(\"value\")", kotlinFlag = null)
  }

  @Test
  fun companionBlockMode() {
    compileMode("companion-block", "AppGraph.create(\"value\")")
  }

  @Test
  fun compatibilityMode() {
    compileMode(
      "compatibility",
      "AppGraph.create(AppGraph.Companion.create(\"value\").value)",
    )
  }

  @Test
  fun noneMode() {
    compileMode(
      "none",
      "createGraphFactory<AppGraph.Factory>().create(\"value\")",
      kotlinFlag = null,
    )
  }

  @Test
  fun combinedKotlinFlagEnablesStaticCreators() {
    compileMode(
      "companion-block",
      "AppGraph.create(\"value\")",
      "-Xcompanion-blocks-and-extensions",
    )
  }

  @Test
  fun companionModesRequireCompilerSupportAndEnabledBlocks() {
    for (mode in listOf("companion-block", "compatibility")) {
      val project =
        object : CompanionProject(mode, kotlinFlag = null) {
            override fun sources() = listOf(source("@DependencyGraph interface AppGraph"))
          }
          .gradleProject
      val result = buildAndFail(project.rootDir, "compileKotlin", "--console=plain")
      val expectedRequirement =
        if (getTestCompilerToolingVersion() >= KotlinToolingVersion("2.5.0-dev-6460")) {
          "-Xcompanion-blocks or -Xcompanion-blocks-and-extensions"
        } else {
          "Kotlin 2.5.0-dev-6460 or later"
        }
      assertThat(result.output).contains("companion-mode=")
      assertThat(result.output).contains(expectedRequirement)
    }
  }

  @Test
  fun javaConsumerCallsStaticGraphAndFactoryCreators() {
    assumeCompanionBlocksSupported()
    for (mode in listOf("companion-block", "compatibility")) {
      val project =
        object : CompanionProject(mode) {
            override fun sources() =
              listOf(
                source(GRAPH, "AppGraph"),
                source(
                  "@Inject class CreatedValue private constructor(val value: String)",
                  "CreatedValue",
                ),
              )
          }
          .gradleProject
      val javaSource = project.rootDir.resolve("src/main/java/test/JavaConsumer.java")
      javaSource.parentFile.mkdirs()
      javaSource.writeText(
        """
        package test;

        public final class JavaConsumer {
          public static String value() {
            return AppGraph.create("java").getValue()
                + ":" + CreatedValue.MetroFactory.newInstance("helper").getValue();
          }
        }
        """
          .trimIndent()
      )

      build(project.rootDir, "classes")
      val javaClasses = project.rootDir.resolve("build/classes/java/main").toURI().toURL()
      URLClassLoader(arrayOf(javaClasses), project.classLoader(target = null)).use { loader ->
        val result = loader.loadClass("test.JavaConsumer").getMethod("value").invoke(null)
        assertThat(result).isEqualTo("java:helper")
      }
    }
  }

  private fun compileMode(
    mode: String,
    creationExpression: String,
    kotlinFlag: String? = "-Xcompanion-blocks",
  ) {
    if (kotlinFlag != null) {
      assumeCompanionBlocksSupported()
    }
    val project =
      object : CompanionProject(mode, kotlinFlag) {
          override fun sources() =
            listOf(
              source(GRAPH, "AppGraph"),
              source("fun main(): String = $creationExpression.value", "Main"),
            )
        }
        .gradleProject
    build(project.rootDir, "compileKotlin")
    assertThat(project.invokeMain<String>(target = null)).isEqualTo("value")
  }

  private companion object {
    const val GRAPH =
      """
      @DependencyGraph
      interface AppGraph {
        val value: String

        @DependencyGraph.Factory
        fun interface Factory {
          fun create(@Provides value: String): AppGraph
        }
      }
    """
  }
}

/** Keeps fixture configuration limited to compiler arguments. */
internal abstract class CompanionProject(
  private val mode: String,
  private val kotlinFlag: String? = "-Xcompanion-blocks",
  multiplatform: Boolean = false,
  additionalGradleProperties: List<String> = emptyList(),
) :
  MetroProject(
    multiplatform = multiplatform,
    additionalGradleProperties = additionalGradleProperties,
  ) {
  override fun StringBuilder.onBuildScript() {
    appendLine("kotlin {")
    appendLine("  compilerOptions {")
    kotlinFlag?.let { appendLine("    freeCompilerArgs.add(\"$it\")") }
    appendLine(
      "    freeCompilerArgs.addAll(\"-P\", \"plugin:dev.zacsweers.metro.compiler:companion-mode=$mode\")"
    )
    appendLine("  }")
    appendLine("}")
  }
}

/** Feature fixtures run on the requested Beta1 floor and all later compiler releases. */
internal fun assumeCompanionBlocksSupported() {
  assumeTrue(getTestCompilerToolingVersion() >= KotlinToolingVersion("2.5.0-Beta1"))
}
