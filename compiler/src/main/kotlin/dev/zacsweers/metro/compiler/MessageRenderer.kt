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

  class MessageBuilder(private val renderer: MessageRenderer) : Appendable {
    private val sb = StringBuilder()

    fun bold(text: String): String = renderer.bold(text)

    fun italic(text: String): String = renderer.italic(text)

    fun red(text: String): String = renderer.red(text)

    fun green(text: String): String = renderer.green(text)

    fun yellow(text: String): String = renderer.yellow(text)

    fun underline(text: String): String = renderer.underline(text)

    fun curlyUnderline(text: String): String = renderer.curlyUnderline(text)

    fun strikethrough(text: String): String = renderer.strikethrough(text)

    fun dim(text: String): String = renderer.dim(text)

    fun code(text: String): String = renderer.code(text)

    fun codeBlock(text: String, indent: String = "    "): String = renderer.codeBlock(text, indent)

    fun append(text: String) = apply { sb.append(text) }

    override fun append(csq: CharSequence?): MessageBuilder = apply { sb.append(csq) }

    override fun append(csq: CharSequence?, start: Int, end: Int): MessageBuilder = apply {
      sb.append(csq, start, end)
    }

    override fun append(c: Char): MessageBuilder = apply { sb.append(c) }

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

    fun appendLineWithUnderlinedContent(
      content: String,
      target: String = content,
      char: Char = '~',
    ) = apply {
      sb.appendLine(content)
      val lines = sb.lines()
      val index = lines[lines.lastIndex - 1].lastIndexOf(target)
      if (index == -1) return@apply
      repeat(index) { sb.append(' ') }
      repeat(target.length) { sb.append(char) }
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

    private val RICH_OUTPUT_SYSPROP: Boolean? =
      System.getProperty("metro.richDiagnostics")?.toBoolean()

    val RICH_OUTPUT_ENABLED: Boolean = RICH_OUTPUT_SYSPROP ?: true

    /**
     * Resolves whether rich output is enabled, with the system property taking priority over the
     * compiler option.
     */
    fun resolveRichOutput(optionValue: Boolean): Boolean = RICH_OUTPUT_SYSPROP ?: optionValue
  }
}
