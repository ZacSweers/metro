// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.ide

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.sdk.DeclarativeInlayRenderer
import com.intellij.driver.sdk.FileEditorManager
import com.intellij.driver.sdk.Inlay
import com.intellij.driver.sdk.WaitForException
import com.intellij.driver.sdk.getHighlights
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.community.model.BuildType
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.report.ErrorReporterToCI
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Extended InlayModel that includes block element access (not in our SDK version). */
@Remote("com.intellij.openapi.editor.InlayModel")
private interface InlayModelWithBlocks {
  fun getInlineElementsInRange(startOffset: Int, endOffset: Int): List<Inlay>

  fun getBlockElementsInRange(startOffset: Int, endOffset: Int): List<Inlay>
}

/**
 * Patterns that indicate an error is Metro-related and should be reported. We filter IN Metro
 * errors rather than trying to filter OUT all unrelated IDE errors.
 */
private val METRO_ERROR_PATTERNS = listOf("metro", "dev.zacsweers.metro", "Metro")

private data class ExpectedDiagnostic(
  val diagnosticId: String,
  val severity: String,
  val description: String,
  /** 0-indexed line in the source where the diagnostic is expected (the line after the comment). */
  val expectedLine: Int,
)

private data class ExpectedInlay(
  val text: String,
  /** 0-indexed line in the source where the inlay is expected (the line after the comment). */
  val expectedLine: Int,
)

/** Builds a lookup from character offset to 0-indexed line number. */
private fun buildLineOffsets(sourceText: String): List<Int> {
  val lineStarts = mutableListOf(0)
  sourceText.forEachIndexed { i, c -> if (c == '\n') lineStarts.add(i + 1) }
  return lineStarts
}

private fun offsetToLine(lineStarts: List<Int>, offset: Int): Int {
  val idx = lineStarts.binarySearch(offset)
  return if (idx >= 0) idx else -(idx + 1) - 1
}

/** Parses `// METRO_DIAGNOSTIC: DIAGNOSTIC_ID,SEVERITY,description` comments from a source file. */
private fun parseExpectedDiagnostics(sourceText: String): List<ExpectedDiagnostic> {
  return sourceText.lines().mapIndexedNotNull { index, line ->
    val match = line.trim().removePrefix("// METRO_DIAGNOSTIC: ").takeIf { it != line.trim() }
    match?.split(",", limit = 3)?.let { parts ->
      require(parts.size == 3) { "METRO_DIAGNOSTIC must have 3 comma-separated fields: $line" }
      ExpectedDiagnostic(
        diagnosticId = parts[0].trim(),
        severity = parts[1].trim(),
        description = parts[2].trim(),
        expectedLine = index + 1,
      )
    }
  }
}

/** Parses `// METRO_INLAY: substring` comments from a source file. */
private fun parseExpectedInlays(sourceText: String): List<ExpectedInlay> {
  return sourceText.lines().mapIndexedNotNull { index, line ->
    val text = line.trim().removePrefix("// METRO_INLAY: ").takeIf { it != line.trim() }?.trim()
    text?.let { ExpectedInlay(text = it, expectedLine = index + 1) }
  }
}

@Suppress("NewApi") // idk why lint is running here
class MetroIdeSmokeTest {

  companion object {
    @JvmStatic
    fun ideVersions(): List<Arguments> =
      Path.of(System.getProperty("metro.ideVersions"))
        .readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
          val parts = line.split(":")
          Arguments.of(parts[0], parts[1])
        }
  }

  @ParameterizedTest
  @MethodSource("ideVersions")
  fun check(product: String, version: String) {
    // IU uses marketing version (e.g., "2025.3.2") with RELEASE buildType
    // AS uses build number (e.g., "2024.2.1.11") directly
    // NOTE: Run ./download-ides.sh first
    val ideProduct =
      when (product) {
        "IU" -> IdeProductProvider.IU.copy(version = version, buildType = BuildType.RELEASE.type)
        "AS" -> IdeProductProvider.AI.copy(buildNumber = version)
        else -> error("Unknown product: $product")
      }

    val testProject = Path.of(System.getProperty("metro.testProject")).toAbsolutePath()

    // Parse expected diagnostics and inlays from the test source file
    val sourceText = testProject.resolve("src/main/kotlin/TestSources.kt").readText()
    val expectedDiagnostics = parseExpectedDiagnostics(sourceText)
    val expectedInlays = parseExpectedInlays(sourceText)

    val testCase = TestCase(ideProduct, LocalProjectInfo(testProject))

    val testContext =
      Starter.newContext(CurrentTestMethod.hyphenateWithClass(), testCase)
        .prepareProjectCleanImport()
        .addProjectToTrustedLocations()
        .applyVMOptionsPatch {
          // Enable third-party compiler plugins (like Metro) in the Kotlin IDE plugin's FIR
          // analysis.
          // RegistryValue falls back to system properties, so this works for Registry.get() calls.
          addSystemProperty("kotlin.k2.only.bundled.compiler.plugins.enabled", false)

          // Disable VCS integration to avoid git popups for IDE-generated files
          addSystemProperty("vcs.log.index.git", false)
          addSystemProperty("git.showDialogsOnUnversionedFiles", false)

          // Suppress Android Studio consent/data-sharing dialog that blocks on CI
          addSystemProperty("jb.consents.confirmation.enabled", false)
          addSystemProperty("idea.initially.ask.config", "never")
        }

    // Collect highlights and inlays inside the driver block, assert after IDE closes.
    // This lets us check the IDE logs first for a more useful error message.
    data class HighlightData(
      val severity: String,
      val description: String?,
      val highlightedText: String?,
    )
    data class InlayData(val offset: Int, val text: String?, val isBlock: Boolean)

    val collectedHighlights = mutableListOf<HighlightData>()
    val collectedInlays = mutableListOf<InlayData>()
    var analysisException: WaitForException? = null

    val result =
      testContext.runIdeWithDriver().useDriverAndCloseIde {
        // Wait for Gradle import + indexing to complete.
        // waitSmartLongEnough (default=true) requires 10 consecutive seconds with no indicators
        // before returning, which is enough to catch the brief gap before Gradle import starts.
        waitForIndicators(10.minutes)

        // Open the test source file and wait for code analysis to complete.
        // This triggers FIR analysis with Metro extensions.
        try {
          openFile("src/main/kotlin/TestSources.kt", singleProject())
        } catch (e: WaitForException) {
          analysisException = e
          return@useDriverAndCloseIde
        }

        val project = singleProject()
        val editor = service<FileEditorManager>(project).getSelectedTextEditor()
        checkNotNull(editor) { "No editor open after opening TestSources.kt" }

        val document = editor.getDocument()

        // Collect highlights
        val highlights = getHighlights(document, project)
        collectedHighlights +=
          highlights.map {
            HighlightData(it.getSeverity().getName(), it.getDescription(), it.getText())
          }

        // Collect inlays (both inline and block)
        val inlayModel = cast(editor.getInlayModel(), InlayModelWithBlocks::class)
        val docLength = document.getText().length
        fun collectInlays(inlays: List<Inlay>, isBlock: Boolean) =
          inlays.map { inlay ->
            val text =
              if (isBlock) {
                // We can't really get much out of these
                inlay.getRenderer().toString()
              } else {
                cast(inlay.getRenderer(), DeclarativeInlayRenderer::class)
                  .getPresentationList()
                  .getEntries()
                  .joinToString("") { it.getText() }
              }
            InlayData(offset = inlay.getOffset(), text = text, isBlock = isBlock)
          }
        val inlineInlays =
          collectInlays(inlayModel.getInlineElementsInRange(0, docLength), isBlock = false)
        val blockInlays =
          collectInlays(inlayModel.getBlockElementsInRange(0, docLength), isBlock = true)
        collectedInlays += inlineInlays + blockInlays
      }

    // Check for IDE runtime failure (e.g. process crash)
    result.failureError?.let { fail("IDE run failed", it) }

    val logsDir = result.runContext.logsDir

    // If openFile timed out, analysis likely crashed. Check logs for Metro FIR errors.
    if (analysisException != null) {
      val metroErrors = collectMetroErrors(logsDir)
      fail(
        buildString {
          appendLine("Analysis timed out (WaitForException), suggesting a FIR crash.")
          if (metroErrors.isNotEmpty()) {
            appendLine("Metro-related errors found:")
            metroErrors.forEach { e ->
              appendLine("  ${e.messageText}")
              appendLine("  ${e.stackTraceContent}")
            }
          } else {
            // No structured errors found — fall back to scanning idea.log directly
            val logCrashes = findMetroLogCrashes(logsDir)
            if (logCrashes != null) {
              appendLine("Metro-related crashes found in idea.log:")
              appendLine(logCrashes)
            } else {
              appendLine("No Metro-related errors found in logs.")
            }
          }
        },
        analysisException,
      )
    }

    // Verify Metro extensions were actually loaded by checking idea.log.
    // Check this before highlights — if extensions weren't enabled, highlights will be empty.
    val ideaLogLines = logsDir.resolve("idea.log").readLines()
    val skipPattern = "Skipping enabling Metro extensions"
    val skipIndex = ideaLogLines.indexOfFirst { it.contains(skipPattern) }
    if (skipIndex != -1) {
      val context = ideaLogLines.subList(skipIndex, minOf(skipIndex + 6, ideaLogLines.size))
      fail("Metro extensions were not enabled!\n" + context.joinToString("\n"))
    }

    // Verify expected diagnostics are present at the correct location
    val errors = mutableListOf<String>()
    val sourceLines = sourceText.lines()
    val lineStarts = buildLineOffsets(sourceText)

    // Match a highlight to an expected diagnostic ID. The [ID] bracket format isn't always
    // present (depends on IDE version), so also match the ID without brackets.
    fun highlightMatchesId(description: String?, id: String): Boolean {
      if (description == null) return false
      return description.contains("[$id]") || description.contains(id)
    }

    for (expected in expectedDiagnostics) {
      // Check a window of lines after the comment since the diagnostic may be a few lines down
      val nearbyLines =
        (expected.expectedLine..minOf(expected.expectedLine + 5, sourceLines.lastIndex))
          .joinToString("\n") { sourceLines[it] }
      val found =
        collectedHighlights.any { h ->
          h.severity == expected.severity &&
            highlightMatchesId(h.description, expected.diagnosticId) &&
            (h.highlightedText == null || nearbyLines.contains(h.highlightedText))
        }
      if (!found) {
        errors +=
          "Missing expected ${expected.severity} [${expected.diagnosticId}] near line ${expected.expectedLine + 1}: ${expected.description}"
      }
    }

    // Check for unexpected ERROR diagnostics (e.g., UNRESOLVED_REFERENCE)
    val expectedErrorIds =
      expectedDiagnostics.filter { it.severity == "ERROR" }.map { it.diagnosticId }.toSet()
    val unexpectedErrors =
      collectedHighlights.filter { h ->
        h.severity == "ERROR" &&
          expectedErrorIds.none { id -> highlightMatchesId(h.description, id) }
      }
    for (unexpected in unexpectedErrors) {
      errors += "Unexpected ERROR: ${unexpected.description}"
    }

    // Verify expected inlays are present at the correct location
    for (expected in expectedInlays) {
      val found =
        collectedInlays.any { inlay ->
          val inlayLine = offsetToLine(lineStarts, inlay.offset)
          val lineMatch = inlayLine in expected.expectedLine..(expected.expectedLine + 10)
          val textMatch = inlay.text?.contains(expected.text) == true
          lineMatch && textMatch
        }
      if (!found) {
        errors +=
          "Missing expected inlay containing '${expected.text}' near line ${expected.expectedLine + 1}"
      }
    }

    // TODO Assert on companion object inlay once we have a test case for it

    if (errors.isNotEmpty()) {
      val allHighlightsSummary =
        collectedHighlights
          .filter { it.description != null }
          .joinToString("\n") {
            "  [${it.severity}] ${it.description} (text='${it.highlightedText}')"
          }
      val allInlaySummary =
        collectedInlays.joinToString("\n") {
          val line = offsetToLine(lineStarts, it.offset) + 1
          "  [${if (it.isBlock) "block" else "inline"} line $line] ${it.text ?: "(no text)"}"
        }
      fail(
        "Smoke test failures:\n" +
          errors.joinToString("\n") { "  - $it" } +
          "\n\nAll highlights with descriptions:\n$allHighlightsSummary" +
          "\n\nAll inlays:\n$allInlaySummary"
      )
    }

    // Scan IDE logs for internal errors collected by the performance testing plugin.
    // These are exceptions caught by the IDE's MessageBus and written to an errors/ directory.
    // We only report Metro-related errors — other IDE errors are ignored.
    val metroErrors = collectMetroErrors(logsDir)

    if (metroErrors.isNotEmpty()) {
      fail(
        "Metro caused ${metroErrors.size} internal error(s) during analysis:\n" +
          metroErrors.joinToString("\n---\n") { e -> "${e.messageText}\n${e.stackTraceContent}" }
      )
    }
  }
}

/** Check if an error is Metro-related based on message or stack trace. */
private fun isMetroRelatedError(messageText: String, stackTrace: String): Boolean {
  return METRO_ERROR_PATTERNS.any { pattern ->
    messageText.contains(pattern, ignoreCase = true) ||
      stackTrace.contains(pattern, ignoreCase = true)
  }
}

/** Collect Metro-related errors from the IDE's error reporter output. */
private fun collectMetroErrors(logsDir: Path) =
  ErrorReporterToCI.collectErrors(logsDir).filter { error ->
    isMetroRelatedError(error.messageText, error.stackTraceContent)
  }

/**
 * Scan idea.log for Metro-related crash stack traces. Returns context lines around each occurrence
 * of `dev.zacsweers.metro` in the log, capturing the exception cause.
 */
private fun findMetroLogCrashes(logsDir: Path): String? {
  val lines =
    try {
      logsDir.resolve("idea.log").readLines()
    } catch (_: Exception) {
      return null
    }

  val metroIndices = lines.indices.filter { i -> lines[i].contains("dev.zacsweers.metro") }

  if (metroIndices.isEmpty()) return null

  // Collect context windows around each Metro mention, merging overlaps
  val contextLines =
    buildSet {
        for (idx in metroIndices) {
          for (i in maxOf(0, idx - 5)..minOf(lines.lastIndex, idx + 2)) {
            add(i)
          }
        }
      }
      .sorted()

  return contextLines.joinToString("\n") { lines[it] }
}
