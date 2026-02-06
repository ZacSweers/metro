// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import com.vanniktech.maven.publish.DeploymentValidation
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
val jdkVersion = catalog.findVersion("jdk").get().requiredVersion
val jvmTargetVersion = catalog.findVersion("jvmTarget").get().requiredVersion

// Java configuration
pluginManager.withPlugin("java") {
  extensions.configure<JavaPluginExtension> {
    toolchain { languageVersion.set(JavaLanguageVersion.of(jdkVersion)) }
  }
  tasks.withType<JavaCompile>().configureEach { options.release.set(jvmTargetVersion.toInt()) }
}

// Suppress native access warnings in forked JVMs (Java 22+)
tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }

tasks.withType<JavaExec>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }

// Kotlin configuration
plugins.withType<KotlinBasePlugin> {
  // Skip explicitApi for samples and benchmark projects
  val useExplicitApi =
    "sample" !in project.path &&
      rootProject.name != "metro-samples" &&
      rootProject.name != "metro-benchmark"
  if (useExplicitApi) {
    configure<KotlinProjectExtension> { explicitApi() }
  }

  // TODO move this to a DSL
  // Configuration required to produce unique META-INF/*.kotlin_module file names
  val artifactId = project.findProperty("POM_ARTIFACT_ID")?.toString()
  tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
      progressiveMode.convention(true)
      if (this is KotlinJvmCompilerOptions) {
        jvmTarget.convention(JvmTarget.fromTarget(jvmTargetVersion))
        jvmDefault.convention(JvmDefaultMode.NO_COMPATIBILITY)
        freeCompilerArgs.addAll("-Xassertions=jvm", "-Xannotation-default-target=param-property")
        artifactId?.let(moduleName::convention)
      }
    }
  }
}

// Maven publish configuration
plugins.withId("com.vanniktech.maven.publish") {
  // Apply dokka for non-compiler projects
  if (project.path != ":compiler" && !project.path.startsWith(":compiler-compat")) {
    apply(plugin = "org.jetbrains.dokka")
  }

  extensions.configure<MavenPublishBaseExtension> {
    publishToMavenCentral(
      automaticRelease = true,
      validateDeployment = DeploymentValidation.VALIDATED,
    )
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