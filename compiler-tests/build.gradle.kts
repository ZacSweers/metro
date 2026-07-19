// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinUsages
import org.jetbrains.kotlin.gradle.targets.js.KotlinJsCompilerAttribute
import org.jetbrains.kotlin.gradle.targets.wasm.d8.D8EnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.d8.D8Plugin
import org.jetbrains.kotlin.tooling.core.KotlinToolingVersion
import org.jetbrains.kotlin.tooling.core.isDev
import org.jetbrains.kotlin.tooling.core.toKotlinVersion

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.buildConfig)
  java
}

project.plugins.apply(D8Plugin::class.java)

sourceSets {
  register("generator230")
  register("generator2320")
  register("generator240")
  register("generator2420")
}

val testCompilerVersionProvider = providers.gradleProperty("metro.testCompilerVersion")

val testCompilerVersion = testCompilerVersionProvider.orElse(libs.versions.kotlin).get()

val testKotlinVersion = KotlinToolingVersion(testCompilerVersion)

val kotlin23 = KotlinToolingVersion(KotlinVersion(2, 3))

val kotlin24Beta1 = KotlinToolingVersion(KotlinVersion(2, 4), "Beta1")

// Minimum supported 2.4.20 dev build. 2.4.20 dev builds ship KT-85292:
// `commonConfigurationForJvmTest` was renamed to `setupJvmPipelineSteps`, and the diagnostic / IR
// dump golden file extensions lost their `.fir.` infix. Anything < this still uses the legacy
// names + helper.
val kotlin2420Dev6138Version = "2.4.20-dev-6138"
val kotlin2420Dev6138 = KotlinToolingVersion(kotlin2420Dev6138Version)
val useKotlin2420DevFallbackArtifacts =
  testKotlinVersion.toKotlinVersion() == KotlinVersion(2, 4, 20) && testKotlinVersion.isDev
val kotlinArtifactsVersion =
  if (useKotlin2420DevFallbackArtifacts) {
    kotlin2420Dev6138Version
  } else {
    testCompilerVersion
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
    buildConfigField("String", "BUILD_COMPILER_VERSION", libs.versions.kotlin.map { "\"$it\"" })
    buildConfigField("String", "TEST_COMPILER_VERSION", "\"$testCompilerVersion\"")
    buildConfigField(
      "kotlin.KotlinVersion",
      "COMPILER_VERSION",
      "KotlinVersion(${testKotlinVersion.major}, ${testKotlinVersion.minor}, ${testKotlinVersion.patch})",
    )
    buildConfigField("String", "COMPILER_TOOLING_VERSION", "\"$testCompilerVersion\"")
  }
}

val metroRuntimeClasspath = configurations.create("metroRuntimeClasspath") { isTransitive = false }
val metroRuntimeCoroutinesClasspath =
  configurations.create("metroRuntimeCoroutinesClasspath") { isTransitive = false }
val coroutinesClasspath = configurations.create("coroutinesClasspath") { isTransitive = false }
val metroRuntimeKlibClasspath =
  configurations.create("metroRuntimeKlibClasspath") {
    isTransitive = false
    attributes {
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(KotlinUsages.KOTLIN_RUNTIME))
      attribute(KotlinPlatformType.attribute, KotlinPlatformType.js)
      attribute(KotlinJsCompilerAttribute.jsCompilerAttribute, KotlinJsCompilerAttribute.ir)
    }
  }
val metroRuntimeCoroutinesKlibClasspath =
  configurations.create("metroRuntimeCoroutinesKlibClasspath") {
    isTransitive = false
    attributes {
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(KotlinUsages.KOTLIN_RUNTIME))
      attribute(KotlinPlatformType.attribute, KotlinPlatformType.js)
      attribute(KotlinJsCompilerAttribute.jsCompilerAttribute, KotlinJsCompilerAttribute.ir)
    }
  }
val coroutinesKlibClasspath =
  configurations.create("coroutinesKlibClasspath") {
    // Coroutines' JS implementation depends on atomicfu. Keep that dependency while allowing the
    // compiler test framework to supply the Kotlin libraries for the compiler under test.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-js")
    attributes {
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(KotlinUsages.KOTLIN_RUNTIME))
      attribute(KotlinPlatformType.attribute, KotlinPlatformType.js)
      attribute(KotlinJsCompilerAttribute.jsCompilerAttribute, KotlinJsCompilerAttribute.ir)
    }
  }

val runtimeTracingClasspath = configurations.create("runtimeTracingClasspath")
val anvilRuntimeClasspath = configurations.create("anvilRuntimeClasspath") { isTransitive = false }
val kiAnvilRuntimeClasspath =
  configurations.create("kiAnvilRuntimeClasspath") { isTransitive = false }
// include transitive in this case to grab compose and circuit runtimes
val circuitRuntimeClasspath =
  configurations.create("circuitRuntimeClasspath") {
    attributes {
      // Force JVM variants
      // TODO in future non-jvm tests we need others
      attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
    }
  }
val circuitRuntimeKlibClasspath =
  configurations.create("circuitRuntimeKlibClasspath") {
    exclude(group = "org.jetbrains.kotlin")
    attributes {
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(KotlinUsages.KOTLIN_RUNTIME))
      attribute(KotlinPlatformType.attribute, KotlinPlatformType.js)
      attribute(KotlinJsCompilerAttribute.jsCompilerAttribute, KotlinJsCompilerAttribute.ir)
    }
  }

// include transitive in this case to grab jakarta and javax
val daggerRuntimeClasspath = configurations.create("daggerRuntimeClasspath") {}
val daggerInteropClasspath =
  configurations.create("daggerInteropClasspath") { isTransitive = false }
val hiltCoreClasspath = configurations.create("hiltCoreClasspath") { isTransitive = false }
// include transitive in this case to grab jakarta and javax
val guiceClasspath = configurations.create("guiceClasspath") {}
val javaxInteropClasspath = configurations.create("javaxInteropClasspath") { isTransitive = false }
val jakartaInteropClasspath =
  configurations.create("jakartaInteropClasspath") { isTransitive = false }
val jsKlibClasspath =
  configurations.create("jsKlibClasspath") {
    isTransitive = false
    attributes { attribute(KotlinPlatformType.attribute, KotlinPlatformType.js) }
  }
val wasmKlibClasspath =
  configurations.create("wasmKlibClasspath") {
    isTransitive = false
    attributes {
      attribute(Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java), "wasm")
    }
  }

// IntelliJ maven repo doesn't carry compiler test framework versions, so we'll pull from that as
// needed for those tests
var compilerTestFrameworkVersion: String
var reflectVersion: String
var generatorConfigToUse: String

check(testKotlinVersion >= kotlin23) {
  "compiler-tests requires Kotlin 2.3.0 or newer, but metro.testCompilerVersion=$testCompilerVersion"
}

generatorConfigToUse =
  // Any 2.4.20 dev test version compiles against the fallback 2420 artifacts (see
  // kotlinArtifactsVersion above), so the generator helpers must match that framework.
  if (useKotlin2420DevFallbackArtifacts || testKotlinVersion >= kotlin2420Dev6138) {
    "generator2420"
  } else if (testKotlinVersion >= kotlin24Beta1) {
    "generator240"
  } else if (testKotlinVersion.toKotlinVersion() >= KotlinVersion(2, 3, 20)) {
    "generator2320"
  } else {
    "generator230"
  }

compilerTestFrameworkVersion =
  if (useKotlin2420DevFallbackArtifacts) {
    kotlin2420Dev6138Version
  } else {
    testCompilerVersion
  }

reflectVersion =
  if (testKotlinVersion.minor == 3 && testKotlinVersion.isDev) {
    "2.3.20"
  } else if (useKotlin2420DevFallbackArtifacts) {
    kotlin2420Dev6138Version
  } else {
    testCompilerVersion
  }

dependencies {
  // 2.3.0 changed the test gen APIs around into different packages
  "generator230CompileOnly"(
    "org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:$compilerTestFrameworkVersion"
  )
  "generator230CompileOnly"("org.jetbrains.kotlin:kotlin-compiler:$compilerTestFrameworkVersion")
  "generator2320CompileOnly"("org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:2.3.20")
  "generator2320CompileOnly"("org.jetbrains.kotlin:kotlin-compiler:2.3.20")
  // Pinned to Beta2 (not Beta1) because Beta2 dropped `diagnosticsByFilePath` for
  // `diagnosticsByFile` -- the same late-on-the-2.4.0-branch rename 2.3.21 did.
  "generator240CompileOnly"(
    "org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:2.4.0-Beta2"
  )
  "generator240CompileOnly"("org.jetbrains.kotlin:kotlin-compiler:2.4.0-Beta2")
  // 2.4.20 dev builds renamed `commonConfigurationForJvmTest` to `setupJvmPipelineSteps`. Compile
  // this helper against the same 2.4.20 artifact set used at test runtime so its erased builder
  // receiver ABI matches the fallback artifacts.
  "generator2420CompileOnly"(
    "org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:$kotlinArtifactsVersion"
  )
  "generator2420CompileOnly"("org.jetbrains.kotlin:kotlin-compiler:$kotlinArtifactsVersion")

  testImplementation(sourceSets.named(generatorConfigToUse).map { it.output })
  testImplementation(
    "org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:$compilerTestFrameworkVersion"
  )
  testImplementation("org.jetbrains.kotlin:kotlin-compiler:$kotlinArtifactsVersion")
  testImplementation("org.jetbrains.kotlin:kotlin-compose-compiler-plugin:$kotlinArtifactsVersion")

  testImplementation(project(":compiler"))
  testImplementation(project(":compiler-compat"))
  testCompileOnly(project(":metro-common"))

  testImplementation(libs.kotlin.testJunit5)

  testRuntimeOnly(libs.ksp.symbolProcessing)
  testImplementation(libs.ksp.symbolProcessing.aaEmbeddable)
  testImplementation(libs.ksp.symbolProcessing.commonDeps)
  testImplementation(libs.ksp.symbolProcessing.api)
  testImplementation(libs.dagger.compiler)
  testImplementation(libs.hilt.compiler)
  testImplementation(libs.hilt.core)

  metroRuntimeClasspath(project(":runtime"))
  metroRuntimeCoroutinesClasspath(project(":runtime-coroutines"))
  coroutinesClasspath(libs.coroutines)
  metroRuntimeKlibClasspath(project(path = ":runtime", configuration = "jsRuntimeElements"))
  metroRuntimeCoroutinesKlibClasspath(
    project(path = ":runtime-coroutines", configuration = "jsRuntimeElements")
  )
  coroutinesKlibClasspath(libs.coroutines)
  runtimeTracingClasspath(project(":metro-trace"))

  daggerInteropClasspath(project(":interop-dagger"))

  guiceClasspath(project(":interop-guice"))
  guiceClasspath(libs.guice)

  javaxInteropClasspath(project(":interop-javax"))
  jakartaInteropClasspath(project(":interop-jakarta"))

  anvilRuntimeClasspath(libs.anvil.annotations)
  anvilRuntimeClasspath(libs.anvil.annotations.optional)

  daggerRuntimeClasspath(libs.dagger.runtime)

  hiltCoreClasspath(libs.hilt.core)

  kiAnvilRuntimeClasspath(libs.kotlinInject.anvil.runtime)
  kiAnvilRuntimeClasspath(libs.kotlinInject.runtime)

  circuitRuntimeClasspath(libs.circuit.runtime.presenter)
  circuitRuntimeClasspath(libs.circuit.runtime.ui)
  circuitRuntimeClasspath(libs.circuit.codegenAnnotations)
  circuitRuntimeClasspath(libs.circuit.subcircuit)
  circuitRuntimeKlibClasspath(libs.circuit.runtime.presenter)
  circuitRuntimeKlibClasspath(libs.circuit.runtime.ui)
  circuitRuntimeKlibClasspath(libs.circuit.codegenAnnotations)
  circuitRuntimeKlibClasspath(libs.circuit.subcircuit)
  circuitRuntimeKlibClasspath(libs.compose.ui)
  circuitRuntimeKlibClasspath(libs.kotlinInject.anvil.runtime.optional)
  circuitRuntimeKlibClasspath(libs.kotlinInject.runtime)
  circuitRuntimeKlibClasspath(libs.kotlinx.browser)

  jsKlibClasspath("org.jetbrains.kotlin:kotlin-stdlib-js:$kotlinArtifactsVersion")
  jsKlibClasspath("org.jetbrains.kotlin:kotlin-test-js:$kotlinArtifactsVersion")
  wasmKlibClasspath("org.jetbrains.kotlin:kotlin-stdlib-wasm-js:$kotlinArtifactsVersion")
  wasmKlibClasspath("org.jetbrains.kotlin:kotlin-stdlib-wasm-wasi:$kotlinArtifactsVersion")
  wasmKlibClasspath("org.jetbrains.kotlin:kotlin-test-wasm-js:$kotlinArtifactsVersion")
  wasmKlibClasspath("org.jetbrains.kotlin:kotlin-test-wasm-wasi:$kotlinArtifactsVersion")

  // Anvil KSP processors, only needs to be on the classpath at runtime since they're loaded via
  // ServiceLoader
  testRuntimeOnly(libs.anvil.kspCompiler)

  // Dependencies required to run the internal test framework.
  // Use the test compiler version because 2.3.20+ uses new APIs from here
  testRuntimeOnly("org.jetbrains.kotlin:kotlin-reflect:$reflectVersion")
  testRuntimeOnly(libs.junit)
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

    inputs.property("testCompilerVersion", testCompilerVersion)

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

val largeTestMode = providers.gradleProperty("metro.enableLargeTests").isPresent
val excludeJsBoxTests = providers.gradleProperty("metro.excludeJsBoxTests").isPresent

tasks.withType<Test> {
  outputs.upToDateWhen { false }
  dependsOn(runtimeTracingClasspath)

  // Inspo from https://youtrack.jetbrains.com/issue/KT-83440
  minHeapSize = "512m"
  maxHeapSize = if (largeTestMode) "5g" else "2g"
  jvmArgs(
    "-ea",
    "-XX:+UseCodeCacheFlushing",
    "-XX:ReservedCodeCacheSize=256m",
    "-XX:MaxMetaspaceSize=${if (largeTestMode) "512m" else "1g"}",
    "-XX:CICompilerCount=2",
    "-Djna.nosys=true",
  )

  dependsOn(metroRuntimeClasspath)
  dependsOn(metroRuntimeCoroutinesClasspath)
  dependsOn(metroRuntimeCoroutinesKlibClasspath)
  dependsOn(coroutinesClasspath)
  dependsOn(coroutinesKlibClasspath)
  dependsOn(metroRuntimeKlibClasspath)
  dependsOn(daggerInteropClasspath)
  dependsOn(hiltCoreClasspath)
  dependsOn(guiceClasspath)
  dependsOn(javaxInteropClasspath)
  dependsOn(jakartaInteropClasspath)
  dependsOn(circuitRuntimeClasspath)
  dependsOn(circuitRuntimeKlibClasspath)
  dependsOn(jsKlibClasspath)
  dependsOn(wasmKlibClasspath)
  inputs
    .dir(layout.projectDirectory.dir("src/test/data"))
    .withPropertyName("testData")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  workingDir = rootDir

  if (providers.gradleProperty("metro.debugCompilerTests").isPresent) {
    testLogging {
      showStandardStreams = true
      showStackTraces = true

      // Set options for log level LIFECYCLE
      events("started", "passed", "failed", "skipped")
      setExceptionFormat("short")

      // Setting this to 0 (the default is 2) will display the test executor that each test is
      // running on.
      displayGranularity = 0
    }

    val outputDir = isolated.rootProject.projectDirectory.dir("tmp").asFile.apply { mkdirs() }

    jvmArgs(
      "-XX:+HeapDumpOnOutOfMemoryError", // Produce a heap dump when an OOM occurs
      "-XX:+CrashOnOutOfMemoryError", // Produce a crash report when an OOM occurs
      "-XX:+UseGCOverheadLimit",
      "-XX:GCHeapFreeLimit=10",
      "-XX:GCTimeLimit=20",
      "-XX:HeapDumpPath=$outputDir",
      "-XX:ErrorFile=$outputDir",
    )
  }

  useJUnitPlatform()

  if (largeTestMode) {
    filter { includeTestsMatching("*StressTest*") }
  } else {
    filter { excludeTestsMatching("*StressTest*") }
  }
  if (excludeJsBoxTests) {
    filter {
      excludeTestsMatching("dev.zacsweers.metro.compiler.JsBoxTestGenerated*")
      excludeTestsMatching("dev.zacsweers.metro.compiler.JsFastInitBoxTestGenerated*")
      excludeTestsMatching("dev.zacsweers.metro.compiler.JsContributionProvidersBoxTestGenerated*")
    }
  }

  val testRuntimeClasspath = project.configurations.testRuntimeClasspath.get()
  // Extra property names are used by e.g. JavaCompilerFacade (thru
  // CodegenTestUtil.prepareJavacOptions) unconditionally
  setLibraryProperty("kotlin-stdlib", testRuntimeClasspath)
  setLibraryProperty("kotlin.full.stdlib.path", "kotlin-stdlib-jdk8", testRuntimeClasspath)
  setLibraryProperty("kotlin.reflect.jar.path", "kotlin-reflect", testRuntimeClasspath)
  setLibraryProperty("kotlin-test", testRuntimeClasspath)
  setLibraryProperty("kotlin-script-runtime", testRuntimeClasspath)
  setLibraryProperty(
    "kotlin.mockJDK.annotations.path",
    "kotlin-annotations-jvm",
    testRuntimeClasspath,
  )
  setLibraryProperty("kotlin-stdlib-js", jsKlibClasspath)
  setLibraryProperty("kotlin-test-js", jsKlibClasspath)
  setLibraryProperty("kotlin-stdlib-wasm-js", wasmKlibClasspath)
  setLibraryProperty("kotlin-stdlib-wasm-wasi", wasmKlibClasspath)
  setLibraryProperty("kotlin-test-wasm-js", wasmKlibClasspath)
  setLibraryProperty("kotlin-test-wasm-wasi", wasmKlibClasspath)
  setLibraryProperty("kotlin-common-stdlib", testRuntimeClasspath)
  setLibraryProperty("kotlin-stdlib-web", testRuntimeClasspath)

  val d8EnvSpec = project.the<D8EnvSpec>()
  dependsOn(d8EnvSpec.run { project.d8SetupTaskProvider })
  systemProperty("javascript.engine.path.V8", d8EnvSpec.executable.get())
  systemProperty("javascript.engine.path.repl", layout.projectDirectory.file("repl.js").asFile)
  systemProperty(
    "kotlin.js.test.root.out.dir",
    layout.buildDirectory.dir("js-test-output").get().asFile.absolutePath,
  )

  systemProperty("metro.shortLocations", "true")

  // Regenerate golden files in place: ./gradlew :compiler-tests:test -PupdateTestData=true
  if (providers.gradleProperty("updateTestData").isPresent) {
    systemProperty("kotlin.test.update.test.data", "true")
  }

  systemProperty("metroRuntime.classpath", metroRuntimeClasspath.asPath)
  systemProperty("metroRuntimeCoroutines.classpath", metroRuntimeCoroutinesClasspath.asPath)
  systemProperty("metroRuntime.klibClasspath", metroRuntimeKlibClasspath.asPath)
  systemProperty(
    "metroRuntimeCoroutines.klibClasspath",
    metroRuntimeCoroutinesKlibClasspath.asPath,
  )
  systemProperty("coroutines.classpath", coroutinesClasspath.asPath)
  systemProperty("coroutines.klibClasspath", coroutinesKlibClasspath.asPath)
  systemProperty("runtimeTracing.classpath", runtimeTracingClasspath.asPath)
  systemProperty("anvilRuntime.classpath", anvilRuntimeClasspath.asPath)
  systemProperty("kiAnvilRuntime.classpath", kiAnvilRuntimeClasspath.asPath)
  systemProperty("daggerRuntime.classpath", daggerRuntimeClasspath.asPath)
  systemProperty("daggerInterop.classpath", daggerInteropClasspath.asPath)
  systemProperty("hiltCore.classpath", hiltCoreClasspath.asPath)
  systemProperty("guice.classpath", guiceClasspath.asPath)
  systemProperty("javaxInterop.classpath", javaxInteropClasspath.asPath)
  systemProperty("jakartaInterop.classpath", jakartaInteropClasspath.asPath)
  systemProperty("circuit.classpath", circuitRuntimeClasspath.asPath)
  systemProperty("circuit.klibClasspath", circuitRuntimeKlibClasspath.asPath)
  systemProperty("ksp.testRuntimeClasspath", configurations.testRuntimeClasspath.get().asPath)

  // Properties required to run the internal test framework.
  systemProperty("idea.ignore.disabled.plugins", "true")
  systemProperty("idea.home.path", rootDir)
}

fun Test.setLibraryProperty(
  extraPropName: String,
  jarName: String,
  configuration: Configuration,
) {
  val regex = """$jarName-\d.*""".toRegex()
  val path = configuration.files.find { regex.matches(it.name) }?.absolutePath ?: return
  systemProperty("org.jetbrains.kotlin.test.$jarName", path)
  if (extraPropName.isNotEmpty()) systemProperty(extraPropName, path)
}

fun Test.setLibraryProperty(
  jarName: String,
  configuration: Configuration,
) = setLibraryProperty("", jarName, configuration)
