// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

/**
 * Renders diagnostic messages, optionally with optional rich formatting for terminal output.
 *
 * This class provides a DSL-like API for building diagnostic messages where key parts (declaration
 * names, types, qualifiers, etc.) can be emphasized. When [richOutput] is enabled, emphasis is
 * rendered using ANSI bold codes. When disabled, the text is passed through as-is.
 *
 * Future:
 * - support smart type rendering (e.g., only fully-qualifying types when there are conflicting
 *   simple names)
 */
internal class MessageRenderer(val richOutput: Boolean = RICH_OUTPUT_ENABLED) {

  fun bold(text: String): String = wrap(text, ANSI_BOLD)

  fun italic(text: String): String = wrap(text, ANSI_ITALIC)

  fun red(text: String): String = wrap(text, ANSI_RED)

  fun green(text: String): String = wrap(text, ANSI_GREEN)

  fun yellow(text: String): String = wrap(text, ANSI_YELLOW)

  fun underline(text: String): String = wrap(text, ANSI_UNDERLINE)

  /**
   * Curly/squiggly underline. Supported by modern terminals (kitty, iTerm2, WezTerm). Falls back to
   * regular underline in terminals that don't support it.
   */
  fun curlyUnderline(text: String): String = wrap(text, ANSI_CURLY_UNDERLINE)

  fun strikethrough(text: String): String = wrap(text, ANSI_STRIKETHROUGH)

  fun dim(text: String): String = wrap(text, ANSI_DIM)

  /** Wraps [text] as inline code using backticks. No ANSI styling — renders the same either way. */
  fun code(text: String): String = "`$text`"

  /**
   * Renders a code block. In rich mode, the content is dimmed. In plain mode, it's just indented.
   * Each line of [text] is indented by [indent].
   */
  fun codeBlock(text: String, indent: String = "    "): String {
    val indented = text.lines().joinToString("\n") { "$indent$it" }
    return if (richOutput) "$ANSI_DIM$indented$ANSI_RESET" else indented
  }

  private fun wrap(text: String, ansiCode: String): String =
    if (richOutput) "$ansiCode$text$ANSI_RESET" else text

  /** Builds a message string using the [MessageBuilder] DSL. */
  inline fun buildMessage(block: MessageBuilder.() -> Unit): String {
    return MessageBuilder(this).apply(block).toString()
  }

  class MessageBuilder(private val renderer: MessageRenderer) {
    private val sb = StringBuilder()

    fun append(text: String) = apply { sb.append(text) }

    fun appendLine(text: String = "") = apply { sb.appendLine(text) }

    fun appendBold(text: String) = apply { sb.append(renderer.bold(text)) }

    fun appendItalic(text: String) = apply { sb.append(renderer.italic(text)) }

    fun appendRed(text: String) = apply { sb.append(renderer.red(text)) }

    fun appendGreen(text: String) = apply { sb.append(renderer.green(text)) }

    fun appendYellow(text: String) = apply { sb.append(renderer.yellow(text)) }

    fun appendUnderline(text: String) = apply { sb.append(renderer.underline(text)) }

    fun appendCurlyUnderline(text: String) = apply { sb.append(renderer.curlyUnderline(text)) }

    fun appendDim(text: String) = apply { sb.append(renderer.dim(text)) }

    fun appendStrikethrough(text: String) = apply { sb.append(renderer.strikethrough(text)) }

    fun appendCode(text: String) = apply { sb.append(renderer.code(text)) }

    fun appendCodeBlock(text: String, indent: String = "    ") = apply {
      sb.append(renderer.codeBlock(text, indent))
    }

    override fun toString(): String = sb.toString()
  }

  companion object {
    internal const val ANSI_BOLD = "\u001B[1m"
    internal const val ANSI_ITALIC = "\u001B[3m"
    internal const val ANSI_UNDERLINE = "\u001B[4m"
    internal const val ANSI_CURLY_UNDERLINE = "\u001B[4:3m"
    internal const val ANSI_STRIKETHROUGH = "\u001B[9m"
    internal const val ANSI_DIM = "\u001B[2m"
    internal const val ANSI_RED = "\u001B[31m"
    internal const val ANSI_GREEN = "\u001B[32m"
    internal const val ANSI_YELLOW = "\u001B[33m"
    internal const val ANSI_RESET = "\u001B[0m"

    /** Regex to strip ANSI escape codes from a string. */
    val ANSI_PATTERN = Regex("\u001B\\[[;:\\d]*m")

    /** Strips all ANSI escape codes from [text]. */
    fun stripAnsi(text: String): String = text.replace(ANSI_PATTERN, "")

    val RICH_OUTPUT_ENABLED: Boolean =
      System.getProperty("metro.richDiagnostics", "true").toBoolean()
  }
}
