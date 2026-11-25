// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import java.io.File
import java.io.Flushable
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker

/**
 * Buffers [report]/[reportExpectOfLenientStub] calls into an internal log and replays them to
 * [delegate] on [flush]. Lets expect/actual IC links be collected during IR processing and written
 * through once afterward. Entries are deduplicated.
 */
internal class BufferingExpectActualTracker(private val delegate: ExpectActualTracker) :
  ExpectActualTracker by delegate, Flushable {
  private val reports = mutableSetOf<Report>()

  private val lenientStubs = mutableSetOf<File>()

  override fun report(expectedFile: File, actualFile: File) {
    reports.add(Report(expectedFile, actualFile))
  }

  override fun reportExpectOfLenientStub(expectedFile: File) {
    lenientStubs.add(expectedFile)
  }

  override fun flush() {
    for (report in reports) {
      delegate.report(report.expectedFile, report.actualFile)
    }
    for (expectedFile in lenientStubs) {
      delegate.reportExpectOfLenientStub(expectedFile)
    }
    reports.clear()
    lenientStubs.clear()
  }

  private data class Report(val expectedFile: File, val actualFile: File)
}
