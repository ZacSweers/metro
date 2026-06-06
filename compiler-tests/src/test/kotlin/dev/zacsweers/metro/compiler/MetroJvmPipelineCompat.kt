// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.FirParser

internal fun Any.setupMetroJvmPipelineCompat(parser: FirParser) {
  val method =
    Class.forName("dev.zacsweers.metro.compiler.MetroJvmPipelineKt").methods.single {
      it.name == "setupMetroJvmPipeline" && it.parameterTypes.size == 2
    }
  method.invoke(null, this, parser)
}
