// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import java.io.File
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker

internal class RecordingExpectActualTracker(
  private val context: IrMetroContext,
  private val delegate: ExpectActualTracker,
) : ExpectActualTracker by delegate {
  override fun report(expectedFile: File, actualFile: File) {
    delegate.report(expectedFile, actualFile)
    context.logExpectActualReport(expectedFile, actualFile)
  }

  override fun reportExpectOfLenientStub(expectedFile: File) {
    delegate.reportExpectOfLenientStub(expectedFile)
    context.logExpectActualReport(expectedFile, null)
  }
}
