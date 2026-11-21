// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.directives.model.RegisteredDirectives
import org.jetbrains.kotlin.test.directives.model.singleOrZeroValue
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager
import org.opentest4j.AssertionFailedError

/** Give each module its own destination so later compilations preserve earlier reports. */
internal fun TestServices.reportDirectory(module: TestModule): File {
  val destination =
    module.directives.singleOrZeroValue(MetroDirectives.REPORTS_DESTINATION)
      ?: MetroReportsChecker.DEFAULT_REPORTS_DIR
  val root = temporaryDirectoryManager.rootDir
  if (moduleStructure.modules.size == 1) {
    return File(root, destination)
  }

  return File(root.resolve(module.name), destination)
}

/**
 * Compares each distinct [MetroDirectives.CHECK_REPORTS] path with its golden. The last module
 * requesting a path owns the check. Global checks apply to the final module. To target an earlier
 * module, place its check between the `MODULE` and `FILE` directives.
 *
 * Expected reports live under `_reports/<fixture-name>/` next to the fixture. Append `.txt` to each
 * requested path, including paths that already have an extension.
 *
 * [MetroDirectives.CHECK_TRACES] checks that [MetroDirectives.TRACE_DESTINATION] (default
 * [DEFAULT_TRACES_DIR]) contains both FIR and IR traces. Their names follow
 * `<id>-(fir|ir)-<moduleName>.perfetto-trace` and share the compilation ID.
 */
class MetroReportsChecker(testServices: TestServices) :
  MetroAfterAnalysisCheckerCompat(testServices) {
  companion object {
    const val DEFAULT_REPORTS_DIR = "metro/reports"
    const val DEFAULT_TRACES_DIR = "metro/traces"
    private val TRACE_FILENAME_PATTERN = Regex("""^(\d{6}-\d{6})-(fir|ir)-(.+)\.perfetto-trace$""")
    private val REPORT_JSON = Json {
      prettyPrint = true
      @OptIn(ExperimentalSerializationApi::class)
      prettyPrintIndent = "  "
    }
  }

  override val directiveContainers: List<DirectivesContainer>
    get() = listOf(MetroDirectives)

  override fun checkAfterAnalysis(thereWereFailures: Boolean) {
    val allDirectives = testServices.moduleStructure.allDirectives

    val reportNames = allDirectives[MetroDirectives.CHECK_REPORTS]
    if (reportNames.isNotEmpty()) {
      checkReports(reportNames.distinct())
    }

    if (thereWereFailures) {
      return
    }

    if (MetroDirectives.CHECK_TRACES in allDirectives) {
      checkTraces(allDirectives)
    }
  }

  private fun checkReports(reportNames: List<String>) {
    val testDataFile = testServices.moduleStructure.originalTestDataFiles.first()
    val expectedRoot =
      testDataFile.resolveSibling("_reports").resolve(testDataFile.nameWithoutExtension)

    var lastError: AssertionFailedError? = null
    for (reportName in reportNames) {
      val actualContent = readReport(reportName)
      val expectedFile = File(expectedRoot, "$reportName.txt")
      try {
        testServices.assertions.assertEqualsToFile(expectedFile, actualContent)
      } catch (e: AssertionFailedError) {
        if (e.message?.contains("Generating: ") != true) {
          throw e
        }

        // Let the framework generate all missing goldens before failing.
        lastError = e
        System.err.println(e.message)
      }
    }

    if (lastError != null) {
      throw lastError
    }
  }

  /** Each module inherits the global checks, so the final module owns those paths. */
  private fun readReport(reportName: String): String {
    val module =
      testServices.moduleStructure.modules.last {
        reportName in it.directives[MetroDirectives.CHECK_REPORTS]
      }
    val fileName =
      if (File(reportName).extension.isEmpty()) {
        "$reportName.txt"
      } else {
        reportName
      }
    val file =
      testServices.reportDirectory(module).resolve(COMPILER_REPORTS_DIRECTORY).resolve(fileName)
    if (!file.isFile) {
      testServices.assertions.fail {
        "Expected report '$reportName' from module '${module.name}' at ${file.absolutePath}"
      }
    }

    val content = file.readText()
    val normalize = MetroDirectives.NORMALIZE_REPORT_SOURCE_LOCATIONS in module.directives
    if (normalize && reportName.endsWith(".json")) {
      return normalizeReportSourceLocations(content)
    }

    return content
  }

  private fun normalizeReportSourceLocations(content: String): String {
    val report = REPORT_JSON.parseToJsonElement(content).withoutSourceLocations()
    return REPORT_JSON.encodeToString(JsonElement.serializer(), report)
  }

  private fun JsonElement.withoutSourceLocations(): JsonElement =
    when (this) {
      is JsonObject ->
        JsonObject(
          filterKeys { it != "origin" && it != "source" }
            .mapValues { (_, value) -> value.withoutSourceLocations() },
        )
      is JsonArray -> JsonArray(map { it.withoutSourceLocations() })
      else -> this
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
}
