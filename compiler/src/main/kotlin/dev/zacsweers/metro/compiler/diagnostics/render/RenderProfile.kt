// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics.render

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import dev.zacsweers.metro.compiler.DiagnosticsRenderMode
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.diagnostics.Style

/**
 * Resolves the effective diagnostics render mode.
 *
 * The `metro.diagnosticsRenderMode` system property overrides the compiler option.
 * [DiagnosticsRenderMode.AUTO] is resolved by the Gradle plugin before compiler invocation; if it
 * reaches the compiler anyway, use plain output.
 */
internal fun MetroOptions.resolveDiagnosticsRenderMode(): DiagnosticsRenderMode {
  val resolved = MetroOptions.SystemProperties.DIAGNOSTICS_RENDER_MODE ?: diagnosticsRenderMode
  return if (resolved == DiagnosticsRenderMode.AUTO) DiagnosticsRenderMode.PLAIN else resolved
}

internal fun renderProfileFor(mode: DiagnosticsRenderMode): RenderProfile =
  when (mode) {
    DiagnosticsRenderMode.RICH -> RenderProfile.RICH
    DiagnosticsRenderMode.PLAIN,
    DiagnosticsRenderMode.AUTO -> RenderProfile.PLAIN
  }

/**
 * Diagnostics rendering choices that differ by mode.
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

    val PLAIN = RenderProfile(GlyphSet.ASCII, Styler.Plain)
    val RICH = RenderProfile(GlyphSet.UNICODE, Styler.Rich, renderSourceSnippets = true)
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
  /** Opening of a source frame headline: `+-` / `╭─`. */
  val frameHeaderOpen: String,
  /** Closing corner of a source frame: `+-` / `╰─`. */
  val frameClose: String,
  /** Elbow connecting an underline to its label: `+--` / `╰──`. */
  val labelElbow: String,
  /** Pointer above a multi-line source range: `v` / `⌄`. */
  val topPointer: String,
  /** Pointer below a source range: `^` / `⌃`. */
  val bottomPointer: String,
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
        frameHeaderOpen = "+-",
        frameClose = "+-",
        labelElbow = "+--",
        topPointer = "v",
        bottomPointer = "^",
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
        frameHeaderOpen = "╭─",
        frameClose = "╰─",
        labelElbow = "╰──",
        topPointer = "⌄",
        bottomPointer = "⌃",
      )
  }
}

/** Realizes a [Style] on already-laid-out text. Must not change the text's visible width. */
internal fun interface Styler {
  fun apply(style: Style, text: String): String

  companion object {
    val Plain: Styler = MordantStyler(AnsiLevel.NONE)

    val Rich: Styler = MordantStyler(AnsiLevel.ANSI16)
  }
}

private class MordantStyler(ansiLevel: AnsiLevel) : Styler {
  private val terminal = Terminal(ansiLevel = ansiLevel, hyperlinks = false, interactive = false)

  override fun apply(style: Style, text: String): String {
    val styled =
      when (style) {
        Style.NONE -> text
        Style.EMPHASIS -> TextStyles.bold(text)
        Style.DIM -> terminal.theme.muted(text)
        Style.ERROR -> terminal.theme.danger(text)
        Style.WARNING -> terminal.theme.warning(text)
        Style.INFO -> terminal.theme.info(text)
        Style.SUCCESS -> terminal.theme.success(text)
        Style.ERROR_EMPHASIS -> (terminal.theme.danger + TextStyles.bold)(text)
        Style.WARNING_EMPHASIS -> (terminal.theme.warning + TextStyles.bold)(text)
        Style.UNDERLINE -> TextStyles.underline(text)
      }

    return terminal.render(styled)
  }
}
