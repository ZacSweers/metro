// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.test.Test
import kotlin.test.assertEquals

/** Checks the source seen by the compiler and the source written back to the fixture. */
class ImportsPreprocessorTest {
  private val imports = "import dev.zacsweers.metro.*"

  @Test
  fun `inserts after an indented package and its trailing comment`() {
    val header = "  package demo.pkg /*\n    comment */\n"
    assertInsertion(header, "val value = 1")
  }

  @Test
  fun `inserts after all file annotations`() {
    val header =
      """
      @file:Suppress(
        "unused",
      )
      @file:[JvmName("Example") JvmMultifileClass]
      """
        .trimIndent() + "\n"
    assertInsertion(header, "\nclass Example")
    assertInsertion(header + "package example\n", "class Example")
  }

  @Test
  fun `ignores header text in the body`() {
    val body = "val source = \"\"\"\n@file:Suppress(\"unused\")\npackage example\n$imports\n\"\"\""
    assertInsertion("\n\n", body)
  }

  @Test
  fun `preserves an existing import while adding diagnostic markers`() {
    val header = "/*\n$imports\n*/\npackage example\n"
    val source = "$header$imports\nval value = 1"
    val updated = source.withFixtureImports(imports).replace("value", "<!WARNING!>value<!>")
    assertEquals(
      "$header$imports\nval <!WARNING!>value<!> = 1",
      updated.withoutFixtureImports(imports),
    )
  }

  @Test
  fun `preserves the final line break when the fixture only has a header`() {
    val source = "package demo"
    val processed = source.withFixtureImports(imports)
    assertEquals("$source\n$imports", processed)
    assertEquals(source, processed.withoutFixtureImports(imports))
    assertInsertion("$source\n", "")
  }

  @Test
  fun `both box preprocessors can remove their own block`() {
    val other = "import helpers.runBlocking\nimport kotlin.test.*"
    val source = "package example\n$other\n$imports\nfun box() = 1"
    val processed = source.withFixtureImports(imports).withFixtureImports(other)
    assertEquals(source, processed.withoutFixtureImports(imports).withoutFixtureImports(other))
  }

  private fun assertInsertion(header: String, body: String) {
    val source = header + body
    val processed = source.withFixtureImports(imports)
    assertEquals("$header$imports\n$body", processed)
    assertEquals(source, processed.withoutFixtureImports(imports))
  }
}
