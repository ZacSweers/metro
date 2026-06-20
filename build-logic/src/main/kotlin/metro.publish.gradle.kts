// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin

apply(plugin = "com.vanniktech.maven.publish")

apply(plugin = "com.autonomousapps.testkit")

val extension = project.extensions.create<MetroArtifactExtension>("metroArtifact")

extension.artifactId.convention(project.name)

extension.name.convention(project.name)

extension.description.convention(providers.gradleProperty("POM_DESCRIPTION"))

extension.packaging.convention("jar")

afterEvaluate {
  extensions.configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
      val publicationArtifactId = artifactId
      val metroArtifactId = extension.artifactId.get()
      val hasProjectNamePrefix = publicationArtifactId.startsWith("${project.name}-")
      artifactId =
        when {
          publicationArtifactId == project.name -> metroArtifactId
          hasProjectNamePrefix -> metroArtifactId + publicationArtifactId.removePrefix(project.name)
          else -> publicationArtifactId
        }

      pom {
        name.set(extension.name)
        description.set(extension.description)
        packaging = extension.packaging.get()
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

// Conversely, when `testKitSupportForJava` exists, disable the `maven` -> FunctionalTest task so
// the two don't overwrite each other in the local repo. autonomousapps only creates this pub when
// the existing `maven` pub's GAV differs from `(group, project.name, version)` -- which happens
// for compat modules where metroArtifact's artifactId != project.name. Modules without the testkit
// pub
// (e.g. `:compiler`) need the `maven` -> FunctionalTest task to stay enabled; otherwise nothing
// installs them. autonomousapps creates the pub inside its own `afterEvaluate`, so check from a
// later one.
afterEvaluate {
  val publishing = extensions.findByType(PublishingExtension::class.java) ?: return@afterEvaluate
  if (publishing.publications.findByName("testKitSupportForJava") != null) {
    tasks.findByName("publishMavenPublicationToFunctionalTestRepository")?.enabled = false
  }
}
