// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics.render

import dev.zacsweers.metro.compiler.diagnostics.Style

/** A contiguous piece of laid-out text with a single style. */
internal data class Run(val text: String, val style: Style = Style.NONE)

/**
 * Accumulates rendered output with word-wrapping to a fixed column budget.
 *
 * Width is calculated before styling so ANSI escape codes never affect layout. Tokens are never
 * split; long identifiers overflow rather than being truncated.
 */
internal class LineWriter(private val styler: Styler, private val width: Int) {
  private val sb = StringBuilder()
  private var lastLineBlank = true

  fun blankLine() {
    if (!lastLineBlank) {
      sb.append('\n')
      lastLineBlank = true
    }
  }

  /**
   * Emits [runs] as one logical line, word-wrapped at spaces. Continuation lines are indented by
   * [hangingIndent].
   */
  fun line(runs: List<Run>, indent: Int, hangingIndent: Int = indent) {
    lineSegments(tokenize(runs), indent, hangingIndent)
  }

  /**
   * Emits atomic [segments] as one logical line. Wrapping may occur between segments, but never
   * inside one.
   */
  fun lineSegments(segments: List<List<Run>>, indent: Int, hangingIndent: Int = indent) {
    if (segments.isEmpty()) return
    var lineRuns = mutableListOf<Run>()
    var lineWidth = indent
    var currentIndent = indent
    fun flush() {
      if (lineRuns.isEmpty()) return
      emitLine(currentIndent, lineRuns)
      lineRuns = mutableListOf()
      currentIndent = hangingIndent
      lineWidth = hangingIndent
    }
    for (segment in segments) {
      val segmentWidth = segment.sumOf { it.text.length }
      val separator = if (lineRuns.isEmpty()) 0 else 1
      if (lineRuns.isNotEmpty() && lineWidth + separator + segmentWidth > width) {
        flush()
      }
      if (lineRuns.isNotEmpty()) {
        lineRuns += Run(" ")
        lineWidth += 1
      }
      lineRuns += segment
      lineWidth += segmentWidth
    }
    flush()
  }

  /** Emits [text] verbatim on its own line. No wrapping; the whole line uses [style]. */
  fun raw(text: String, indent: Int, style: Style = Style.NONE) {
    emitLine(indent, listOf(Run(text, style)))
  }

  /** Emits [runs] verbatim on one line. Used for column-exact source frames. */
  fun rawRuns(runs: List<Run>, indent: Int) {
    emitLine(indent, runs)
  }

  private fun emitLine(indent: Int, runs: List<Run>) {
    repeat(indent) { sb.append(' ') }
    for (run in runs) {
      if (run.text.isEmpty()) continue
      sb.append(styler.apply(run.style, run.text))
    }
    sb.append('\n')
    lastLineBlank = false
  }

  /** Splits runs into unbreakable word tokens. Words may span run/style boundaries. */
  private fun tokenize(runs: List<Run>): List<List<Run>> {
    val tokens = mutableListOf<List<Run>>()
    var current = mutableListOf<Run>()
    fun endToken() {
      if (current.isNotEmpty()) {
        tokens += current
        current = mutableListOf()
      }
    }
    for (run in runs) {
      var start = 0
      while (start < run.text.length) {
        val spaceIndex = run.text.indexOf(' ', start)
        if (spaceIndex == -1) {
          current += Run(run.text.substring(start), run.style)
          break
        }
        if (spaceIndex > start) {
          current += Run(run.text.substring(start, spaceIndex), run.style)
        }
        endToken()
        start = spaceIndex + 1
      }
    }
    endToken()
    return tokens
  }

  fun build(): String = sb.toString().trimEnd('\n')
}
