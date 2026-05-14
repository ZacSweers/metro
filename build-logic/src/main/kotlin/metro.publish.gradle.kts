// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import foundry.gradle.properties.PropertyResolver
import org.gradle.api.publish.PublishingExtension
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin

apply(plugin = "com.vanniktech.maven.publish")

apply(plugin = "com.autonomousapps.testkit")

val extension = project.extensions.create<MetroPublishExtension>("metroPublish")

val artifactIdProvider =
  PropertyResolver(project).optionalStringProvider("POM_ARTIFACT_ID", project.name)

extension.artifactId.convention(artifactIdProvider)

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
// for compat modules where POM_ARTIFACT_ID != project.name. Modules without the testkit pub
// (e.g. `:compiler`) need the `maven` -> FunctionalTest task to stay enabled; otherwise nothing
// installs them. autonomousapps creates the pub inside its own `afterEvaluate`, so check from a
// later one.
afterEvaluate {
  val publishing = extensions.findByType(PublishingExtension::class.java) ?: return@afterEvaluate
  if (publishing.publications.findByName("testKitSupportForJava") != null) {
    tasks.findByName("publishMavenPublicationToFunctionalTestRepository")?.enabled = false
  }
}
