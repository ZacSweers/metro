// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.lang.RuntimeException

/** An exception that signals to end processing but assumes all errors have been reported prior. */
internal class ExitProcessingException : RuntimeException()

internal fun exitProcessing(): Nothing = throw ExitProcessingException()
