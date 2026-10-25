// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.MetroClassIds
import org.jetbrains.kotlin.name.ClassId

/** Identifies when the generated graph invokes a callback. */
public enum class GraphLifecyclePhase(
  public val annotation: ClassId,
  /** The runtime trace kind of the span around the whole phase. */
  public val traceKind: String,
) {
  INIT(MetroClassIds.onGraphInit, "GraphInit"),
  CLOSE(MetroClassIds.onGraphClose, "GraphClose");

  /** The runtime trace kind of the span around one callback, like `OnGraphInit`. */
  public val callbackTraceKind: String
    get() = annotation.shortClassName.asString()

  public companion object {
    /** Returns the phase whose annotation is [classId], or null. */
    public fun forAnnotation(classId: ClassId?): GraphLifecyclePhase? = entries.firstOrNull {
      it.annotation == classId
    }
  }
}
