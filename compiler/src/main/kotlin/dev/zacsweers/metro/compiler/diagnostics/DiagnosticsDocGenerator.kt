// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Generates docs/diagnostics.md from the [MetroDiagnosticId] registry. Section headings use each
 * ID's simple name so mkdocs produces anchors matching [MetroDiagnosticId.anchor] and
 * [MetroDiagnosticId.docsUrl].
 *
 * Usage: `DiagnosticsDocGenerator <path-to-diagnostics.md> [--check]`. With `--check`, exits
 * non-zero if the committed file is out of date instead of writing.
 */
internal object DiagnosticsDocGenerator {

  fun generate(): String = buildString {
    val diagnosticIds = MetroDiagnosticId.entries.sortedBy { it.fullId.substringAfter('/') }

    appendLine("# Diagnostics Reference")
    appendLine()
    appendLine("<!-- Generated from MetroDiagnosticId by DiagnosticsDocGenerator. -->")
    appendLine("<!-- Run `./gradlew :compiler:generateDiagnosticsDocs` to update. -->")
    appendLine()
    appendLine(
      "Reference for Metro's common graph-validation diagnostics: the messages reported with " +
        "`[Metro/...]` IDs while validating dependency graphs."
    )
    appendLine()
    appendLine(
      "This is not an exhaustive list of everything Metro reports. Many finer-grained declaration " +
        "checks, such as annotation misuse and visibility errors, are reported directly in the " +
        "frontend or IDE without an ID."
    )
    appendLine()
    appendLine(
      "Diagnostics rendering is controlled by the `diagnosticsRenderMode` Gradle option: `AUTO`, " +
        "`PLAIN`, or `RICH`."
    )
    appendLine()
    appendLine("| Diagnostic | Summary |")
    appendLine("|------------|---------|")
    for (id in diagnosticIds) {
      appendLine("| [`${id.fullId}`](#${id.anchor}) | ${id.brief} |")
    }
    for (id in diagnosticIds) {
      appendLine()
      appendLine("## ${id.fullId.substringAfter('/')}")
      appendLine()
      appendLine("**Diagnostic:** `${id.fullId}`")
      appendLine()
      appendLine("**Summary:** ${id.brief}")
      appendLine()
      val explanation =
        id.explanation.trimIndent().trim().split("\n\n").joinToString("\n\n") { paragraph ->
          paragraph.lines().joinToString(" ") { it.trim() }
        }
      appendLine(explanation)
    }
  }

  @JvmStatic
  fun main(args: Array<String>) {
    val target = Path.of(args.first())
    val check = args.contains("--check")
    val content = generate()
    if (check) {
      val current = if (target.exists()) target.readText() else ""
      if (current != content) {
        System.err.println(
          "$target is out of date. Run ./gradlew :compiler:generateDiagnosticsDocs to regenerate."
        )
        exitProcess(1)
      }
    } else {
      target.writeText(content)
      println("Wrote $target")
    }
  }
}
