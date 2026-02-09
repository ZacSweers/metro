// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

apply(plugin = "com.vanniktech.maven.publish")
apply(plugin = "com.autonomousapps.testkit")

project.extensions.create<MetroPublishExtension>("metroPublish").apply {
  artifactId.convention(project.name)
}

tasks
  .named { it == "publishTestKitSupportForJavaPublicationToFunctionalTestRepository" }
  .configureEach { mustRunAfter(tasks.matching { it.name.startsWith("sign") }) }
