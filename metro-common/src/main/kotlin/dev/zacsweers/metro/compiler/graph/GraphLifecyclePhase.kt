// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.MetroClassIds
import org.jetbrains.kotlin.name.ClassId

/** Identifies when the generated graph invokes a callback. */
public enum class GraphLifecyclePhase(public val annotation: ClassId) {
  INIT(MetroClassIds.onGraphInit),
  CLOSE(MetroClassIds.onGraphClose),
}
