// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.MessageRenderer.Companion.ANSI_BOLD
import dev.zacsweers.metro.compiler.MessageRenderer.Companion.ANSI_CURLY_UNDERLINE
import dev.zacsweers.metro.compiler.MessageRenderer.Companion.ANSI_DIM
import dev.zacsweers.metro.compiler.MessageRenderer.Companion.ANSI_GREEN
import dev.zacsweers.metro.compiler.MessageRenderer.Companion.ANSI_ITALIC
import dev.zacsweers.metro.compiler.MessageRenderer.Companion.ANSI_RED
import dev.zacsweers.metro.compiler.MessageRenderer.Companion.ANSI_RESET
import dev.zacsweers.metro.compiler.MessageRenderer.Companion.ANSI_STRIKETHROUGH
import dev.zacsweers.metro.compiler.MessageRenderer.Companion.ANSI_UNDERLINE
import dev.zacsweers.metro.compiler.MessageRenderer.Companion.ANSI_YELLOW
import org.junit.Test

class MessageRendererTest {

  private val rich = MessageRenderer(richOutput = true)
  private val plain = MessageRenderer(richOutput = false)

  @Test
  fun `bold wraps in ANSI bold when rich`() {
    assertThat(rich.bold("hello")).isEqualTo("${ANSI_BOLD}hello$ANSI_RESET")
  }

  @Test
  fun `bold is passthrough when plain`() {
    assertThat(plain.bold("hello")).isEqualTo("hello")
  }

  @Test
  fun `italic wraps in ANSI italic when rich`() {
    assertThat(rich.italic("hello")).isEqualTo("${ANSI_ITALIC}hello$ANSI_RESET")
  }

  @Test
  fun `italic is passthrough when plain`() {
    assertThat(plain.italic("hello")).isEqualTo("hello")
  }

  @Test
  fun `red wraps in ANSI red when rich`() {
    assertThat(rich.red("error")).isEqualTo("${ANSI_RED}error$ANSI_RESET")
  }

  @Test
  fun `green wraps in ANSI green when rich`() {
    assertThat(rich.green("ok")).isEqualTo("${ANSI_GREEN}ok$ANSI_RESET")
  }

  @Test
  fun `yellow wraps in ANSI yellow when rich`() {
    assertThat(rich.yellow("warn")).isEqualTo("${ANSI_YELLOW}warn$ANSI_RESET")
  }

  @Test
  fun `underline wraps in ANSI underline when rich`() {
    assertThat(rich.underline("link")).isEqualTo("${ANSI_UNDERLINE}link$ANSI_RESET")
  }

  @Test
  fun `curlyUnderline uses extended underline sequence`() {
    assertThat(rich.curlyUnderline("squiggly"))
      .isEqualTo("${ANSI_CURLY_UNDERLINE}squiggly$ANSI_RESET")
  }

  @Test
  fun `strikethrough wraps in ANSI strikethrough when rich`() {
    assertThat(rich.strikethrough("removed")).isEqualTo("${ANSI_STRIKETHROUGH}removed$ANSI_RESET")
  }

  @Test
  fun `dim wraps in ANSI dim when rich`() {
    assertThat(rich.dim("context")).isEqualTo("${ANSI_DIM}context$ANSI_RESET")
  }

  @Test
  fun `all styles are passthrough when plain`() {
    assertThat(plain.red("a")).isEqualTo("a")
    assertThat(plain.green("a")).isEqualTo("a")
    assertThat(plain.yellow("a")).isEqualTo("a")
    assertThat(plain.underline("a")).isEqualTo("a")
    assertThat(plain.curlyUnderline("a")).isEqualTo("a")
    assertThat(plain.strikethrough("a")).isEqualTo("a")
    assertThat(plain.dim("a")).isEqualTo("a")
  }

  @Test
  fun `code wraps in backticks regardless of mode`() {
    assertThat(rich.code("foo.bar")).isEqualTo("`foo.bar`")
    assertThat(plain.code("foo.bar")).isEqualTo("`foo.bar`")
  }

  @Test
  fun `codeBlock indents each line`() {
    val input = "val x = 1\nval y = 2"
    val result = plain.codeBlock(input)
    assertThat(result).isEqualTo("    val x = 1\n    val y = 2")
  }

  @Test
  fun `codeBlock applies dim when rich`() {
    val result = rich.codeBlock("val x = 1")
    assertThat(result).isEqualTo("${ANSI_DIM}    val x = 1$ANSI_RESET")
  }

  @Test
  fun `codeBlock respects custom indent`() {
    val result = plain.codeBlock("line1\nline2", indent = "  > ")
    assertThat(result).isEqualTo("  > line1\n  > line2")
  }

  @Test
  fun `stripAnsi removes all ANSI codes`() {
    val styled = rich.bold("hello") + " " + rich.red("world")
    assertThat(MessageRenderer.stripAnsi(styled)).isEqualTo("hello world")
  }

  @Test
  fun `stripAnsi handles curly underline extended sequence`() {
    val styled = rich.curlyUnderline("squiggly")
    assertThat(MessageRenderer.stripAnsi(styled)).isEqualTo("squiggly")
  }

  @Test
  fun `stripAnsi is no-op on plain text`() {
    assertThat(MessageRenderer.stripAnsi("no codes here")).isEqualTo("no codes here")
  }

  @Test
  fun `buildMessage DSL produces expected output`() {
    val result = rich.buildMessage {
      append("Found ")
      appendBold("3")
      append(" errors in ")
      appendCode("AppGraph")
    }
    assertThat(MessageRenderer.stripAnsi(result)).isEqualTo("Found 3 errors in `AppGraph`")
  }

  @Test
  fun `buildMessage DSL plain mode`() {
    val result = plain.buildMessage {
      append("Found ")
      appendBold("3")
      append(" errors in ")
      appendCode("AppGraph")
    }
    assertThat(result).isEqualTo("Found 3 errors in `AppGraph`")
  }
}
