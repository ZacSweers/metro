// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

import java.util.Locale
import org.gradle.api.Project

internal fun String.capitalizeUS() = replaceFirstChar {
  if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
}

internal val Project.logVerbosely: Boolean
  get() {
    return providers.gradleProperty("metro.logVerbosely").isPresent
  }
