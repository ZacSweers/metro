// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

fun main() {
  generateTests<AbstractBoxTest, AbstractDiagnosticTest, AbstractFirDumpTest, AbstractIrDumpTest>()
}
