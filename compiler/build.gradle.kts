// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// Bootstrap: add the Metro compiler plugin JAR to the buildscript classpath from Maven Central.
// Buildscript resolution is NOT subject to project-level composite build dependency substitution,
// which avoids the circular task dependency (compileKotlin → shadowJar → compileKotlin) that
// occurs when Gradle substitutes dev.zacsweers.metro:compiler with project(:compiler).
buildscript {
  repositories { mavenCentral() }
  val bootstrapVersion =
    extra.properties["METRO_BOOTSTRAP_VERSION"]?.toString()
      ?: error("METRO_BOOTSTRAP_VERSION not set in gradle.properties")
  dependencies {
    classpath("dev.zacsweers.metro:compiler:$bootstrapVersion") { isTransitive = false }
  }
}

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.poko)
  alias(libs.plugins.buildConfig)
  alias(libs.plugins.wire)
  alias(libs.plugins.shadow) apply false
  id("metro.publish")
  // apply false to put metro on the classpath. Conditionally applied below.
  alias(libs.plugins.metro)
}

metro {
  generateAssistedFactories.set(true)
  // We embed and shade the runtime in the compiler's shadow JAR
  automaticallyAddRuntimeDependencies.set(false)
}

// Extract the bootstrap compiler JAR from the buildscript classpath
val bootstrapVersion = extra.properties["METRO_BOOTSTRAP_VERSION"]?.toString()!!
val bootstrapJar =
  buildscript.configurations.getByName("classpath").files.single {
    it.name == "compiler-$bootstrapVersion.jar"
  }

configurations
  .matching { it.name.startsWith("kotlinCompilerPluginClasspath") }
  .configureEach {
    exclude(group = "dev.zacsweers.metro", module = "compiler")
    dependencies.add(project.dependencies.create(files(bootstrapJar)))
  }

buildConfig {
  generateAtSync = true
  packageName("dev.zacsweers.metro.compiler")
  kotlin {
    useKotlinOutput {
      internalVisibility = true
      topLevelConstants = true
    }
  }
  sourceSets.named("main") {
    buildConfigField(
      "String",
      "METRO_VERSION",
      providers.gradleProperty("VERSION_NAME").map { "\"$it\"" },
    )
    buildConfigField("String", "PLUGIN_ID", libs.versions.pluginId.map { "\"$it\"" })
    // Metadata version for compatibility checking. Increment when making breaking changes to
    // metro_metadata.proto
    buildConfigField("Int", "METADATA_VERSION", 1)
  }
  sourceSets.named("test") {
    buildConfigField("String", "JVM_TARGET", libs.versions.jvmTarget.map { "\"$it\"" })
  }
}

tasks.test {
  maxParallelForks = Runtime.getRuntime().availableProcessors() * 2
  systemProperty("metro.buildDir", project.layout.buildDirectory.asFile.get().absolutePath)
  systemProperty("metro.richDiagnostics", "false")
}

wire { kotlin { javaInterop = false } }

/**
 * Kotlin native requires the compiler plugin to embed its dependencies. (See
 * https://youtrack.jetbrains.com/issue/KT-53477)
 *
 * In order to do this, we replace the default jar task with a shadowJar task that embeds the
 * dependencies from the "embedded" configuration.
 */
val embedded by configurations.dependencyScope("embedded")

val embeddedClasspath by configurations.resolvable("embeddedClasspath") { extendsFrom(embedded) }

configurations.named("compileOnly").configure { extendsFrom(embedded) }

configurations.named("testImplementation").configure { extendsFrom(embedded) }

tasks.jar.configure { enabled = false }

val shadowJar =
  tasks.register("shadowJar", ShadowJar::class.java) {
    from(java.sourceSets.main.map { it.output })
    configurations.add(embeddedClasspath)

    // TODO these are relocated, do we need to/can we exclude these?
    //  exclude("META-INF/wire-runtime.kotlin_module")
    //  exclude("META-INF/okio.kotlin_module")
    dependencies {
      exclude(dependency("org.jetbrains:.*"))
      exclude(dependency("org.intellij:.*"))
      exclude(dependency("org.jetbrains.kotlin:.*"))
      exclude(dependency("dev.drewhamilton.poko:.*"))
    }

    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()

    relocate("androidx.collection", "dev.zacsweers.metro.compiler.shaded.androidx.collection")
    relocate("androidx.tracing", "dev.zacsweers.metro.compiler.shaded.androidx.tracing")
    relocate("com.squareup.wire", "dev.zacsweers.metro.compiler.shaded.com.squareup.wire")
    relocate("com.squareup.okio", "dev.zacsweers.metro.compiler.shaded.com.squareup.okio")
    relocate("com.jakewharton.picnic", "dev.zacsweers.metro.compiler.shaded.com.jakewharton.picnic")
    relocate(
      "com.jakewharton.crossword",
      "dev.zacsweers.metro.compiler.shaded.com.jakewharton.crossword",
    )
    relocate("okio", "dev.zacsweers.metro.compiler.shaded.okio")
    // Relocate the metro runtime while excluding the compiler's own package
    relocate("dev.zacsweers.metro", "dev.zacsweers.metro.compiler.shaded.metro") {
      exclude("dev.zacsweers.metro.compiler.**")
    }
  }

/**
 * The wire and poko plugin add their dependencies automatically. This is not needed because we can
 * either ignore or embed them so we remove them.
 *
 * Note: this is done in `afterEvaluate` to run after wire:
 * https://github.com/square/wire/blob/34931324f09c5827a624c056e1040dc8d01cbcd9/wire-gradle-plugin/src/main/kotlin/com/squareup/wire/gradle/WirePlugin.kt#L75
 *
 * Same for poko:
 * https://github.com/drewhamilton/Poko/blob/7bde5b23cc65a95a894e0ba0fb305704c49382f0/poko-gradle-plugin/src/main/kotlin/dev/drewhamilton/poko/gradle/PokoGradlePlugin.kt#L19
 */
project.afterEvaluate {
  configurations.named("api") {
    dependencies.removeIf { it is ExternalDependency && it.group == "com.squareup.wire" }
  }
  configurations.named("implementation") {
    dependencies.removeIf { it is ExternalDependency && it.group == "dev.drewhamilton.poko" }
  }
}

for (c in arrayOf("apiElements", "runtimeElements")) {
  configurations.named(c) { artifacts.removeIf { true } }
  artifacts.add(c, shadowJar)
}

dependencies {
  compileOnly(libs.kotlin.compiler)
  compileOnly(libs.kotlin.stdlib)
  compileOnly(libs.poko.annotations)
  compileOnly(libs.androidx.collection)

  add(embedded.name, project(":runtime"))
  add(embedded.name, libs.androidx.collection)
  add(embedded.name, libs.androidx.tracing.wire)
  add(embedded.name, libs.picnic)
  add(embedded.name, libs.wire.runtime)
  add(embedded.name, libs.kotlinx.serialization.json)
  add(embedded.name, project(":compiler-compat"))
  rootProject.isolated.projectDirectory.dir("compiler-compat").asFile.listFiles()!!.forEach {
    if (it.isDirectory && it.name.startsWith("k") && File(it, "version.txt").exists()) {
      add(embedded.name, project(":compiler-compat:${it.name}"))
    }
  }

  testCompileOnly(libs.poko.annotations)

  testImplementation(project(":interop-dagger"))
  testImplementation(libs.kotlin.reflect)
  testImplementation(libs.kotlin.stdlib)
  val testCompilerVersion =
    providers.gradleProperty("metro.testCompilerVersion").orElse(libs.versions.kotlin).get()
  testRuntimeOnly("org.jetbrains.kotlin:kotlin-compiler:$testCompilerVersion")
  // Cover for https://github.com/tschuchortdev/kotlin-compile-testing/issues/274
  testImplementation(libs.kotlin.aptEmbeddable)
  if (testCompilerVersion.startsWith("2.4")) {
    testImplementation("dev.zacsweers.kctfork:core:0.13.0-alpha01")
    testImplementation("dev.zacsweers.kctfork:ksp:0.13.0-alpha01")
  } else {
    testImplementation(libs.kct)
    testImplementation(libs.kct.ksp)
  }
  testImplementation(libs.okio)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.truth)
  testImplementation(libs.coroutines)
  testImplementation(libs.coroutines.test)
  testImplementation(libs.dagger.compiler)
  testImplementation(libs.dagger.runtime)
  testImplementation(libs.anvil.annotations)
  testImplementation(libs.androidx.collection)
}
