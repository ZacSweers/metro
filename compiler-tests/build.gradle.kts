// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.buildConfig)
  java
}

buildConfig {
  generateAtSync = true
  packageName("dev.zacsweers.metro.compiler.test")
  kotlin {
    useKotlinOutput {
      internalVisibility = true
      topLevelConstants = true
    }
  }
  sourceSets.named("test") {
    buildConfigField("String", "JVM_TARGET", libs.versions.jvmTarget.map { "\"$it\"" })
  }
}

val metroRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }
val anvilRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }
val kiAnvilRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }
// include transitive in this case to grab jakarta and javax
val daggerRuntimeClasspath: Configuration by configurations.creating {}
val daggerInteropClasspath: Configuration by configurations.creating { isTransitive = false }

dependencies {
  testImplementation(project(":compiler"))

  testImplementation(libs.kotlin.testJunit5)
  testImplementation(libs.kotlin.compilerTestFramework)
  testImplementation(libs.kotlin.compiler)

  testRuntimeOnly(libs.ksp.symbolProcessing)
  testImplementation(libs.ksp.symbolProcessing.aaEmbeddable)
  testImplementation(libs.ksp.symbolProcessing.commonDeps)
  testImplementation(libs.ksp.symbolProcessing.api)
  testImplementation(libs.classgraph)
  testImplementation(libs.dagger.compiler)

  metroRuntimeClasspath(project(":runtime"))
  daggerInteropClasspath(project(":interop-dagger"))
  anvilRuntimeClasspath(libs.anvil.annotations)
  anvilRuntimeClasspath(libs.anvil.annotations.optional)
  daggerRuntimeClasspath(libs.dagger.runtime)
  kiAnvilRuntimeClasspath(libs.kotlinInject.anvil.runtime)

  // Dependencies required to run the internal test framework.
  testRuntimeOnly(libs.kotlin.reflect)
  testRuntimeOnly(libs.kotlin.test)
  testRuntimeOnly(libs.kotlin.scriptRuntime)
  testRuntimeOnly(libs.kotlin.annotationsJvm)
}

tasks.register<JavaExec>("generateTests") {
  inputs
    .dir(layout.projectDirectory.dir("src/test/data"))
    .withPropertyName("testData")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.dir(layout.projectDirectory.dir("src/test/java")).withPropertyName("generatedTests")

  classpath = sourceSets.test.get().runtimeClasspath
  mainClass.set("dev.zacsweers.metro.compiler.GenerateTestsKt")
  workingDir = rootDir
}

tasks.withType<Test> {
  dependsOn(metroRuntimeClasspath)
  dependsOn(daggerInteropClasspath)
  inputs
    .dir(layout.projectDirectory.dir("src/test/data"))
    .withPropertyName("testData")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  workingDir = rootDir

  systemProperty("metro.messaging.useShortCompilerSourceLocations", "true")

  useJUnitPlatform()

  setLibraryProperty("kotlin.minimal.stdlib.path", "kotlin-stdlib")
  setLibraryProperty("kotlin.full.stdlib.path", "kotlin-stdlib-jdk8")
  setLibraryProperty("kotlin.reflect.jar.path", "kotlin-reflect")
  setLibraryProperty("kotlin.test.jar.path", "kotlin-test")
  setLibraryProperty("kotlin.script.runtime.path", "kotlin-script-runtime")
  setLibraryProperty("kotlin.annotations.path", "kotlin-annotations-jvm")

  systemProperty("metroRuntime.classpath", metroRuntimeClasspath.asPath)
  systemProperty("anvilRuntime.classpath", anvilRuntimeClasspath.asPath)
  systemProperty("kiAnvilRuntime.classpath", kiAnvilRuntimeClasspath.asPath)
  systemProperty("daggerRuntime.classpath", daggerRuntimeClasspath.asPath)
  systemProperty("daggerInterop.classpath", daggerInteropClasspath.asPath)

  // Properties required to run the internal test framework.
  systemProperty("idea.ignore.disabled.plugins", "true")
  systemProperty("idea.home.path", rootDir)
}

fun Test.setLibraryProperty(propName: String, jarName: String) {
  val path =
    project.configurations.testRuntimeClasspath
      .get()
      .files
      .find { """$jarName-\d.*jar""".toRegex().matches(it.name) }
      ?.absolutePath ?: return
  systemProperty(propName, path)
}
