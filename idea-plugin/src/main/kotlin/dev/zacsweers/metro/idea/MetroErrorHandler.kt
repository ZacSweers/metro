// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.bugsnag.Bugsnag
import com.bugsnag.Severity
import com.intellij.diagnostic.AbstractMessage
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import java.awt.Component

class MetroErrorHandler : ErrorReportSubmitter() {
  private val bugsnag: Bugsnag? =
    BUGSNAG_KEY.takeIf { it.isNotBlank() }
      ?.let { key ->
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
      }

  override fun getReportActionText(): String = "Send to Metro"

  override fun submit(
    events: Array<out IdeaLoggingEvent>,
    additionalInfo: String?,
    parentComponent: Component,
    consumer: Consumer<in SubmittedReportInfo>,
  ): Boolean {
    val client = bugsnag ?: return true
    for (event in events) {
      val throwable =
        (event.data as? AbstractMessage)?.throwable
          ?: event.throwable
          ?: RuntimeException(event.message)
      try {
        Bugsnag.addThreadMetadata("Data", "message", event.message)
        Bugsnag.addThreadMetadata("Data", "additional info", additionalInfo.orEmpty())
        Bugsnag.addThreadMetadata("Data", "stacktrace", event.throwableText)
        client.notify(throwable, Severity.ERROR)
      } finally {
        Bugsnag.clearThreadMetadata("Data")
      }
    }
    return true
  }
}
