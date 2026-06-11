// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics

/**
 * Semantic styles applied to [Text] spans.
 *
 * Render profiles decide how these become ANSI codes or plain text; model code never deals in
 * escape sequences.
 */
internal enum class Style {
  NONE,
  /** Key content like type names and declaration names. Bold in rich output. */
  EMPHASIS,
  /** De-emphasized supporting content. Dimmed in rich output. */
  DIM,
  /** Error-colored content (red in rich output). */
  ERROR,
  /** Warning-colored content (yellow in rich output). */
  WARNING,
  /** Positive/suggestion content (green in rich output). */
  SUCCESS,
  UNDERLINE,
}

/**
 * Styled diagnostic text with no layout or terminal escape codes.
 *
 * Type names are first-class spans so renderers can use simple names by default and switch to fully
 * qualified names only for ambiguous simple names in the same diagnostic.
 */
internal class Text(internal val spans: List<Span>) {

  internal sealed interface Span {
    val style: Style

    data class Plain(val text: String, override val style: Style = Style.NONE) : Span

    /** Inline code, rendered with backticks in all modes. */
    data class Code(val text: String) : Span {
      override val style: Style
        get() = Style.NONE
    }

    /**
     * A type reference. Renderers prefer [simpleRender] and use [fqRender] when [fqName] is
     * ambiguous in the current diagnostic.
     */
    data class Type(
      val fqName: String,
      val simpleRender: String,
      val fqRender: String,
      override val style: Style = Style.EMPHASIS,
    ) : Span
  }

  fun isEmpty(): Boolean = spans.isEmpty() || spans.all { it is Span.Plain && it.text.isEmpty() }

  val typeSpans: List<Span.Type>
    get() = spans.filterIsInstance<Span.Type>()

  /** Unstyled rendering with simple type names. For tests, JSON output, and fallbacks. */
  override fun toString(): String =
    spans.joinToString("") { span ->
      when (span) {
        is Span.Plain -> span.text
        is Span.Code -> "`${span.text}`"
        is Span.Type -> span.simpleRender
      }
    }

  override fun equals(other: Any?): Boolean = other is Text && other.spans == spans

  override fun hashCode(): Int = spans.hashCode()

  companion object {
    val EMPTY = Text(emptyList())
  }
}

internal fun textOf(text: String, style: Style = Style.NONE): Text =
  Text(listOf(Text.Span.Plain(text, style)))

internal inline fun buildText(block: TextBuilder.() -> Unit): Text =
  TextBuilder().apply(block).build()

internal class TextBuilder {
  private val spans = mutableListOf<Text.Span>()

  @IgnorableReturnValue
  fun append(text: String, style: Style = Style.NONE) = apply {
    spans += Text.Span.Plain(text, style)
  }

  @IgnorableReturnValue fun append(text: Text) = apply { spans += text.spans }

  @IgnorableReturnValue fun appendCode(text: String) = apply { spans += Text.Span.Code(text) }

  @IgnorableReturnValue
  fun appendType(
    fqName: String,
    simpleRender: String = fqName.substringAfterLast('.'),
    fqRender: String = fqName,
    style: Style = Style.EMPHASIS,
  ) = apply {
    spans += Text.Span.Type(fqName, simpleRender, fqRender, style)
  }

  fun build(): Text = Text(spans.toList())
}
