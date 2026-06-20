// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.provider.Property

interface MetroArtifactExtension {
  val artifactId: Property<String>
  val name: Property<String>
  val description: Property<String>
  val packaging: Property<String>
}
