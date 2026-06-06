// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import java.util.Properties
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.buildConfig)
  alias(libs.plugins.intellijPlatform)
  id("metro.base")
}

val metroRootProperties =
  Properties().apply {
    layout.projectDirectory.file("../gradle.properties").asFile.inputStream().use(::load)
  }

group = metroRootProperties.getProperty("GROUP")

version = metroRootProperties.getProperty("VERSION_NAME")

metroProject { jvmTarget.set(libs.versions.ideaJvmTarget) }

java { toolchain { languageVersion.set(libs.versions.ideaJvmTarget.map(JavaLanguageVersion::of)) } }

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

buildConfig {
  generateAtSync = true
  packageName("dev.zacsweers.metro.idea")
  kotlin {
    useKotlinOutput {
      internalVisibility = true
      topLevelConstants = true
    }
  }
  buildConfigField("String", "PLUGIN_ID", libs.versions.pluginId.map { "\"$it\"" })
}

dependencies {
  intellijPlatform {
    intellijIdeaUltimate("2026.1.3")
    bundledPlugin("org.jetbrains.kotlin")
    testFramework(TestFrameworkType.Platform)
  }

  testImplementation("dev.zacsweers.metro:runtime:$version")
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test)
}

intellijPlatform {
  pluginConfiguration {
    name = "Metro"
    version = project.version.toString()

    ideaVersion {
      sinceBuild = "261"
    }
  }

  pluginVerification {
    ides {
      create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1.3")
      // Quail 1 is marketed as 2026.1.1, but the Android Studio release feed keys it as 2026.1.1.8.
      create(IntelliJPlatformType.AndroidStudio, "2026.1.1.8")
    }
  }
}

tasks.withType<VerifyPluginTask>().configureEach {
  setJvmArgs(jvmArgs.filterNot { it == "--sun-misc-unsafe-memory-access=allow" })
}

tasks.test {
  dependsOn(gradle.includedBuild("metro").task(":runtime:jvmJar"))
  systemProperty(
    "metro.runtime.jar",
    layout.projectDirectory
      .file("../runtime/build/libs/runtime-jvm-$version.jar")
      .asFile
      .absolutePath,
  )
}
