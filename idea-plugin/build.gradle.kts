// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import foundry.gradle.properties.PropertyResolver
import foundry.gradle.properties.StartParameterProperties
import foundry.gradle.properties.createPropertiesProvider
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

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

fun ideaPluginSigningProperty(name: String): Provider<String> =
  providers
    .environmentVariable(name)
    .filter(String::isNotBlank)
    .orElse(propertyResolver.optionalStringProvider(name).filter(String::isNotBlank))

val ideaPluginPrivateKey = ideaPluginSigningProperty("METRO_IDEA_PLUGIN_PRIVATE_KEY")
val ideaPluginPrivateKeyFile =
  layout
    .file(
      ideaPluginSigningProperty("METRO_IDEA_PLUGIN_PRIVATE_KEY_FILE").map { path -> file(path) },
    )
    .filter { !ideaPluginPrivateKey.isPresent }
val ideaPluginPrivateKeyPassword =
  ideaPluginSigningProperty("METRO_IDEA_PLUGIN_PRIVATE_KEY_PASSWORD")
val ideaPluginCertificateChain = ideaPluginSigningProperty("METRO_IDEA_PLUGIN_CERTIFICATE_CHAIN")
val ideaPluginCertificateChainFile =
  layout
    .file(
      ideaPluginSigningProperty("METRO_IDEA_PLUGIN_CERTIFICATE_CHAIN_FILE").map { path ->
        file(path)
      },
    )
    .filter { !ideaPluginCertificateChain.isPresent }

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
    listOf("eap")
  } else {
    listOf("default", "eap")
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
      "dev.zacsweers.metro.graph.ExperimentalMetroGraphApi",
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

  fun Provider<*>.mapToQuotedString() = map { "\"$it\"" }

  buildConfigField("String", "PLUGIN_ID", libs.versions.pluginId.mapToQuotedString())
  buildConfigField("String", "VERSION", pluginVersion.mapToQuotedString())
  buildConfigField("String", "GIT_SHA", gitSha.mapToQuotedString())
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

tasks.named<KotlinJvmCompile>("compileLibFixtureKotlin") {
  compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
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

// The IDE supplies Kotlin's standard library to plugins.
val kotlinStdlibExclude = mapOf("group" to "org.jetbrains.kotlin", "module" to "kotlin-stdlib")

val shadedClasspath =
  configurations.resolvable("shadedClasspath") {
    extendsFrom(shaded)
    exclude(coroutinesExclude)
    exclude(kotlinStdlibExclude)
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
    intellijIdea("2026.2")
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

tasks.processResources {
  from(layout.projectDirectory.dir("../design")) {
    include("pluginIcon.svg", "pluginIcon_dark.svg")
    into("META-INF")
  }
}

tasks.jar {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  from(shadedClasspath.flatMap { it.elements }.map { files -> files.map { zipTree(it.asFile) } })
  exclude(
    "META-INF/*.DSA",
    "META-INF/*.RSA",
    "META-INF/*.SF",
    // Wire's desktop runtime does not use its Android Parcelable adapter.
    "com/squareup/wire/AndroidMessage.class",
    "com/squareup/wire/AndroidMessage\$*.class",
    // These generated annotation implementations fail verifier checks and have no incoming
    // references.
    "kotlinx/serialization/json/JsonClassDiscriminator\$Impl.class",
    "kotlinx/serialization/json/JsonIgnoreUnknownKeys\$Impl.class",
    "kotlinx/serialization/json/JsonNames\$Impl.class",
  )
}

intellijPlatform {
  pluginConfiguration {
    id.set("dev.zacsweers.metro.idea")
    name.set("Metro")
    version.set(pluginVersion)
    description.set(
      providers.fileContents(layout.projectDirectory.file("marketplace-description.html")).asText,
    )

    ideaVersion {
      sinceBuild.set("262")
    }
  }

  signing {
    privateKey.set(ideaPluginPrivateKey)
    privateKeyFile.set(ideaPluginPrivateKeyFile)
    password.set(ideaPluginPrivateKeyPassword)
    certificateChain.set(ideaPluginCertificateChain)
    certificateChainFile.set(ideaPluginCertificateChainFile)
  }

  publishing {
    token.set(propertyResolver.optionalStringProvider("intellijPlatformPublishingToken"))

    channels.set(publishingChannels)

    // Boolean for whether to mark this release as hidden
    hidden.set(
      propertyResolver
        .optionalStringProvider("intellijPlatformPublishingHidden")
        .map(String::toBoolean)
        .orElse(false),
    )
  }

  pluginVerification {
    failureLevel.set(
      listOf(
        VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
        VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
        VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
        VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
      ),
    )

    ides {
      create(IntelliJPlatformType.IntellijIdea, "2026.2")
      create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
      create(IntelliJPlatformType.IntellijIdea, "263.6800.24")
      create(IntelliJPlatformType.AndroidStudio, "2026.2.1.8")
    }
  }
}

val validateIdeaPluginSigning =
  tasks.register("validateIdeaPluginSigning") {
    group = "intellij platform"
    description = "Validates the credentials used to sign the IDEA plugin."

    doLast {
      check(ideaPluginPrivateKey.isPresent || ideaPluginPrivateKeyFile.isPresent) {
        "Missing METRO_IDEA_PLUGIN_PRIVATE_KEY or METRO_IDEA_PLUGIN_PRIVATE_KEY_FILE"
      }
      ideaPluginPrivateKeyFile.orNull?.asFile?.let { privateKeyFile ->
        check(privateKeyFile.isFile) {
          "METRO_IDEA_PLUGIN_PRIVATE_KEY_FILE does not point to a regular file: $privateKeyFile"
        }
      }

      check(ideaPluginPrivateKeyPassword.isPresent) {
        "Missing METRO_IDEA_PLUGIN_PRIVATE_KEY_PASSWORD"
      }

      check(ideaPluginCertificateChain.isPresent || ideaPluginCertificateChainFile.isPresent) {
        "Missing METRO_IDEA_PLUGIN_CERTIFICATE_CHAIN or METRO_IDEA_PLUGIN_CERTIFICATE_CHAIN_FILE"
      }
      ideaPluginCertificateChainFile.orNull?.asFile?.let { certificateChainFile ->
        check(certificateChainFile.isFile) {
          "METRO_IDEA_PLUGIN_CERTIFICATE_CHAIN_FILE does not point to a regular file: " +
            certificateChainFile
        }
      }
    }
  }

tasks.named("buildPlugin") { mustRunAfter(validateIdeaPluginSigning) }

tasks.named("signPlugin") { dependsOn(validateIdeaPluginSigning) }

tasks.named("verifyPluginSignature") { dependsOn(tasks.named("signPlugin")) }

tasks.named("publishPlugin") { dependsOn(validateIdeaPluginSigning) }

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
    },
  )
}
