// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlin.jvm)
  java
}

val metroRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }
val anvilRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }
// include transitive in this case to grab jakarta and javax
val daggerRuntimeClasspath: Configuration by configurations.creating {}

dependencies {
  testImplementation(project(":compiler"))

  testImplementation(libs.kotlin.testJunit5)
  testImplementation(libs.kotlin.compilerTestFramework)
  testImplementation(libs.kotlin.compiler)

  metroRuntimeClasspath(project(":runtime"))
  anvilRuntimeClasspath(libs.anvil.annotations)
  anvilRuntimeClasspath(libs.anvil.annotations.optional)
  daggerRuntimeClasspath(libs.dagger.runtime)

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
  inputs
    .dir(layout.projectDirectory.dir("src/test/data"))
    .withPropertyName("testData")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  workingDir = rootDir

  useJUnitPlatform()

  systemProperty("metroRuntime.classpath", metroRuntimeClasspath.asPath)
  systemProperty("anvilRuntime.classpath", anvilRuntimeClasspath.asPath)
  systemProperty("daggerRuntime.classpath", daggerRuntimeClasspath.asPath)

  // Properties required to run the internal test framework.
  systemProperty("idea.ignore.disabled.plugins", "true")
  systemProperty("idea.home.path", rootDir)
}
