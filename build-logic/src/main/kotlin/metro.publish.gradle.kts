// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin

apply(plugin = "com.vanniktech.maven.publish")

apply(plugin = "com.autonomousapps.testkit")

project.extensions.create<MetroPublishExtension>("metroPublish").apply {
  artifactId.convention(project.name)
}

if (!isCompilerProject) {
  plugins.withType<KotlinBasePlugin> { configure<KotlinProjectExtension> { explicitApi() } }
}

tasks
  .named { it == "publishTestKitSupportForJavaPublicationToFunctionalTestRepository" }
  .configureEach { mustRunAfter(tasks.matching { it.name.startsWith("sign") }) }
