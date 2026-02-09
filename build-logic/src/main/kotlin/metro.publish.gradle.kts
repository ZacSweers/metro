// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

project.extensions.create<MetroPublishExtension>("metroPublish").apply {
  artifactId.convention(project.name)
}