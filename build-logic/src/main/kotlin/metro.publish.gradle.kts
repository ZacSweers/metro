// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import com.dropbox.gradle.plugins.dependencyguard.DependencyGuardPluginExtension
import me.tylerbwong.gradle.metalava.Format
import me.tylerbwong.gradle.metalava.extension.MetalavaExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin

apply(plugin = "com.vanniktech.maven.publish")

apply(plugin = "com.autonomousapps.testkit")

val isCompilerArtifact = project.path == ":compiler" || project.path.startsWith(":compiler-compat")
val isCommonArtifact = project.path == ":metro-common"
val isCompatibilityCheckExcluded = isCompilerArtifact || isCommonArtifact

if (!isCompatibilityCheckExcluded) {
  apply(plugin = "com.dropbox.dependency-guard")
  apply(plugin = "me.tylerbwong.gradle.metalava")

  val metalavaPackageFilter = buildList {
    add("dev.zacsweers.metro.*")
    metroApiIgnoredPackages.forEach { add("-$it.*") }
    add("dev.zacsweers.metrox.*")
  }
    .joinToString(":")

  configure<MetalavaExtension> {
    format.set(Format.V4)
    filename.set("api/metalava.txt")
    reportWarningsAsErrors.set(true)
    arguments.add("--stub-packages=$metalavaPackageFilter")
    arguments.addAll("--jdk-home", System.getProperty("java.home"))
    hiddenAnnotations.addAll(metroApiNonPublicMarkers)
  }

  pluginManager.withPlugin("com.github.gmazzo.buildconfig") {
    val generateBuildConfigClasses = tasks.named("generateBuildConfigClasses")
    tasks
      .matching { it.name.startsWith("metalava") }
      .configureEach { dependsOn(generateBuildConfigClasses) }
  }

  pluginManager.withPlugin("com.android.library") {
    configure<DependencyGuardPluginExtension> { configuration("releaseRuntimeClasspath") }
    tasks
      .matching { it.name.startsWith("metalava") }
      .configureEach {
        enabled = name.endsWith("Release")
      }
  }

  pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
    configure<DependencyGuardPluginExtension> { configuration("androidRuntimeClasspath") }
  }

  pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    configure<MetalavaExtension> { arguments.addAll("--hide", "DuplicateSourceClass") }
    configure<DependencyGuardPluginExtension> { configuration("jvmRuntimeClasspath") }
  }

  pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    configure<DependencyGuardPluginExtension> { configuration("runtimeClasspath") }
  }
}

val extension = project.extensions.create<MetroArtifactExtension>("metroArtifact")

extension.artifactId.convention(project.name)

extension.name.convention(project.name)

extension.description.convention(providers.gradleProperty("POM_DESCRIPTION"))

afterEvaluate {
  extensions.configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
      val publicationArtifactId = artifactId
      val metroArtifactId = extension.artifactId.get()
      val hasProjectNamePrefix = publicationArtifactId.startsWith("${project.name}-")
      val isGradlePluginMarker = publicationArtifactId.endsWith(".gradle.plugin")
      artifactId =
        when {
          publicationArtifactId == project.name -> metroArtifactId
          hasProjectNamePrefix -> metroArtifactId + publicationArtifactId.removePrefix(project.name)
          else -> publicationArtifactId
        }

      pom {
        name.set(extension.name)
        description.set(extension.description)
        if (!isGradlePluginMarker && extension.packaging.isPresent) {
          packaging = extension.packaging.get()
        }
      }
    }
  }
}

// Compiler-compat artifacts get explicit API mode too; only the main :compiler module is exempt.
if (project.path != ":compiler") {
  plugins.withType<KotlinBasePlugin> { configure<KotlinProjectExtension> { explicitApi() } }
}

// Every publish task must run after every sign task to avoid implicit-dependency validation
// errors where the testkit publication and main publication share outputs from each other's
// signing tasks.
tasks
  .named { it.startsWith("publish") && it.contains("PublicationTo") }
  .configureEach { mustRunAfter(tasks.matching { it.name.startsWith("sign") }) }

// `testKitSupportForJava` is only meant for the local FunctionalTest repo; don't let it publish
// to Maven Central where it would race the real `maven` publication at the same coordinates.
tasks
  .matching { it.name == "publishTestKitSupportForJavaPublicationToMavenCentralRepository" }
  .configureEach { enabled = false }

// When TestKit adds its coordinate-correcting publication, it replaces the local `maven` publish.
// Modules whose existing coordinates already match `(group, project.name, version)` keep the
// original task. TestKit creates the extra publication in `afterEvaluate`, so check after it.
afterEvaluate {
  val publishing = extensions.findByType(PublishingExtension::class.java) ?: return@afterEvaluate
  if (publishing.publications.findByName("testKitSupportForJava") != null) {
    tasks.findByName("publishMavenPublicationToFunctionalTestRepository")?.enabled = false
  }
}
