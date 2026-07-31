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
 * [DiagnosticsRenderMode.AUTO] is resolved by the Gradle plugin before compiler invocation. If it
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

internal val RenderProfile.Companion.RICH: RenderProfile
  get() = RichRenderProfile

internal val Styler.Companion.Rich: Styler
  get() = RichStyler

// Lazy for two reasons: eager top-level initializers run in declaration order, which made the
// profile read the styler's backing field before it was assigned, and plain-mode compilations
// share this file through renderProfileFor and should never touch mordant.
private val RichStyler: Styler by lazy { MordantStyler(AnsiLevel.ANSI16) }

private val RichRenderProfile by lazy {
  RenderProfile(GlyphSet.UNICODE, RichStyler, renderSourceSnippets = true)
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
