// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.bugsnag.Bugsnag
import com.bugsnag.Severity
import com.intellij.diagnostic.AbstractMessage
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.diagnostic.SubmittedReportInfo.SubmissionStatus
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.Consumer
import java.awt.Component

/** Transport behind IntelliJ's error-report contract, separated so completion is testable. */
internal fun interface MetroErrorReporter {
  fun report(event: IdeaLoggingEvent, throwable: Throwable, additionalInfo: String?)
}

class MetroErrorHandler
internal constructor(
  private val reporter: MetroErrorReporter?,
  private val executor: (Runnable) -> Unit = { runnable ->
    ApplicationManager.getApplication().executeOnPooledThread(runnable)
  },
) : ErrorReportSubmitter() {

  constructor() : this(createBugsnagReporter())

  override fun getReportActionText(): String = "Send to Metro"

  override fun submit(
    events: Array<out IdeaLoggingEvent>,
    additionalInfo: String?,
    parentComponent: Component,
    consumer: Consumer<in SubmittedReportInfo>,
  ): Boolean {
    val activeReporter = reporter ?: return false
    // The platform calls submit from the fatal-error dialog on the EDT. The network report runs
    // on a background thread and the consumer is invoked on completion.
    executor {
      try {
        for (event in events) {
          val throwable =
            (event.data as? AbstractMessage)?.throwable
              ?: event.throwable
              ?: RuntimeException(event.message)
          activeReporter.report(event, throwable, additionalInfo)
        }
        consumer.consume(SubmittedReportInfo(null, null, SubmissionStatus.NEW_ISSUE))
      } catch (e: Exception) {
        logger<MetroErrorHandler>().warn("Could not send Metro error report", e)
        consumer.consume(SubmittedReportInfo(null, e.message, SubmissionStatus.FAILED))
      }
    }
    return true
  }

  private companion object {
    fun createBugsnagReporter(): MetroErrorReporter? {
      val key = BUGSNAG_KEY.takeIf { it.isNotBlank() } ?: return null
      val client =
        Bugsnag(key, false).apply {
          setAutoCaptureSessions(false)
          startSession()
          setAppVersion(VERSION)
          setProjectPackages("dev.zacsweers.metro.idea")
          addOnError { event ->
            val appInfo = ApplicationInfo.getInstance()
            event.addMetadata("Device", "osVersion", System.getProperty("os.version"))
            event.addMetadata("Device", "JRE", System.getProperty("java.version"))
            event.addMetadata("Device", "IDE Version", appInfo.fullVersion)
            event.addMetadata("Device", "IDE Build #", appInfo.build)
            if (GIT_SHA.isNotBlank()) {
              event.addMetadata("Device", "Plugin SHA", GIT_SHA)
            }
            PluginManagerCore.plugins.forEach { plugin ->
              event.addMetadata("Plugins", plugin.name, "${plugin.pluginId} : ${plugin.version}")
            }
            true
          }
        }
      return MetroErrorReporter { event, throwable, additionalInfo ->
        try {
          Bugsnag.addThreadMetadata("Data", "message", event.message)
          Bugsnag.addThreadMetadata("Data", "additional info", additionalInfo.orEmpty())
          Bugsnag.addThreadMetadata("Data", "stacktrace", event.throwableText)
          client.notify(throwable, Severity.ERROR)
        } finally {
          Bugsnag.clearThreadMetadata("Data")
        }
      }
    }
  }
}
