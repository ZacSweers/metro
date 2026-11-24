// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.test.assertEquals
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestService
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.moduleStructure
import org.opentest4j.AssertionFailedError

/**
 * Records errors and expected sourceless warnings from Metro's [MessageCollector].
 *
 * Metro uses the collector when a diagnostic has no source element. The diagnostic handlers cannot
 * see these messages.
 */
class MetroMessageCollector : TestService {
  private val _errors = mutableListOf<String>()
  val errors: List<String>
    get() = _errors

  private val warnings = mutableMapOf<String, MutableList<String>>()

  fun sourcelessWarnings(module: TestModule): List<String> = warnings[module.name].orEmpty()

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
        if (
          severity.isWarning &&
            location == null &&
            MetroDirectives.EXPECT_METRO_COLLECTOR_WARNING in module.directives
        ) {
          warnings.getOrPut(module.name, ::mutableListOf).add(message)
        }
        delegate.report(severity, message, location)
      }
    }
}

val TestServices.metroMessageCollector: MetroMessageCollector by TestServices.testServiceAccessor()

/** Checks expected warnings and rejects unexpected errors from Metro's collector. */
class MetroMessageCollectorChecker(testServices: TestServices) :
  MetroAfterAnalysisCheckerCompat(testServices) {
  override fun checkAfterAnalysis(thereWereFailures: Boolean) {
    val collector = testServices.metroMessageCollector
    val errors = collector.errors
    if (errors.isNotEmpty()) {
      val message = buildString {
        appendLine(
          "Metro reported errors through its message collector. Test goldens can't capture " +
            "these. Report them on a declaration in the module being compiled.",
        )
        for (error in errors) {
          appendLine()
          appendLine(error)
        }
      }
      throw AssertionFailedError(message)
    }

    for (module in testServices.moduleStructure.modules) {
      val expected = module.directives[MetroDirectives.EXPECT_METRO_COLLECTOR_WARNING]
      if (expected.isEmpty()) {
        continue
      }
      val actual = collector.sourcelessWarnings(module)
      // Kotlin parses this directive into words. Compare one complete warning per module.
      assertEquals(
        listOf(expected.joinToString(" ")),
        actual,
        "Metro collector warnings in ${module.name}",
      )
    }
  }
}
