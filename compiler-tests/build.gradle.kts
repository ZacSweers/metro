// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.gradle.kotlin.dsl.sourceSets
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.tooling.core.KotlinToolingVersion
import org.jetbrains.kotlin.tooling.core.isDev
import org.jetbrains.kotlin.tooling.core.toKotlinVersion

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.buildConfig)
  java
}

sourceSets {
  register("generator220")
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

// First 2.4.20 dev build that ships KT-85292: `commonConfigurationForJvmTest` was renamed to
// `setupJvmPipelineSteps`, and the diagnostic / IR dump golden file extensions lost their `.fir.`
// infix. Anything < this still uses the legacy names + helper.
val kotlin2420Dev835 = KotlinToolingVersion(KotlinVersion(2, 4, 20), "dev-835")

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
      "KotlinVersion(${testKotlinVersion.major}, ${testKotlinVersion.minor}, ${testKotlinVersion.patch})",
    )
  }
}

val metroRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }
val anvilRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }
val kiAnvilRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }
// include transitive in this case to grab compose and circuit runtimes
val circuitRuntimeClasspath: Configuration by configurations.creating {
  attributes {
    // Force JVM variants
    // TODO in future non-jvm tests we need others
    attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
  }
}

// include transitive in this case to grab jakarta and javax
val daggerRuntimeClasspath: Configuration by configurations.creating {}
val daggerInteropClasspath: Configuration by configurations.creating { isTransitive = false }
// include transitive in this case to grab jakarta and javax
val guiceClasspath: Configuration by configurations.creating {}
val javaxInteropClasspath: Configuration by configurations.creating { isTransitive = false }
val jakartaInteropClasspath: Configuration by configurations.creating { isTransitive = false }
val wasmKlibClasspath: Configuration by configurations.creating {
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

if (testKotlinVersion >= kotlin23) {
  generatorConfigToUse =
    if (testKotlinVersion >= kotlin2420Dev835) {
      "generator2420"
    } else if (testKotlinVersion >= kotlin24Beta1) {
      "generator240"
    } else if (testKotlinVersion.toKotlinVersion() >= KotlinVersion(2, 3, 20)) {
      "generator2320"
    } else {
      "generator230"
    }
  compilerTestFrameworkVersion = testCompilerVersion
  reflectVersion =
    if (testKotlinVersion.minor == 3 && testKotlinVersion.isDev) {
      "2.3.20"
    } else {
      testCompilerVersion
    }
} else {
  generatorConfigToUse = "generator220"
  compilerTestFrameworkVersion = "2.2.20"
  reflectVersion = "2.2.20"
}

dependencies {
  // 2.3.0 changed the test gen APIs around into different packages
  "generator220CompileOnly"("org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:2.2.20")
  "generator220CompileOnly"("org.jetbrains.kotlin:kotlin-compiler:2.2.20")
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
  // 2.4.20-dev-835 renamed `commonConfigurationForJvmTest` to `setupJvmPipelineSteps`.
  "generator2420CompileOnly"(
    "org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:2.4.20-dev-835"
  )
  "generator2420CompileOnly"("org.jetbrains.kotlin:kotlin-compiler:2.4.20-dev-835")

  testImplementation(sourceSets.named(generatorConfigToUse).map { it.output })
  testImplementation(
    "org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:$compilerTestFrameworkVersion"
  )
  testImplementation("org.jetbrains.kotlin:kotlin-compiler:$testCompilerVersion")
  testImplementation("org.jetbrains.kotlin:kotlin-compose-compiler-plugin:$testCompilerVersion")

  testImplementation(project(":compiler"))
  testImplementation(project(":compiler-compat"))

  testImplementation(libs.kotlin.testJunit5)

  testRuntimeOnly(libs.ksp.symbolProcessing)
  testImplementation(libs.ksp.symbolProcessing.aaEmbeddable)
  testImplementation(libs.ksp.symbolProcessing.commonDeps)
  testImplementation(libs.ksp.symbolProcessing.api)
  testImplementation(libs.dagger.compiler)

  metroRuntimeClasspath(project(":runtime"))
  daggerInteropClasspath(project(":interop-dagger"))
  guiceClasspath(project(":interop-guice"))
  guiceClasspath(libs.guice)
  javaxInteropClasspath(project(":interop-javax"))
  jakartaInteropClasspath(project(":interop-jakarta"))
  anvilRuntimeClasspath(libs.anvil.annotations)
  anvilRuntimeClasspath(libs.anvil.annotations.optional)
  daggerRuntimeClasspath(libs.dagger.runtime)
  kiAnvilRuntimeClasspath(libs.kotlinInject.anvil.runtime)
  kiAnvilRuntimeClasspath(libs.kotlinInject.runtime)
  circuitRuntimeClasspath(libs.circuit.runtime.presenter)
  circuitRuntimeClasspath(libs.circuit.runtime.ui)
  circuitRuntimeClasspath(libs.circuit.codegenAnnotations)

  wasmKlibClasspath("org.jetbrains.kotlin:kotlin-stdlib-wasm-js:$testCompilerVersion")
  wasmKlibClasspath("org.jetbrains.kotlin:kotlin-stdlib-wasm-wasi:$testCompilerVersion")
  wasmKlibClasspath("org.jetbrains.kotlin:kotlin-test-wasm-js:$testCompilerVersion")
  wasmKlibClasspath("org.jetbrains.kotlin:kotlin-test-wasm-wasi:$testCompilerVersion")

  // Anvil KSP processors, only needs to be on the classpath at runtime since they're loaded via
  // ServiceLoader
  testRuntimeOnly(libs.anvil.kspCompiler)

  // Dependencies required to run the internal test framework.
  // Use the test compiler version because 2.3.20+ uses new APIs from here
  testRuntimeOnly("org.jetbrains.kotlin:kotlin-reflect:$reflectVersion")
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

tasks.withType<Test> {
  outputs.upToDateWhen { false }

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
  dependsOn(daggerInteropClasspath)
  dependsOn(guiceClasspath)
  dependsOn(javaxInteropClasspath)
  dependsOn(jakartaInteropClasspath)
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

  val testRuntimeClasspath = project.configurations.testRuntimeClasspath.get()
  setLibraryProperty("kotlin.minimal.stdlib.path", "kotlin-stdlib", "jar", testRuntimeClasspath)
  setLibraryProperty("kotlin.full.stdlib.path", "kotlin-stdlib-jdk8", "jar", testRuntimeClasspath)
  setLibraryProperty("kotlin.reflect.jar.path", "kotlin-reflect", "jar", testRuntimeClasspath)
  setLibraryProperty("kotlin.test.jar.path", "kotlin-test", "jar", testRuntimeClasspath)
  setLibraryProperty(
    "kotlin.script.runtime.path",
    "kotlin-script-runtime",
    "jar",
    testRuntimeClasspath,
  )
  setLibraryProperty(
    "kotlin.annotations.path",
    "kotlin-annotations-jvm",
    "jar",
    testRuntimeClasspath,
  )
  setLibraryProperty("kotlin.js.stdlib.path", "kotlin-stdlib-js", "jar", testRuntimeClasspath)
  setLibraryProperty("kotlin.js.test.path", "kotlin-test-js", "jar", testRuntimeClasspath)
  setLibraryProperty(
    "kotlin.common.stdlib.path",
    "kotlin-common-stdlib",
    "jar",
    testRuntimeClasspath,
  )
  setLibraryProperty("kotlin.web.stdlib.path", "kotlin-stdlib-web", "jar", testRuntimeClasspath)
  setLibraryProperty(
    "kotlin.wasm.stdlib.wasm-js.path",
    "kotlin-stdlib-wasm-js",
    "klib",
    wasmKlibClasspath,
  )
  setLibraryProperty(
    "kotlin.wasm.stdlib.wasm-wasi.path",
    "kotlin-stdlib-wasm-wasi",
    "klib",
    wasmKlibClasspath,
  )
  setLibraryProperty(
    "kotlin.wasm.test.wasm-js.path",
    "kotlin-test-wasm-js",
    "klib",
    wasmKlibClasspath,
  )
  setLibraryProperty(
    "kotlin.wasm.test.wasm-wasi.path",
    "kotlin-test-wasm-wasi",
    "klib",
    wasmKlibClasspath,
  )

  systemProperty("metro.shortLocations", "true")

  systemProperty("metroRuntime.classpath", metroRuntimeClasspath.asPath)
  systemProperty("anvilRuntime.classpath", anvilRuntimeClasspath.asPath)
  systemProperty("kiAnvilRuntime.classpath", kiAnvilRuntimeClasspath.asPath)
  systemProperty("daggerRuntime.classpath", daggerRuntimeClasspath.asPath)
  systemProperty("daggerInterop.classpath", daggerInteropClasspath.asPath)
  systemProperty("guice.classpath", guiceClasspath.asPath)
  systemProperty("javaxInterop.classpath", javaxInteropClasspath.asPath)
  systemProperty("jakartaInterop.classpath", jakartaInteropClasspath.asPath)
  systemProperty("circuit.classpath", circuitRuntimeClasspath.asPath)
  systemProperty("ksp.testRuntimeClasspath", configurations.testRuntimeClasspath.get().asPath)

  // Properties required to run the internal test framework.
  systemProperty("idea.ignore.disabled.plugins", "true")
  systemProperty("idea.home.path", rootDir)
}

fun Test.setLibraryProperty(
  propName: String,
  jarName: String,
  extension: String,
  configuration: Configuration,
) {
  val regex = "$jarName-\\d.*$extension".toRegex()
  val path = configuration.files.find { regex.matches(it.name) }?.absolutePath ?: return
  systemProperty(propName, path)
}
