// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics.render

import dev.zacsweers.metro.compiler.diagnostics.Style

/**
 * Diagnostics rendering choices that differ by mode.
 *
 * Layout logic is shared between modes. Profiles only choose glyphs, styling, source snippets, and
 * the column budget.
 */
public data class RenderProfile(
  public val glyphs: GlyphSet,
  public val styler: Styler,
  public val width: Int = DEFAULT_WIDTH,
  /**
   * When true, the renderer reads source files and draws miette-style frames with underlined,
   * labeled spans.
   *
   * Plain output never enables this so diagnostic goldens and log output stay independent of local
   * source availability.
   */
  public val renderSourceSnippets: Boolean = false,
) {
  public companion object {
    /**
     * Fixed render budget. The compiler usually runs in a daemon process, where terminal width is
     * not a reliable input.
     */
    public const val DEFAULT_WIDTH: Int = 100

    public val PLAIN: RenderProfile = RenderProfile(GlyphSet.ASCII, Styler.Plain)
  }
}

/** Structural glyphs. ASCII renders everywhere; Unicode is reserved for RICH output. */
public data class GlyphSet(
  /** Dependency edge arrow: `A -> B`. */
  public val arrow: String,
  /** Alias (`@Binds`) edge arrow: `A ~~> B`. */
  public val aliasArrow: String,
  public val bullet: String,
  /** Vertical continuation bar in multi-line structures. */
  public val vbar: String,
  public val hline: String,
  /** Opening of a cycle loop: `+->` / `╭─▶`. */
  public val loopStart: String,
  /** Top-right closing of a horizontal cycle loop: `--+` / `─╮`. */
  public val loopTopEnd: String,
  /** Bottom-left corner of a cycle loop: `+` / `╰`. */
  public val loopBottomStart: String,
  /** Bottom-right corner of a horizontal cycle loop: `+` / `╯`. */
  public val loopBottomEnd: String,
  /** Separator between an item and its location: ` - ` / ` — `. */
  public val locationSeparator: String,
  /** Opening of a source frame headline: `+-` / `╭─`. */
  public val frameHeaderOpen: String,
  /** Closing corner of a source frame: `+-` / `╰─`. */
  public val frameClose: String,
  /** Elbow connecting an underline to its label: `+--` / `╰──`. */
  public val labelElbow: String,
  /** Pointer above a multi-line source range: `v` / `⌄`. */
  public val topPointer: String,
  /** Pointer below a source range: `^` / `⌃`. */
  public val bottomPointer: String,
) {
  public companion object {
    public val ASCII: GlyphSet =
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

    public val UNICODE: GlyphSet =
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
public fun interface Styler {
  public fun apply(style: Style, text: String): String

  public companion object {
    /** Identity styler for plain output. Styles are dropped; text passes through unchanged. */
    public val Plain: Styler = Styler { _, text -> text }
  }
}
