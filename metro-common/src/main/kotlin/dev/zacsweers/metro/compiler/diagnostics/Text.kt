// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics

/**
 * Semantic styles applied to [Text] spans.
 *
 * Render profiles decide how these become ANSI codes or plain text; model code never deals in
 * escape sequences.
 */
public enum class Style {
  NONE,
  /** Key content like type names and declaration names. Bold in rich output. */
  EMPHASIS,
  /** De-emphasized supporting content. Dimmed in rich output. */
  DIM,
  /** Error-colored content (red in rich output). */
  ERROR,
  /** Warning-colored content in rich output. */
  WARNING,
  /** Informational/guidance content. */
  INFO,
  /** Positive/suggestion content in rich output. */
  SUCCESS,
  /** Bold error-colored content (red + bold in rich output). */
  ERROR_EMPHASIS,
  /** Bold warning-colored content (yellow + bold in rich output). */
  WARNING_EMPHASIS,
  UNDERLINE,
}

/**
 * Styled diagnostic text with no layout or terminal escape codes.
 *
 * Type names are first-class spans so renderers can use simple names by default and switch to fully
 * qualified names only for ambiguous simple names in the same diagnostic.
 */
public class Text(public val spans: List<Span>) {

  public sealed interface Span {
    public val style: Style

    public data class Plain(val text: String, override val style: Style = Style.NONE) : Span

    /** Inline code, rendered with backticks in all modes. */
    public data class Code(val text: String) : Span {
      override val style: Style
        get() = Style.NONE
    }

    /**
     * A type reference. Renderers prefer [simpleRender] and use [fqRender] when [fqName] is
     * ambiguous in the current diagnostic.
     */
    public data class Type(
      val fqName: String,
      val simpleRender: String,
      val fqRender: String,
      override val style: Style = Style.EMPHASIS,
    ) : Span
  }

  public fun isEmpty(): Boolean =
    spans.isEmpty() || spans.all { it is Span.Plain && it.text.isEmpty() }

  public val typeSpans: List<Span.Type>
    get() = spans.filterIsInstance<Span.Type>()

  /** Unstyled rendering with simple type names. For tests and fallbacks. */
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

  public companion object {
    public val EMPTY: Text = Text(emptyList())
  }
}

public fun textOf(text: String, style: Style = Style.NONE): Text =
  Text(listOf(Text.Span.Plain(text, style)))

public inline fun buildText(block: TextBuilder.() -> Unit): Text =
  TextBuilder().apply(block).build()

public class TextBuilder {
  private val spans = mutableListOf<Text.Span>()

  @IgnorableReturnValue
  public fun append(text: String, style: Style = Style.NONE): TextBuilder = apply {
    spans += Text.Span.Plain(text, style)
  }

  @IgnorableReturnValue public fun append(text: Text): TextBuilder = apply { spans += text.spans }

  @IgnorableReturnValue
  public fun appendCode(text: String): TextBuilder = apply { spans += Text.Span.Code(text) }

  @IgnorableReturnValue
  public fun appendType(
    fqName: String,
    simpleRender: String = fqName.substringAfterLast('.'),
    fqRender: String = fqName,
    style: Style = Style.EMPHASIS,
  ): TextBuilder = apply {
    spans += Text.Span.Type(fqName, simpleRender, fqRender, style)
  }

  public fun build(): Text = Text(spans.toList())
}
