// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.Project

val Project.isCompilerProject: Boolean
  get() = project.path.startsWith(":compiler")
