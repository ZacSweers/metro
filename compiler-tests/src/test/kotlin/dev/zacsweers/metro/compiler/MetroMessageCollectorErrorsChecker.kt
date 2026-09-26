// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.util.Collections
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestService
import org.jetbrains.kotlin.test.services.TestServices
import org.opentest4j.AssertionFailedError

/**
 * Records errors that Metro reports through its [MessageCollector].
 *
 * Metro uses the message collector when a diagnostic has no source element to attach to. Test
 * pipelines only check diagnostics. Without this, these errors pass silently.
 */
class MetroMessageCollectorErrors : TestService {
  private val _errors = Collections.synchronizedList(mutableListOf<String>())
  val errors: List<String>
    get() = synchronized(_errors) { _errors.toList() }

  fun wrap(module: TestModule, delegate: MessageCollector): MessageCollector =
    object : MessageCollector by delegate {
      override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
      ) {
        if (severity.isError) {
          val renderedLocation = location?.let { "${it.path}:${it.line}:${it.column}: " }.orEmpty()
          _errors += "[${module.name}] $renderedLocation$message"
        }
        delegate.report(severity, message, location)
      }
    }
}

val TestServices.metroMessageCollectorErrors: MetroMessageCollectorErrors by
  TestServices.testServiceAccessor()

/** Fails the test if Metro reported any errors through its message collector. */
class MetroMessageCollectorErrorsChecker(testServices: TestServices) :
  MetroAfterAnalysisCheckerCompat(testServices) {
  override fun checkAfterAnalysis(thereWereFailures: Boolean) {
    val errors = testServices.metroMessageCollectorErrors.errors
    if (errors.isEmpty()) {
      return
    }
    val message = buildString {
      appendLine(
        "Metro reported errors through its message collector. Test goldens can't capture " +
          "these. Report them on a declaration in the module being compiled."
      )
      for (error in errors) {
        appendLine()
        appendLine(error)
      }
    }
    throw AssertionFailedError(message)
  }
}
