// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics.render

import dev.zacsweers.metro.compiler.ConsoleMode
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.diagnostics.Style

/**
 * Resolves the effective console mode.
 *
 * The `metro.diagnosticsConsole` system property overrides the compiler option. [ConsoleMode.AUTO]
 * is resolved by the Gradle plugin before compiler invocation; if it reaches the compiler anyway,
 * use plain output.
 */
internal fun MetroOptions.resolveConsoleMode(): ConsoleMode {
  val resolved = MetroOptions.SystemProperties.DIAGNOSTICS_CONSOLE ?: diagnosticsConsole
  return if (resolved == ConsoleMode.AUTO) ConsoleMode.PLAIN else resolved
}

internal fun renderProfileFor(mode: ConsoleMode): RenderProfile =
  when (mode) {
    ConsoleMode.RICH -> RenderProfile.RICH
    ConsoleMode.PLAIN,
    ConsoleMode.AUTO -> RenderProfile.PLAIN
  }

/**
 * Console rendering choices that differ by mode.
 *
 * Layout logic is shared between modes. Profiles only choose glyphs, styling, source snippets, and
 * the column budget.
 */
internal data class RenderProfile(
  val glyphs: GlyphSet,
  val styler: Styler,
  val width: Int = DEFAULT_WIDTH,
  /**
   * When true, the renderer reads source files and draws miette-style frames with underlined,
   * labeled spans.
   *
   * Plain output never enables this so diagnostic goldens and log output stay independent of local
   * source availability.
   */
  val renderSourceSnippets: Boolean = false,
) {
  companion object {
    /**
     * Fixed render budget. The compiler usually runs in a daemon process, where terminal width is
     * not a reliable input.
     */
    const val DEFAULT_WIDTH = 100

    val PLAIN = RenderProfile(GlyphSet.ASCII, Styler.None)
    val RICH = RenderProfile(GlyphSet.UNICODE, Styler.Ansi, renderSourceSnippets = true)
  }
}

/** Structural glyphs. ASCII renders everywhere; Unicode is reserved for RICH output. */
internal data class GlyphSet(
  /** Dependency edge arrow: `A -> B`. */
  val arrow: String,
  /** Alias (`@Binds`) edge arrow: `A ~~> B`. */
  val aliasArrow: String,
  val bullet: String,
  /** Vertical continuation bar in multi-line structures. */
  val vbar: String,
  val hline: String,
  /** Opening of a cycle loop: `+->` / `╭─▶`. */
  val loopStart: String,
  /** Top-right closing of a horizontal cycle loop: `--+` / `─╮`. */
  val loopTopEnd: String,
  /** Bottom-left corner of a cycle loop: `+` / `╰`. */
  val loopBottomStart: String,
  /** Bottom-right corner of a horizontal cycle loop: `+` / `╯`. */
  val loopBottomEnd: String,
  /** Separator between an item and its location: ` - ` / ` — `. */
  val locationSeparator: String,
  /** Opening of a source frame header: `+-[` / `╭─[`. */
  val frameOpen: String,
  /** Closing corner of a source frame: `+-` / `╰─`. */
  val frameClose: String,
  /** Pointer in the middle of a span underline: `+` / `┬`. */
  val underlineTee: String,
  /** Elbow connecting an underline to its label: `+--` / `╰──`. */
  val labelElbow: String,
) {
  companion object {
    val ASCII =
      GlyphSet(
        arrow = "->",
        aliasArrow = "~~>",
        bullet = "-",
        vbar = "|",
        hline = "-",
        loopStart = "+->",
        loopTopEnd = "--+",
        loopBottomStart = "+",
        loopBottomEnd = "+",
        locationSeparator = " - ",
        frameOpen = "+-[",
        frameClose = "+-",
        underlineTee = "+",
        labelElbow = "+--",
      )

    val UNICODE =
      GlyphSet(
        arrow = "→",
        aliasArrow = "┄▶",
        bullet = "•",
        vbar = "│",
        hline = "─",
        loopStart = "╭─▶",
        loopTopEnd = "─╮",
        loopBottomStart = "╰",
        loopBottomEnd = "╯",
        locationSeparator = " — ",
        frameOpen = "╭─[",
        frameClose = "╰─",
        underlineTee = "┬",
        labelElbow = "╰──",
      )
  }
}

/** Realizes a [Style] on already-laid-out text. Must not change the text's visible width. */
internal fun interface Styler {
  fun apply(style: Style, text: String): String

  companion object {
    val None = Styler { _, text -> text }

    val Ansi = Styler { style, text ->
      val code =
        when (style) {
          Style.NONE -> return@Styler text
          Style.EMPHASIS -> AnsiCodes.BOLD
          Style.DIM -> AnsiCodes.DIM
          Style.ERROR -> AnsiCodes.RED
          Style.WARNING -> AnsiCodes.YELLOW
          Style.SUCCESS -> AnsiCodes.GREEN
          Style.UNDERLINE -> AnsiCodes.UNDERLINE
        }
      "$code$text${AnsiCodes.RESET}"
    }
  }
}

internal object AnsiCodes {
  const val BOLD = "\u001B[1m"
  const val DIM = "\u001B[2m"
  const val UNDERLINE = "\u001B[4m"
  const val RED = "\u001B[31m"
  const val GREEN = "\u001B[32m"
  const val YELLOW = "\u001B[33m"
  const val RESET = "\u001B[0m"

  private val ANSI_PATTERN = Regex("\u001B\\[[;:\\d]*m")

  fun strip(text: String): String = text.replace(ANSI_PATTERN, "")
}
