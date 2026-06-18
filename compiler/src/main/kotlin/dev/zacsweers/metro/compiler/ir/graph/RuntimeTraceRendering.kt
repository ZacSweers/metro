// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.symbols.Symbols

/** Rendering rules shared by runtime trace spans for bindings and generated graph entry points. */
internal fun IrContextualTypeKey.runtimeTraceQualifier(): String? {
  val multibindingKeyData = typeKey.multibindingKeyData
  return if (multibindingKeyData == null) {
    typeKey.qualifier?.render(short = true, useRelativeClassNames = true)
  } else {
    multibindingKeyData.multibindingTypeKey
      ?.qualifier
      ?.render(short = true, useRelativeClassNames = true)
  }
}

/** Renders the canonical trace type without its qualifier. */
internal fun IrContextualTypeKey.runtimeTraceType(): String {
  return typeKey.render(short = true, includeQualifier = false, useRelativeClassNames = true)
}

/** Renders the contextual trace type, or `null` when it matches [runtimeTraceType]. */
internal fun IrContextualTypeKey.runtimeTraceContextualType(): String? {
  return when (wrappedType) {
    is WrappedType.Canonical<*> ->
      typeKey.render(short = true, includeQualifier = false, useRelativeClassNames = true)
    else -> render(short = true, includeQualifier = false, useRelativeClassNames = true)
  }
}

/** Runtime tracing does not trace the infrastructure used to create trace contexts. */
internal val IrContextualTypeKey.isRuntimeTracingInfra: Boolean
  get() {
    val classId = typeKey.classId
    return classId == Symbols.ClassIds.tracer || classId == Symbols.ClassIds.metroTraceContext
  }
