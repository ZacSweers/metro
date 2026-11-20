// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.services.ReversibleSourceFilePreprocessor
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.isJavaFile

/** Supplies fixture imports during compilation and removes them from diagnostic updates. */
abstract class ImportsPreprocessor(testServices: TestServices) :
  ReversibleSourceFilePreprocessor(testServices) {

  abstract val additionalImports: Set<String>

  private val additionalImportsString by lazy {
    additionalImports.sorted().joinToString(separator = "\n") { "import $it" }
  }

  final override fun process(file: TestFile, content: String): String {
    if (file.isAdditional || file.isJavaFile) {
      return content
    }

    return content.withFixtureImports(additionalImportsString)
  }

  final override fun revert(file: TestFile, actualContent: String): String {
    if (file.isAdditional || file.isJavaFile) {
      return actualContent
    }

    return actualContent.withoutFixtureImports(additionalImportsString)
  }
}

/** Inserts imports after the file header. A header at EOF needs its own line break. */
internal fun String.withFixtureImports(imports: String): String {
  val offset = fixtureImportOffset()
  val unterminatedHeader = offset > 0 && offset == length
  if (unterminatedHeader && !endsWith('\n')) {
    return "$this\n$imports"
  }

  return replaceRange(offset, offset, "$imports\n")
}

/**
 * Skips header text and removes one injected block, preserving identical imports in the fixture.
 */
internal fun String.withoutFixtureImports(imports: String): String {
  val block = "$imports\n"
  val start = indexOf(block, fixtureImportOffset())
  if (start < 0) {
    return removeSuffix("\n$imports")
  }

  return removeRange(start, start + block.length)
}

/** Stops at the first declaration or import so text in the body can't affect placement. */
private fun String.fixtureImportOffset(): Int {
  // Preserve leading blank lines that the test framework uses for multi-file source positions.
  val firstContent = indexOfFirst { !it.isWhitespace() }
  var offset = lastIndexOf('\n', firstContent) + 1
  if (firstContent < 0) {
    return offset
  }

  val lexer = KotlinLexer()
  lexer.start(this)
  if (lexer.tokenType == KtTokens.SHEBANG_COMMENT) {
    offset = lexer.nextToken()
  } else {
    lexer.skipTrivia()
  }

  while (lexer.tokenType == KtTokens.AT) {
    lexer.nextToken()
    if (lexer.tokenType == null || lexer.tokenText != "file") {
      return offset
    }

    lexer.nextToken()
    if (lexer.tokenType != KtTokens.COLON) {
      return offset
    }

    lexer.nextToken()
    offset = lexer.skipHeaderEntry()
  }

  if (lexer.tokenType == KtTokens.PACKAGE_KEYWORD) {
    lexer.nextToken()
    return lexer.skipHeaderEntry()
  }

  return offset
}

/** Consumes a qualified name or a file annotation with balanced arguments or brackets. */
private fun KotlinLexer.skipHeaderEntry(): Int {
  var depth = 0
  var offset = tokenStart
  while (tokenType != null) {
    val token = tokenType
    when (token) {
      KtTokens.LPAR,
      KtTokens.LBRACKET -> depth++
      KtTokens.RPAR,
      KtTokens.RBRACKET -> depth--
    }

    offset = nextToken()
    val inName = token == KtTokens.DOT || tokenType == KtTokens.DOT
    val inArguments = depth > 0 || tokenType == KtTokens.LPAR
    if (!inName && !inArguments) {
      break
    }
  }

  if (tokenType == KtTokens.SEMICOLON) {
    return nextToken()
  }

  return offset
}

private fun KotlinLexer.nextToken(): Int {
  advance()
  return skipTrivia()
}

/**
 * Prefers the next line boundary outside comments. Keeps the lexer on the next significant token.
 */
private fun KotlinLexer.skipTrivia(): Int {
  var nextLine: Int? = null
  while (KtTokens.WHITE_SPACE_OR_COMMENT_BIT_SET.contains(tokenType)) {
    if (nextLine == null && tokenType == KtTokens.WHITE_SPACE) {
      val newline = tokenText.indexOf('\n')
      if (newline >= 0) {
        nextLine = tokenStart + newline + 1
      }
    }
    advance()
  }

  return nextLine ?: tokenStart
}
