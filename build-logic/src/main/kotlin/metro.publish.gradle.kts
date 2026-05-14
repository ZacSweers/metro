// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import foundry.gradle.properties.PropertyResolver
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
// The symmetric `maven` -> FunctionalTest task is left enabled: modules where artifactId matches
// project.name (e.g. `:compiler`) don't get a `testKitSupportForJava` pub at all, so that task is
// the only thing installing them into the FunctionalTest repo.
tasks
  .matching { it.name == "publishTestKitSupportForJavaPublicationToMavenCentralRepository" }
  .configureEach { enabled = false }
