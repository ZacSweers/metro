// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.hasAnyAnnotation
import dev.zacsweers.metro.idea.qualifierAnnotation
import org.jetbrains.kotlin.analysis.api.KaSession

/** Extracts a factory-provided value with the concrete type of its inherited SAM parameter. */
internal fun CallableParameterView.instanceBindingData(
  session: KaSession,
  options: MetroOptions,
): List<BindingData> =
  with(session) {
    if (!symbol.hasAnyAnnotation(options.providesAnnotations)) return@with emptyList()
    listOf(
      BindingData(
        typeKey(returnType, qualifierAnnotation(symbol, options)),
        BindingData.Kind.BOUND_INSTANCE,
        null,
        null,
        isGraphPrivate = symbol.annotations.any { it.classId == MetroClassIds.graphPrivate },
      )
    )
  }
