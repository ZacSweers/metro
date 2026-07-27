// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.Project

val Project.isCompilerProject: Boolean
  get() = project.path.startsWith(":compiler")

val metroApiIgnoredPackages =
  listOf(
    "dev.zacsweers.metro.internal",
    "dev.zacsweers.metro.compiler.compat",
    "dev.zacsweers.metro.interop.dagger.internal",
    "dev.zacsweers.metro.interop.guice.internal",
    "dev.zacsweers.metro.trace.internal",
  )

val metroApiNonPublicMarkers =
  listOf(
    "dev.zacsweers.metro.ExperimentalMetroCoroutinesApi",
    "dev.zacsweers.metro.ExperimentalMetroApi",
    "dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi",
  )
