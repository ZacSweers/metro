// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

apply(plugin = "com.vanniktech.maven.publish")

project.extensions.create<MetroPublishExtension>("metroPublish").apply {
  artifactId.convention(project.name)
}
