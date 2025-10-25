// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.gradle.kotlin.dsl.sourceSets

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.buildConfig)
  java
}

sourceSets {
  register("generator220")
  register("generator230")
}

val testCompilerVersionProvider = providers.gradleProperty("metro.testCompilerVersion")

val testCompilerVersion = testCompilerVersionProvider.orElse(libs.versions.kotlin).get()

val kotlinVersion =
  testCompilerVersion.substringBefore('-').split('.').let { (major, minor, patch) ->
    KotlinVersion(major.toInt(), minor.toInt(), patch.toInt())
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
    // Not a Boolean to avoid warnings about constants in if conditions
    buildConfigField(
      "String",
      "OVERRIDE_COMPILER_VERSION",
      "\"${testCompilerVersionProvider.isPresent}\"",
    )
    buildConfigField("String", "JVM_TARGET", libs.versions.jvmTarget.map { "\"$it\"" })
    buildConfigField(
      "kotlin.KotlinVersion",
      "COMPILER_VERSION",
      "KotlinVersion(${kotlinVersion.major}, ${kotlinVersion.minor}, ${kotlinVersion.patch})",
    )
  }
}

val metroRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }
val anvilRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }
val kiAnvilRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }
// include transitive in this case to grab jakarta and javax
val daggerRuntimeClasspath: Configuration by configurations.creating {}
val daggerInteropClasspath: Configuration by configurations.creating { isTransitive = false }

dependencies {
  // IntelliJ maven repo doesn't carry compiler test framework versions, so we'll pull from that as
  // needed for those tests
  val compilerTestFrameworkVersion: String

  // 2.3.0 changed the test gen APIs around into different packages
  "generator220CompileOnly"(libs.kotlin.compilerTestFramework)
  "generator230CompileOnly"(
    "org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:2.3.0-Beta1"
  )
  val generatorConfigToUse: String

  if (kotlinVersion >= KotlinVersion(2, 3)) {
    generatorConfigToUse = "generator230"
    compilerTestFrameworkVersion = "2.3.0-Beta1"
  } else {
    generatorConfigToUse = "generator220"
    compilerTestFrameworkVersion = libs.versions.kotlin.get()
  }
  testImplementation(sourceSets.named(generatorConfigToUse).map { it.output })
  testImplementation(
    "org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:$compilerTestFrameworkVersion"
  )
  testImplementation("org.jetbrains.kotlin:kotlin-compiler:$testCompilerVersion")

  testImplementation(project(":compiler"))
  testImplementation(project(":compiler-compat"))

  testImplementation(libs.kotlin.testJunit5)

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

  // Anvil KSP processors, only needs to be on the classpath at runtime since they're loaded via
  // ServiceLoader
  testRuntimeOnly(libs.anvil.kspCompiler)

  // Dependencies required to run the internal test framework.
  testRuntimeOnly(libs.kotlin.reflect)
  testRuntimeOnly(libs.kotlin.test)
  testRuntimeOnly(libs.kotlin.scriptRuntime)
  testRuntimeOnly(libs.kotlin.annotationsJvm)
}

val generateTests =
  tasks.register<JavaExec>("generateTests") {
    inputs
      .dir(layout.projectDirectory.dir("src/test/data"))
      .withPropertyName("testData")
      .withPathSensitivity(PathSensitivity.RELATIVE)

    testCompilerVersionProvider.orNull?.let { inputs.property("testCompilerVersion", it) }

    outputs.dir(layout.projectDirectory.dir("src/test/java")).withPropertyName("generatedTests")

    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.zacsweers.metro.compiler.GenerateTestsKt")
    workingDir = rootDir

    // Larger heap size
    minHeapSize = "128m"
    maxHeapSize = "1g"

    // Larger stack size
    jvmArgs("-Xss1m")
  }

tasks.withType<Test> {
  outputs.upToDateWhen { false }
  dependsOn(metroRuntimeClasspath)
  dependsOn(daggerInteropClasspath)
  inputs
    .dir(layout.projectDirectory.dir("src/test/data"))
    .withPropertyName("testData")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  workingDir = rootDir

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
