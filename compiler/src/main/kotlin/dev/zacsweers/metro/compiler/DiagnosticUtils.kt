// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

private val separatorsRegex = Regex("[-_]")

internal fun createDiagnosticReportPath(diagnosticKey: String, fileName: String): String {
  return "$diagnosticKey/${fileName.replace(separatorsRegex, "/")}"
}
