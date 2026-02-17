// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.provider.Property

interface MetroPublishExtension {
  val artifactId: Property<String>
}
