// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import com.vanniktech.maven.publish.DeploymentValidation
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.withType
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
val jdkVersion = catalog.findVersion("jdk").get().requiredVersion
val jvmTargetVersion = catalog.findVersion("jvmTarget").get().requiredVersion
val compilerJvmTargetVersion = catalog.findVersion("compilerJvmTarget").get().requiredVersion

val metroExtension =
  project.extensions.create<MetroProjectExtension>("metroProject").apply {
    jvmTarget.convention(
      if (isCompilerProject) {
        compilerJvmTargetVersion
      } else {
        jvmTargetVersion
      }
    )
  }

// Java configuration
pluginManager.withPlugin("java") {
  extensions.configure<JavaPluginExtension> {
    toolchain { languageVersion.convention(JavaLanguageVersion.of(jdkVersion)) }
  }
  tasks.withType<JavaCompile>().configureEach {
    options.release.convention(metroExtension.jvmTarget.map(String::toInt))
  }
}

// Suppress native access warnings and ReservedStackAccess warnings in forked JVMs
tasks.withType<Test>().configureEach {
  jvmArgs(
    "--enable-native-access=ALL-UNNAMED",
    "--sun-misc-unsafe-memory-access=allow",
    "-XX:StackReservedPages=0",
  )
}

tasks.withType<JavaExec>().configureEach {
  jvmArgs(
    "--enable-native-access=ALL-UNNAMED",
    "--sun-misc-unsafe-memory-access=allow",
    "-XX:StackReservedPages=0",
  )
}

// Kotlin configuration
plugins.withType<KotlinBasePlugin> {
  tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
      progressiveMode.convention(metroExtension.progressiveMode)
      languageVersion.convention(metroExtension.languageVersion)
      apiVersion.convention(metroExtension.apiVersion)
      if (this is KotlinJvmCompilerOptions) {
        jvmTarget.convention(metroExtension.jvmTarget.map(JvmTarget::fromTarget))
        jvmDefault.convention(JvmDefaultMode.NO_COMPATIBILITY)
        freeCompilerArgs.addAll("-Xassertions=jvm", "-Xannotation-default-target=param-property")
        if (isCompilerProject) {
          freeCompilerArgs.addAll(
            "-Xcontext-parameters",
            "-Xreturn-value-checker=full",
            "-Xcontext-sensitive-resolution",
            "-Xdata-flow-based-exhaustiveness",
            //  "-Xallow-contracts-on-more-functions",
            //  "-Xallow-condition-implies-returns-contracts",
            //  "-Xallow-holdsin-contract",
            // TODO next minor release
            //  "-Xwhen-expressions=indy",
            // TODO Kotlin 2.3.0
            //  "-Xexplicit-backing-fields",
          )
          if (project.name != "compiler-tests") {
            optIn.addAll(
              "kotlin.contracts.ExperimentalContracts",
              "kotlin.contracts.ExperimentalExtendedContracts",
              "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
              "org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI",
            )
          }
        }
      }
    }
  }
}

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
  // Suppress "WASI is an experimental feature" Node.js warnings
  tasks.withType<KotlinJsTest>().configureEach {
    if (name.contains("wasmWasi", ignoreCase = true)) {
      nodeJsArgs += "--no-warnings"
    }
  }
}

pluginManager.withPlugin("metro.publish") {
  val metroPublish = extensions.getByType<MetroPublishExtension>()
  tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
      if (this is KotlinJvmCompilerOptions) {
        // Configuration required to produce unique META-INF/*.kotlin_module file names
        moduleName.set(metroPublish.artifactId)
      }
    }
  }

  val isNotCompiler = project.path != ":compiler" && !project.path.startsWith(":compiler-compat")

  if (isNotCompiler) {
    val metroRuntimeLanguageVersion =
      catalog.findVersion("metro-runtime-languageVersion").get().requiredVersion
    val runtimeKotlinVersion = KotlinVersion.fromVersion(metroRuntimeLanguageVersion)
    metroExtension.languageVersion.convention(runtimeKotlinVersion)
    metroExtension.apiVersion.convention(runtimeKotlinVersion)
    if (runtimeKotlinVersion < KotlinVersion.DEFAULT) {
      metroExtension.progressiveMode.set(false)
    }
  }

  // Maven publish configuration
  plugins.withId("com.vanniktech.maven.publish") {
    // Apply dokka for non-compiler projects
    if (isNotCompiler) {
      apply(plugin = "org.jetbrains.dokka")
    }

    extensions.configure<MavenPublishBaseExtension> {
      publishToMavenCentral(
        automaticRelease = true,
        validateDeployment = DeploymentValidation.VALIDATED,
      )
    }
  }
}

// Android configuration
pluginManager.withPlugin("com.android.library") { apply(plugin = "metro.android") }

pluginManager.withPlugin("com.android.application") { apply(plugin = "metro.android") }

// Dokka configuration
pluginManager.withPlugin("org.jetbrains.dokka") {
  extensions.configure<DokkaExtension> {
    basePublicationsDirectory.convention(layout.buildDirectory.dir("dokkaDir"))
    dokkaSourceSets.configureEach {
      skipDeprecated.convention(true)
      documentedVisibilities.add(VisibilityModifier.Public)
      reportUndocumented.convention(true)
      perPackageOption {
        matchingRegex.convention(".*\\.internal.*")
        suppress.convention(true)
      }
      sourceLink {
        localDirectory.convention(layout.projectDirectory.dir("src"))
        val relPath = rootProject.projectDir.toPath().relativize(projectDir.toPath())
        remoteUrl(
          providers.gradleProperty("POM_SCM_URL").map { scmUrl -> "$scmUrl/tree/main/$relPath/src" }
        )
        remoteLineSuffix.convention("#L")
      }
    }
  }
}

plugins.withId("com.autonomousapps.testkit") {
  rootProject.tasks.named("installForFunctionalTest") {
    dependsOn(tasks.named("installForFunctionalTest"))
  }
}
