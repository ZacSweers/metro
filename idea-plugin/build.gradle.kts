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

val explicitReleaseBuild =
  providers
    .gradleProperty("metroIdeaReleaseBuild")
    .map { it.toBooleanStrictOrNull() == true }
    .orElse(false)

// Gradle accepts camel-hump task abbreviations like `pubPl`, so match them the same way.
fun matchesTaskAbbreviation(requested: String, taskName: String): Boolean {
  if (requested.isEmpty()) {
    return false
  }
  val pattern = buildString {
    for (ch in requested) {
      if (ch.isUpperCase()) {
        append("[a-z0-9]*")
      }
      append(Regex.escape(ch.toString()))
    }
    append("[a-zA-Z0-9]*")
  }
  return Regex(pattern).matches(taskName)
}

val publishingTaskRequested = providers.provider {
  gradle.startParameter.taskNames
    .map { it.substringAfterLast(':') }
    .any { requested ->
      matchesTaskAbbreviation(requested, "publishPlugin") ||
        matchesTaskAbbreviation(requested, "signPlugin")
    }
}

val isReleaseOrPublishingBuild =
  explicitReleaseBuild.zip(publishingTaskRequested) { explicitRelease, publishRequested ->
    explicitRelease || publishRequested
  }

val releaseGitSha =
  providers.of(GitCommitValueSource::class.java) {
    parameters.projectDirectory.set(layout.projectDirectory.dir(".."))
  }

val gitSha = isReleaseOrPublishingBuild.flatMap { isReleaseBuild ->
  if (isReleaseBuild) {
    // A release build without a resolvable sha should fail loudly, not publish untagged.
    providers.provider {
      val sha = releaseGitSha.orNull
      checkNotNull(sha) {
        "Could not read the git commit sha for a release/publishing build"
      }
    }
  } else {
    providers.provider { "" }
  }
}

group = propertyResolver.requiredStringProvider("GROUP").get()

val versionProvider = propertyResolver.requiredStringProvider("VERSION_NAME")

version = versionProvider.get()

val isSnapshotVersion = versionProvider.map { it.contains("SNAPSHOT") }

// Computed once so every query of the version provider observes the same timestamp.
val snapshotTimestamp by lazy { System.currentTimeMillis() }

val pluginVersion =
  isReleaseOrPublishingBuild.zip(versionProvider) { releaseOrPublishing, versionName ->
    if (releaseOrPublishing && versionName.contains("SNAPSHOT")) {
      "$versionName-$snapshotTimestamp"
    } else {
      versionName
    }
  }

val defaultPublishingChannels = isSnapshotVersion.map { snapshotVersion ->
  if (snapshotVersion) {
    listOf("EAP")
  } else {
    listOf("Stable")
  }
}

val configuredPublishingChannels =
  propertyResolver.optionalStringProvider("intellijPlatformPublishingChannels").map { channels ->
    channels.split(',').map(String::trim).filter(String::isNotEmpty)
  }

val publishingChannels =
  configuredPublishingChannels
    .flatMap { channels ->
      if (channels.isEmpty()) {
        defaultPublishingChannels
      } else {
        providers.provider { channels }
      }
    }
    .orElse(defaultPublishingChannels)

metroProject { jvmTarget.set(libs.versions.ideaJvmTarget) }

kotlin {
  compilerOptions {
    optIn.addAll(
      // Analysis API type rendering used by MetroResolutionService
      "org.jetbrains.kotlin.analysis.api.KaExperimentalApi",
      // Platform extension points can run on the EDT, where analysis must be explicitly allowed.
      "org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt",
    )
  }
}

java { toolchain { languageVersion.set(libs.versions.ideaJvmTarget.map(JavaLanguageVersion::of)) } }

repositories {
  mavenCentral()
  google()
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
  buildConfigField("String", "VERSION", providers.provider { "\"$version\"" })
  buildConfigField(
    "String",
    "BUGSNAG_KEY",
    propertyResolver
      .optionalStringProvider("MetroIntellijBugsnagKey")
      .map { "\"$it\"" }
      .orElse("\"\""),
  )
  buildConfigField("String", "GIT_SHA", gitSha.map { "\"$it\"" })
}

val metroRuntime = configurations.dependencyScope("metroRuntime")

val metroRuntimeClasspath =
  configurations.resolvable("metroRuntimeClasspath") {
    extendsFrom(metroRuntime)
    isTransitive = false
    resolutionStrategy.useGlobalDependencySubstitutionRules = false
  }

val kotlinStdlib = configurations.dependencyScope("kotlinStdlib")

val kotlinStdlibClasspath =
  configurations.resolvable("kotlinStdlibClasspath") {
    extendsFrom(kotlinStdlib)
    isTransitive = false
  }

val compilerTestData = layout.projectDirectory.dir("../compiler-tests/src/test/data")
val compilerParityTestData = compilerTestData.dir("diagnostic/ideaParity")

// A compiled "library" with Metro-annotated classes + handwritten contribution hint functions,
// used by tests covering resolution from binary dependencies.
val libFixture =
  sourceSets.register("libFixture") {
    kotlin.srcDir("src/test/data/libFixtures/kotlin")
  }

val libFixtureJar =
  tasks.register<Jar>("libFixtureJar") {
    archiveClassifier.set("lib-fixture")
    from(libFixture.map { it.output })
  }

val shaded = configurations.dependencyScope("shaded")

// androidx.tracing pulls a plain kotlinx-coroutines that must not shadow the IDE's patched
// coroutines in the plugin jar or test runtime.
val coroutinesExclude =
  mapOf("group" to "org.jetbrains.kotlinx", "module" to "kotlinx-coroutines-core")

val shadedClasspath =
  configurations.resolvable("shadedClasspath") {
    extendsFrom(shaded)
    exclude(coroutinesExclude)
  }

configurations.named("testImplementation") { exclude(coroutinesExclude) }

// Runs a sandboxed IDE with the plugin installed from source: ./gradlew runLocalIde
// To use a locally installed IDE (e.g., Android Studio) instead of the default target:
// ./gradlew runLocalIde "-PintellijPlatformTesting.idePath=/Applications/Android Studio.app"
intellijPlatformTesting.runIde.register("runLocalIde") {
  providers.gradleProperty("intellijPlatformTesting.idePath").orNull?.let {
    localPath.set(file(it))
  }
}

dependencies {
  intellijPlatform {
    intellijIdeaUltimate("2026.1.3")
    bundledPlugin("org.jetbrains.kotlin")
    testFramework(TestFrameworkType.Platform)
    pluginVerifier()
    zipSigner()
  }

  add(metroRuntime.name, "dev.zacsweers.metro:runtime:$metroBootstrapVersion")
  add(kotlinStdlib.name, libs.kotlin.stdlib)
  add(
    libFixture.get().compileOnlyConfigurationName,
    "dev.zacsweers.metro:runtime:$metroBootstrapVersion",
  )
  implementation(libs.bugsnag) { exclude(group = "org.slf4j") }
  compileOnly("dev.zacsweers.metro:metro-common")
  compileOnly(libs.androidx.collection)
  compileOnly(libs.androidx.tracing)
  compileOnly(libs.androidx.tracing.wire)
  add(shaded.name, "dev.zacsweers.metro:metro-common")
  add(shaded.name, libs.androidx.collection)
  add(shaded.name, libs.androidx.tracing)
  add(shaded.name, libs.androidx.tracing.wire)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test)
  testImplementation("dev.zacsweers.metro:metro-common")
  testImplementation(libs.androidx.collection)
  testImplementation(libs.androidx.tracing)
  testImplementation(libs.androidx.tracing.wire)
}

tasks.jar {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  from(shadedClasspath.flatMap { it.elements }.map { files -> files.map { zipTree(it.asFile) } })
  exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF")
}

intellijPlatform {
  pluginConfiguration {
    id.set("dev.zacsweers.metro.idea")
    name.set("Metro")
    version.set(pluginVersion)
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

    channels.set(publishingChannels)

    // Boolean for whether to mark this release as hidden
    hidden.set(
      propertyResolver
        .optionalStringProvider("intellijPlatformPublishingHidden")
        .map(String::toBoolean)
    )
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
  dependsOn(kotlinStdlibClasspath)
  dependsOn(libFixtureJar)
  inputs.dir(compilerParityTestData).withPathSensitivity(PathSensitivity.RELATIVE)
  // The argument provider below has no annotated properties, so these must be declared as
  // inputs explicitly or fixture/classpath changes leave test runs UP-TO-DATE with stale jars.
  inputs
    .files(libFixtureJar)
    .withPropertyName("metroLibFixtureJar")
    .withPathSensitivity(PathSensitivity.NONE)
  inputs
    .files(metroRuntimeClasspath)
    .withPropertyName("metroRuntimeTestClasspath")
    .withPathSensitivity(PathSensitivity.NONE)
  inputs
    .files(kotlinStdlibClasspath)
    .withPropertyName("kotlinStdlibTestClasspath")
    .withPathSensitivity(PathSensitivity.NONE)
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-DmetroRuntime.classpath=${metroRuntimeClasspath.get().asPath}",
        "-DkotlinStdlib.classpath=${kotlinStdlibClasspath.get().asPath}",
        "-DmetroLibFixture.classpath=${libFixtureJar.get().archiveFile.get().asFile.absolutePath}",
        "-DmetroCompilerTestData.path=${compilerTestData.asFile.absolutePath}",
      )
    }
  )
}
