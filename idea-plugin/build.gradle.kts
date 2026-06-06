// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import foundry.gradle.properties.PropertyResolver
import foundry.gradle.properties.StartParameterProperties
import foundry.gradle.properties.createPropertiesProvider
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.buildConfig)
  alias(libs.plugins.intellijPlatform)
  id("metro.base")
}

val startParameterProperties =
  providers.of(StartParameterProperties::class.java) {
    parameters.properties.set(gradle.startParameter.projectProperties)
  }

val metroRootLocalProperties = createPropertiesProvider("../local.properties")

val metroRootGradleProperties = createPropertiesProvider("../gradle.properties")

val metroRootLocalProperty: (String) -> Provider<String> = { key ->
  metroRootLocalProperties.map { it.getProperty(key) }
}

val metroRootGradleProperty: (String) -> Provider<String> = { key ->
  metroRootGradleProperties.map { it.getProperty(key) }.orElse(providers.gradleProperty(key))
}

val propertyResolver =
  PropertyResolver(
    project,
    startParameterProperty = { key ->
      startParameterProperties.map { it[key] }
    },
    globalLocalProperty = metroRootLocalProperty,
    globalGradleLocalProperty = metroRootGradleProperty,
  )

val metroBootstrapVersion = propertyResolver.requiredStringProvider("METRO_BOOTSTRAP_VERSION").get()

group = propertyResolver.requiredStringProvider("GROUP").get()

version = propertyResolver.requiredStringProvider("VERSION_NAME").get()

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

val metroRuntimeClasspath: Configuration by configurations.creating {
  isTransitive = false
  resolutionStrategy.useGlobalDependencySubstitutionRules = false
}

dependencies {
  intellijPlatform {
    intellijIdeaUltimate("2026.1.3")
    bundledPlugin("org.jetbrains.kotlin")
    testFramework(TestFrameworkType.Platform)
  }

  metroRuntimeClasspath("dev.zacsweers.metro:runtime:$metroBootstrapVersion")
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test)
}

intellijPlatform {
  pluginConfiguration {
    id.set("dev.zacsweers.metro.idea")
    name.set("Metro")
    version.set(providers.provider { project.version.toString() })
    description.set("Additional IDE support and features for projects using Metro.")

    ideaVersion {
      sinceBuild.set("261")
    }
  }

  signing {
    keyStore.set(
      layout.file(propertyResolver.optionalStringProvider("signing.secretKeyRingFile").map(::file))
    )
    keyStorePassword.set(propertyResolver.optionalStringProvider("signing.password"))
    keyStoreKeyAlias.set(propertyResolver.optionalStringProvider("signing.keyId"))
  }

  publishing {
    token.set(propertyResolver.optionalStringProvider("intellijPlatformPublishingToken"))
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
  dependsOn(metroRuntimeClasspath)
  systemProperty("metroRuntime.classpath", metroRuntimeClasspath.asPath)
}
