// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.diagnostic.SubmittedReportInfo.SubmissionStatus
import com.intellij.util.Consumer
import javax.swing.JPanel
import junit.framework.TestCase

class MetroErrorHandlerTest : TestCase() {

  fun testUnconfiguredReporterDoesNotStartSubmission() {
    var submitted: SubmittedReportInfo? = null

    val started =
      MetroErrorHandler(null)
        .submit(
          events = arrayOf(IdeaLoggingEvent("failure", IllegalStateException("broken"))),
          additionalInfo = null,
          parentComponent = JPanel(),
          consumer = Consumer { submitted = it },
        )

    assertFalse(started)
    assertNull(submitted)
  }

  fun testSuccessfulReporterCompletesSubmission() {
    val reported = mutableListOf<Throwable>()
    var submitted: SubmittedReportInfo? = null
    val handler =
      MetroErrorHandler(
        MetroErrorReporter { _, throwable, _ -> reported += throwable },
        executor = Runnable::run,
      )
    val first = IllegalStateException("first")
    val second = IllegalArgumentException("second")

    val started =
      handler.submit(
        events = arrayOf(IdeaLoggingEvent("first", first), IdeaLoggingEvent("second", second)),
        additionalInfo = "details",
        parentComponent = JPanel(),
        consumer = Consumer { submitted = it },
      )

    assertTrue(started)
    assertEquals(listOf(first, second), reported)
    assertEquals(SubmissionStatus.NEW_ISSUE, submitted?.status)
  }

  fun testReporterFailureCompletesSubmissionAsFailed() {
    val failure = IllegalStateException("offline")
    var submitted: SubmittedReportInfo? = null
    val handler =
      MetroErrorHandler(MetroErrorReporter { _, _, _ -> throw failure }, executor = Runnable::run)

    val started =
      handler.submit(
        events = arrayOf(IdeaLoggingEvent("failure", RuntimeException("broken"))),
        additionalInfo = null,
        parentComponent = JPanel(),
        consumer = Consumer { submitted = it },
      )

    // The submission starts. The failure arrives through the consumer once the report runs.
    assertTrue(started)
    assertEquals(SubmissionStatus.FAILED, submitted?.status)
    assertEquals("offline", submitted?.linkText)
  }
}
