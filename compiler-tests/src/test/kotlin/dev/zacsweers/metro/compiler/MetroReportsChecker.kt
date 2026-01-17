// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.io.File
import org.jetbrains.kotlin.test.WrappedException
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.directives.model.singleOrZeroValue
import org.jetbrains.kotlin.test.model.AfterAnalysisChecker
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager
import org.jetbrains.kotlin.test.utils.withExtension
import org.opentest4j.AssertionFailedError

/**
 * AfterAnalysisChecker that verifies Metro report outputs against expected files.
 *
 * When [MetroDirectives.REPORTS_DESTINATION] is configured and [MetroDirectives.CHECK_REPORTS]
 * specifies report names, this checker will:
 * 1. Find the specified report files in the reports destination
 * 2. Compare each report file against an expected file alongside the test data
 *
 * Expected files should be named: `<testFile>.<reportName>.txt`
 *
 * Example usage:
 * ```
 * // REPORTS_DESTINATION: metro/reports
 * // CHECK_REPORTS: merging-unmatched-exclusions-fir-test.AppGraph
 * // CHECK_REPORTS: merging-unmatched-replacements-ir-test.AppGraph
 * ```
 *
 * This will look for report files named `merging-unmatched-exclusions-fir-test.AppGraph.txt` and
 * `merging-unmatched-replacements-ir-test.AppGraph.txt` in the reports directory, and compare them
 * against `<testFile>.merging-unmatched-exclusions-fir-test.AppGraph.txt` and
 * `<testFile>.merging-unmatched-replacements-ir-test.AppGraph.txt` respectively.
 */
class MetroReportsChecker(testServices: TestServices) : AfterAnalysisChecker(testServices) {
  companion object {
    const val DEFAULT_REPORTS_DIR = "metro/reports"
  }

  override val directiveContainers: List<DirectivesContainer>
    get() = listOf(MetroDirectives)

  override fun check(failedAssertions: List<WrappedException>) {
    if (failedAssertions.isNotEmpty()) return

    val allDirectives = testServices.moduleStructure.allDirectives

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
      val reportFile = File(reportsDir, "$reportName.txt")
      val expectedFile = testDataFile.withExtension("$reportName.txt")

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
}
