// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

/**
 * Maps ANSI SGR escape codes to readable tags for golden files. Rich diagnostic snapshots are
 * asserted against `.rich.ir.diag.txt` files; raw escape bytes would make those unreadable and
 * editor-hostile, so they're escaped to `<b>`/`<dim>`/`</>`-style markers. Unknown escape sequences
 * become `<esc:...>` rather than being silently dropped.
 */
object AnsiMarkup {
  private val KNOWN =
    mapOf(
      "\u001B[1m" to "<b>",
      "\u001B[2m" to "<dim>",
      "\u001B[3m" to "<i>",
      "\u001B[4m" to "<u>",
      "\u001B[4:3m" to "<curly>",
      "\u001B[9m" to "<strike>",
      "\u001B[31m" to "<red>",
      "\u001B[32m" to "<green>",
      "\u001B[33m" to "<yellow>",
      "\u001B[0m" to "</>",
    )

  private val ANSI_PATTERN = Regex("\u001B\\[[;:\\d]*[a-zA-Z]")

  fun escape(text: String): String =
    ANSI_PATTERN.replace(text) { match ->
      KNOWN[match.value] ?: "<esc:${match.value.drop(1)}>"
    }
}
