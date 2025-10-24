// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.interop

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.NonExistLocation
import java.io.PrintWriter
import java.io.StringWriter
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector

internal class TestKSPLogger(
  private val messageCollector: MessageCollector,
  private val allWarningsAsErrors: Boolean,
) : KSPLogger {

  companion object {
    const val PREFIX = "[ksp] "
  }

  data class Event(val severity: CompilerMessageSeverity, val message: String)

  val recordedEvents = mutableListOf<Event>()

  private fun convertMessage(message: String, symbol: KSNode?): String =
    when (val location = symbol?.location) {
      is FileLocation -> "$PREFIX${location.filePath}:${location.lineNumber}: $message"
      is NonExistLocation,
      null -> "$PREFIX$message"
    }

  override fun logging(message: String, symbol: KSNode?) {
    recordedEvents.add(Event(CompilerMessageSeverity.LOGGING, convertMessage(message, symbol)))
  }

  override fun info(message: String, symbol: KSNode?) {
    recordedEvents.add(Event(CompilerMessageSeverity.INFO, convertMessage(message, symbol)))
  }

  override fun warn(message: String, symbol: KSNode?) {
    val severity =
      if (allWarningsAsErrors) CompilerMessageSeverity.ERROR else CompilerMessageSeverity.WARNING
    recordedEvents.add(Event(severity, convertMessage(message, symbol)))
  }

  override fun error(message: String, symbol: KSNode?) {
    recordedEvents.add(Event(CompilerMessageSeverity.ERROR, convertMessage(message, symbol)))
  }

  override fun exception(e: Throwable) {
    val writer = StringWriter()
    e.printStackTrace(PrintWriter(writer))
    recordedEvents.add(Event(CompilerMessageSeverity.EXCEPTION, writer.toString()))
  }

  fun reportAll(reportToCompilerSeverity: Set<CompilerMessageSeverity>) {
    var errorReported = false
    for (event in recordedEvents) {
      if (!errorReported && event.severity in reportToCompilerSeverity) {
        messageCollector.report(event.severity, event.message)
        if (event.severity <= CompilerMessageSeverity.ERROR) {
          errorReported = true
        }
      }
    }
    if (errorReported) {
      messageCollector.report(
        CompilerMessageSeverity.ERROR,
        "Error occurred in KSP, check log for detail",
      )
    }
  }
}
