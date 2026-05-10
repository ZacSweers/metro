// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.io.File
import org.jetbrains.kotlin.test.WrappedException
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.directives.model.RegisteredDirectives
import org.jetbrains.kotlin.test.directives.model.singleOrZeroValue
import org.jetbrains.kotlin.test.model.AfterAnalysisChecker
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager
import org.opentest4j.AssertionFailedError

/**
 * AfterAnalysisChecker that verifies Metro report and trace outputs.
 *
 * When [MetroDirectives.REPORTS_DESTINATION] is configured and [MetroDirectives.CHECK_REPORTS]
 * specifies report names, this checker will:
 * 1. Find the specified report files in the reports destination
 * 2. Compare each report file against an expected file alongside the test data
 *
 * Expected files should be named: `<testFile>/<diagnosticKey>/<path>/<reportName>.txt`
 *
 * Example usage:
 * ```
 * // REPORTS_DESTINATION: metro/reports
 * // CHECK_REPORTS: merging-unmatched-exclusions-fir/test/AppGraph
 * // CHECK_REPORTS: merging-unmatched-replacements-ir/test/AppGraph
 * ```
 *
 * This will look for report files named `merging-unmatched-exclusions-fir/test/AppGraph.txt` and
 * `merging-unmatched-replacements-ir/test/AppGraph.txt` in the reports directory, and compare them
 * against `<testFile>/merging-unmatched-exclusions-fir/test/AppGraph.txt` and
 * `<testFile>/merging-unmatched-replacements-ir/test/AppGraph.txt` respectively.
 *
 * When [MetroDirectives.CHECK_TRACES] is set, also asserts that trace files were produced under
 * [MetroDirectives.TRACE_DESTINATION] (or [DEFAULT_TRACES_DIR] when unset), every filename matches
 * `<id>-(fir|ir)-<moduleName>.perfetto-trace`, all files share the same `<id>` prefix, and both
 * phases are represented.
 */
class MetroReportsChecker(testServices: TestServices) : AfterAnalysisChecker(testServices) {
  companion object {
    const val DEFAULT_REPORTS_DIR = "metro/reports"
    const val DEFAULT_TRACES_DIR = "metro/traces"
    private val TRACE_FILENAME_PATTERN = Regex("""^(\d{6}-\d{6})-(fir|ir)-(.+)\.perfetto-trace$""")
  }

  override val directiveContainers: List<DirectivesContainer>
    get() = listOf(MetroDirectives)

  override fun check(failedAssertions: List<WrappedException>) {
    if (failedAssertions.isNotEmpty()) return

    val allDirectives = testServices.moduleStructure.allDirectives

    checkReports(allDirectives)
    if (MetroDirectives.CHECK_TRACES in allDirectives) {
      checkTraces(allDirectives)
    }
  }

  private fun checkReports(allDirectives: RegisteredDirectives) {
    // Get the list of report names to check
    val reportNamesToCheck = allDirectives[MetroDirectives.CHECK_REPORTS]
    if (reportNamesToCheck.isEmpty()) return

    // Get the reports destination from directives, or use default
    val reportsDestination =
      allDirectives.singleOrZeroValue(MetroDirectives.REPORTS_DESTINATION) ?: DEFAULT_REPORTS_DIR

    val reportsDir =
      File(testServices.temporaryDirectoryManager.rootDir.absolutePath, reportsDestination)

    val testDataFile = testServices.moduleStructure.originalTestDataFiles.first()

    var generatedMissingFiles = false
    var lastError: AssertionFailedError? = null
    for (reportName in reportNamesToCheck) {
      val baseFileName = "$reportName.txt"
      val reportFile = File(reportsDir, baseFileName)
      val expectedFile = File(testDataFile.withoutExtension(), baseFileName)

      if (!reportFile.exists()) {
        testServices.assertions.fail {
          "Expected report '$reportName' was not generated. " +
            "Report file not found: ${reportFile.absolutePath}"
        }
      } else {
        val actualContent = reportFile.readText()
        try {
          testServices.assertions.assertEqualsToFile(expectedFile, actualContent)
        } catch (e: AssertionFailedError) {
          if (e.message?.contains("Generating: ") == true) {
            // Don't fail eagerly
            generatedMissingFiles = true
            lastError = e
            System.err.println(e.message)
          } else {
            throw e
          }
        }
      }
    }
    if (generatedMissingFiles) {
      throw lastError!!
    }
  }

  private fun checkTraces(allDirectives: RegisteredDirectives) {
    val destination =
      allDirectives.singleOrZeroValue(MetroDirectives.TRACE_DESTINATION) ?: DEFAULT_TRACES_DIR
    val tracesDir = File(testServices.temporaryDirectoryManager.rootDir.absolutePath, destination)
    if (!tracesDir.isDirectory) {
      testServices.assertions.fail {
        "Expected trace directory was not created: ${tracesDir.absolutePath}"
      }
    }

    val files = tracesDir.listFiles().orEmpty().filter { it.extension == "perfetto-trace" }
    if (files.isEmpty()) {
      testServices.assertions.fail {
        "No .perfetto-trace files were produced in ${tracesDir.absolutePath}"
      }
    }

    val parsed = files.map { file ->
      val match =
        TRACE_FILENAME_PATTERN.matchEntire(file.name)
          ?: testServices.assertions.fail {
            "Trace filename does not match `<id>-(fir|ir)-<moduleName>.perfetto-trace`: ${file.name}"
          }
      Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3])
    }

    val ids = parsed.map { it.first }.toSet()
    if (ids.size != 1) {
      testServices.assertions.fail {
        "All trace files from one compilation must share an id, but found ${ids.sorted()}"
      }
    }

    val phases = parsed.map { it.second }.toSet()
    if ("fir" !in phases) {
      testServices.assertions.fail {
        "Expected at least one FIR trace file, got ${files.map { it.name }}"
      }
    }
    if ("ir" !in phases) {
      testServices.assertions.fail {
        "Expected at least one IR trace file, got ${files.map { it.name }}"
      }
    }
  }

  private fun File.withoutExtension(): File {
    return parentFile.resolve(nameWithoutExtension)
  }
}
