// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics.render

import dev.zacsweers.metro.compiler.diagnostics.DiagnosticSection
import dev.zacsweers.metro.compiler.diagnostics.DiagnosticSpan
import dev.zacsweers.metro.compiler.diagnostics.LocatedItem
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnostic
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.compiler.diagnostics.Note
import dev.zacsweers.metro.compiler.diagnostics.NoteKind
import dev.zacsweers.metro.compiler.diagnostics.Style
import dev.zacsweers.metro.compiler.diagnostics.Text
import dev.zacsweers.metro.compiler.diagnostics.textOf

/**
 * Per-diagnostic rendering context.
 *
 * [fullyQualifiedTypeNames] contains fqNames whose simple names are ambiguous within this
 * diagnostic, so they must render fully qualified.
 */
public class RenderContext(public val fullyQualifiedTypeNames: Set<String> = emptySet()) {
  public companion object {
    public val EMPTY: RenderContext = RenderContext()
  }
}

/**
 * Renders [MetroDiagnostic]s to console text.
 *
 * All console modes share the same layout. [profile] chooses glyphs, styling, source snippets, and
 * the column budget.
 */
public class DiagnosticRenderer(
  private val profile: RenderProfile,
  /** Source text lookup for frame rendering; only consulted when the profile enables snippets. */
  private val sourceLines: (String) -> List<String>? = { null },
) {

  private val glyphs: GlyphSet
    get() = profile.glyphs

  public fun render(
    diagnostic: MetroDiagnostic,
    context: RenderContext = RenderContext.EMPTY,
  ): String {
    val writer = LineWriter(profile.styler, profile.width)

    val severityStyle =
      when (diagnostic.severity) {
        MetroSeverity.ERROR -> Style.ERROR
        MetroSeverity.WARNING -> Style.WARNING
      }

    val titleRuns = diagnostic.titleRuns(severityStyle, context)
    val primaryFrameRendered =
      if (profile.renderSourceSnippets) {
        val primarySpan = diagnostic.primarySpan
        if (primarySpan == null) {
          false
        } else {
          renderFrames(
            writer = writer,
            items = listOf(SourceFrameItem(primarySpan, primarySpan.label)),
            context = context,
            headerText = titleRuns.joinToString(separator = "") { it.text },
            severity = diagnostic.severity,
          )
        }
      } else {
        false
      }
    val locationHeaderSection =
      if (!primaryFrameRendered && profile.renderSourceSnippets) {
        diagnostic.sections.firstOrNull { section ->
          section is DiagnosticSection.Locations &&
            section.header == null &&
            section.canRenderLocationFrame()
        }
      } else {
        null
      }

    if (!primaryFrameRendered && locationHeaderSection == null) {
      writer.line(titleRuns, indent = 0, hangingIndent = 4)
    }

    var locationHeaderRendered = false
    for (section in diagnostic.sections) {
      writer.blankLine()
      val locationFrameHeaderText =
        if (section === locationHeaderSection && !locationHeaderRendered) {
          titleRuns.joinToString(separator = "") { it.text }
        } else {
          null
        }
      renderSection(writer, section, context, diagnostic.severity, locationFrameHeaderText)
      if (locationFrameHeaderText != null) {
        locationHeaderRendered = true
      }
    }

    if (diagnostic.notes.isNotEmpty() || diagnostic.includeDocsUrl) {
      writer.blankLine()
      for (note in diagnostic.notes) {
        renderNote(writer, note, context)
      }
      if (diagnostic.includeDocsUrl) {
        renderNoteLine(writer, "docs:", Style.DIM, textOf(diagnostic.id.docsUrl), context)
      }
    }

    return writer.build()
  }

  private fun renderSection(
    writer: LineWriter,
    section: DiagnosticSection,
    context: RenderContext,
    severity: MetroSeverity,
    locationFrameHeaderText: String? = null,
  ) {
    when (section) {
      is DiagnosticSection.Chain -> renderChain(writer, section, context)
      is DiagnosticSection.BindingTrace -> renderBindingTrace(writer, section, context)
      is DiagnosticSection.Cycle -> renderCycle(writer, section, context)
      is DiagnosticSection.Locations ->
        renderLocations(writer, section, context, severity, locationFrameHeaderText)
      is DiagnosticSection.SimilarBindings -> renderSimilarBindings(writer, section, context)
      is DiagnosticSection.CodeBlock -> {
        section.location?.let { writer.raw(it, CONTENT_INDENT) }
        for (line in section.code.lines()) {
          writer.raw(line, CONTENT_INDENT, Style.DIM)
        }
      }
      is DiagnosticSection.Generic -> {
        writer.line(section.text.resolve(context), SECTION_INDENT, SECTION_INDENT)
      }
    }
  }

  private fun renderChain(
    writer: LineWriter,
    section: DiagnosticSection.Chain,
    context: RenderContext,
  ) {
    // Keep each edge and target together so wrapping happens between chain steps.
    val segments =
      section.items.mapIndexed { index, item ->
        if (index == 0) {
          item.resolve(context)
        } else {
          buildList {
            add(Run("${glyphs.arrow} ", Style.DIM))
            addAll(item.resolve(context))
          }
        }
      }
    writer.lineSegments(segments, SECTION_INDENT, SECTION_INDENT + 2)
  }

  private fun renderBindingTrace(
    writer: LineWriter,
    section: DiagnosticSection.BindingTrace,
    context: RenderContext,
  ) {
    writer.line(
      listOf(Run("trace (in "), Run(section.graphName, Style.EMPHASIS), Run("):")),
      SECTION_INDENT,
    )

    for (entry in section.entries) {
      writer.line(
        buildList {
          entry.graphName?.let { add(Run("[$it] ", Style.DIM)) }
          addAll(entry.key.resolve(context))
          entry.usage?.let { add(Run(" $it")) }
          entry.context?.let {
            add(Run(" "))
            addAll(it.resolve(context))
          }
        },
        indent = CONTENT_INDENT,
        hangingIndent = CONTENT_INDENT + 4,
      )
    }

    section.continuation?.let {
      writer.line(
        buildList {
          add(Run("… same as for ", Style.DIM))
          addAll(it.resolve(context))
        },
        indent = CONTENT_INDENT,
        hangingIndent = CONTENT_INDENT + 4,
      )
    }
  }

  private fun renderCycle(
    writer: LineWriter,
    section: DiagnosticSection.Cycle,
    context: RenderContext,
  ) {
    writer.line(listOf(Run("cycle:")), SECTION_INDENT)
    val nodes = section.nodes
    val resolvedNames = nodes.map { it.name.resolve(context) }
    val nameWidths = resolvedNames.map { runs -> runs.sumOf { it.text.length } }

    // Horizontal: +-> B --> A ~~> FakeA --+
    //             +-----------------------+
    var horizontalWidth = glyphs.loopStart.length + 1 + nameWidths[0]
    for (i in 1 until nodes.size) {
      val arrow = if (nodes[i - 1].aliasEdgeToNext) glyphs.aliasArrow else glyphs.arrow
      horizontalWidth += 1 + arrow.length + 1 + nameWidths[i]
    }
    horizontalWidth += 1 + glyphs.loopTopEnd.length

    if (CONTENT_INDENT + horizontalWidth <= profile.width) {
      val topRuns = buildList {
        add(Run("${glyphs.loopStart} ", Style.DIM))
        addAll(resolvedNames[0].map { it.copy(style = emphasize(it.style)) })

        for (i in 1 until nodes.size) {
          val arrow = if (nodes[i - 1].aliasEdgeToNext) glyphs.aliasArrow else glyphs.arrow
          add(Run(" $arrow ", Style.DIM))
          addAll(resolvedNames[i].map { it.copy(style = emphasize(it.style)) })
        }

        add(Run(" ${glyphs.loopTopEnd}", Style.DIM))
      }

      writer.lineSegments(listOf(topRuns), CONTENT_INDENT)

      val fill = horizontalWidth - glyphs.loopBottomStart.length - glyphs.loopBottomEnd.length
      writer.raw(
        glyphs.loopBottomStart + glyphs.hline.repeat(fill) + glyphs.loopBottomEnd,
        CONTENT_INDENT,
        Style.DIM,
      )
    } else {
      // Vertical: +-> B
      //           |   --> A
      //           |   ~~> FakeA
      //           +-- back to B
      writer.lineSegments(
        listOf(
          buildList {
            add(Run("${glyphs.loopStart} ", Style.DIM))
            addAll(resolvedNames[0])
          }
        ),
        CONTENT_INDENT,
      )

      val continuationPad = " ".repeat(glyphs.loopStart.length - glyphs.vbar.length)

      for (i in 1 until nodes.size) {
        val arrow = if (nodes[i - 1].aliasEdgeToNext) glyphs.aliasArrow else glyphs.arrow
        writer.lineSegments(
          listOf(
            buildList {
              add(Run("${glyphs.vbar}$continuationPad $arrow ", Style.DIM))
              addAll(resolvedNames[i])
            }
          ),
          CONTENT_INDENT,
        )
      }

      writer.lineSegments(
        listOf(
          buildList {
            add(Run("${glyphs.loopBottomStart}${glyphs.hline.repeat(2)} back to ", Style.DIM))
            addAll(resolvedNames[0])
          }
        ),
        CONTENT_INDENT,
      )
    }
  }

  private fun renderLocations(
    writer: LineWriter,
    section: DiagnosticSection.Locations,
    context: RenderContext,
    severity: MetroSeverity,
    frameHeaderText: String? = null,
  ) {
    section.header?.let { writer.line(it.resolve(context), SECTION_INDENT, SECTION_INDENT) }

    // Merge spans by file so related locations can share one source frame.
    val framed = mutableSetOf<LocatedItem>()
    var pendingFrameHeaderText = frameHeaderText
    if (profile.renderSourceSnippets) {
      section.items
        .filter { it.span != null }
        .groupBy { it.span!!.filePath }
        .forEach { (_, items) ->
          val frameItems = items.map { item ->
            SourceFrameItem(
              span = item.span!!,
              label = item.span.label ?: item.description,
              excerpt = if (item.preferSourceSnippet) null else item.code?.toSourceExcerpt(),
              includeLeadingAnnotations = item.includeLeadingAnnotations,
            )
          }
          if (
            renderFrames(
              writer,
              frameItems,
              context,
              headerText = pendingFrameHeaderText,
              severity = severity,
              collapseMultilineSpans = true,
            )
          ) {
            framed += items
            pendingFrameHeaderText = null
          }
        }
    }

    var renderedFallback = false
    for (item in section.items) {
      if (item in framed) continue
      if (renderedFallback && item.code != null) writer.blankLine()
      val locationRuns = buildList {
        item.location?.let { add(Run(it)) }
        item.description?.let {
          if (item.location != null) add(Run(glyphs.locationSeparator, Style.DIM))
          addAll(it.resolve(context))
        }
      }
      if (locationRuns.isNotEmpty()) {
        writer.line(locationRuns, CONTENT_INDENT, CONTENT_INDENT + 4)
      }
      item.code?.lines()?.forEach { writer.raw(it, NESTED_INDENT, Style.DIM) }
      renderedFallback = true
    }
  }

  private fun DiagnosticSection.Locations.canRenderLocationFrame(): Boolean {
    return items.any { item ->
      val span = item.span ?: return@any false
      item.code?.toSourceExcerpt() != null || sourceLines(span.filePath) != null
    }
  }

  /** Renders a Kotlin-toolchain-style source frame for [spans] in the same file. */
  private fun renderFrames(
    writer: LineWriter,
    items: List<SourceFrameItem>,
    context: RenderContext,
    headerText: String? = null,
    severity: MetroSeverity? = null,
    collapseMultilineSpans: Boolean = false,
  ): Boolean {
    val first = items.first().span
    val lines = sourceLines(first.filePath)
    if (lines == null && items.any { it.excerpt == null }) return false
    val sorted =
      items
        .map { item ->
          val span =
            if (item.excerpt == null && item.includeLeadingAnnotations) {
              item.span.expandToLeadingAnnotations(lines, collapseMultilineSpans)
            } else {
              item.span.collapseMultilineForLocation(lines, collapseMultilineSpans)
            }
          item.copy(span = span)
        }
        .sortedBy { it.span.line }
    val maxLineNo = sorted.maxOf { it.span.endLine.coerceAtLeast(it.span.line) }
    val gutterWidth = maxLineNo.toString().length
    val borderPrefix = " ".repeat(gutterWidth + 1)
    val frameIndent = 0
    val contentBudget = profile.width - frameIndent - gutterWidth - 3

    writer.blankLine()
    val displayPath = first.renderDisplayPath()
    if (headerText == null) {
      writer.raw(
        "$borderPrefix${glyphs.frameHeaderOpen} $displayPath:${first.line}:${first.column}",
        frameIndent,
        Style.DIM,
      )
    } else {
      writer.rawRuns(
        buildList {
          add(Run("$borderPrefix${glyphs.frameHeaderOpen} ", Style.DIM))
          add(Run(headerText, Style.EMPHASIS))
        },
        frameIndent,
      )
      writer.rawRuns(
        listOf(
          Run("$borderPrefix${glyphs.vbar} ${glyphs.arrow} ", Style.DIM),
          Run("$displayPath:${first.line}:${first.column}"),
        ),
        frameIndent,
      )
      writer.raw("$borderPrefix${glyphs.vbar}", frameIndent, Style.DIM)
    }

    val rangeStyle = severity?.toStyle() ?: Style.DIM
    for (item in sorted) {
      val span = item.span
      val label = item.label
      val excerpt = item.excerpt
      val snippet =
        excerpt?.let { listOf(it.line) } ?: snippetFor(lines.orEmpty(), span, contentBudget)
      if (snippet.isEmpty()) continue
      val isMultiLine = snippet.size > 1

      if (isMultiLine) {
        writer.rawRuns(
          listOf(
            Run("$borderPrefix${glyphs.vbar} ", Style.DIM),
            Run(buildTopPointer(span, snippet.first()), rangeStyle),
          ),
          frameIndent,
        )
      }

      snippet.forEachIndexed { index, line ->
        val lineNo = (span.line + index).toString().padStart(gutterWidth)
        writer.rawRuns(
          buildList {
            add(Run("$lineNo ${glyphs.vbar} ", Style.DIM))
            if (excerpt != null) {
              addAll(highlightLineRange(line, excerpt.start, excerpt.end, rangeStyle))
            } else {
              addAll(highlightRange(line, span, index, snippet.size, rangeStyle))
            }
          },
          frameIndent,
        )
      }

      writer.rawRuns(
        listOf(
          Run("$borderPrefix${glyphs.vbar} ", Style.DIM),
          Run(
            if (excerpt == null) {
              buildBottomPointer(span, isMultiLine)
            } else {
              buildBottomPointer(excerpt.start, excerpt.end)
            },
            rangeStyle,
          ),
        ),
        frameIndent,
      )

      if (label != null) {
        writer.rawRuns(
          buildList {
            add(Run("$borderPrefix${glyphs.vbar} ", Style.DIM))
            add(Run("${glyphs.labelElbow} ", Style.DIM))
            addAll(label.resolve(context))
          },
          frameIndent,
        )
      }
    }
    writer.raw("$borderPrefix${glyphs.frameClose}", frameIndent, Style.DIM)
    return true
  }

  private fun snippetFor(
    lines: List<String>,
    span: DiagnosticSpan,
    contentBudget: Int,
  ): List<String> {
    val endLine = span.endLine.coerceAtLeast(span.line)
    return (span.line..endLine).mapNotNull { lineNo ->
      val line = lines.getOrNull(lineNo - 1) ?: return@mapNotNull null
      if (line.length > contentBudget) line.take(contentBudget - 1) + "…" else line
    }
  }

  private fun DiagnosticSpan.collapseMultilineForLocation(
    lines: List<String>?,
    collapse: Boolean,
  ): DiagnosticSpan {
    if (!collapse || endLine <= line) return this
    val sourceLine = lines?.getOrNull(line - 1) ?: return copy(endLine = line)
    return copy(endLine = line, endColumn = sourceLine.trimEnd().length + 1)
  }

  private fun DiagnosticSpan.expandToLeadingAnnotations(
    lines: List<String>?,
    collapse: Boolean,
  ): DiagnosticSpan {
    if (lines == null) return collapseMultilineForLocation(lines, collapse)
    val declarationLine =
      lines.getOrNull(line - 1) ?: return collapseMultilineForLocation(lines, collapse)
    var startLine = line
    var previousIndex = line - 2
    while (previousIndex >= 0 && lines[previousIndex].trimStart().startsWith("@")) {
      startLine = previousIndex + 1
      previousIndex--
    }
    if (startLine == line) return collapseMultilineForLocation(lines, collapse)
    val startColumn =
      lines[startLine - 1]
        .indexOfFirst { !it.isWhitespace() }
        .let { index ->
          if (index == -1) 1 else index + 1
        }
    val newEndLine = if (collapse) line else endLine
    val newEndColumn =
      if (collapse) {
        declarationLine.trimEnd().length + 1
      } else {
        endColumn
      }
    return copy(
      line = startLine,
      column = startColumn,
      endLine = newEndLine,
      endColumn = newEndColumn,
    )
  }

  private fun buildTopPointer(span: DiagnosticSpan, firstLine: String): String {
    val padding = span.column - 1
    val length = firstLine.length - padding
    return " ".repeat(padding) + glyphs.topPointer.repeat(length.coerceAtLeast(1))
  }

  private fun buildBottomPointer(span: DiagnosticSpan, isMultiLine: Boolean): String {
    if (isMultiLine) {
      val length = (span.endColumn - 1).coerceAtLeast(1)
      return glyphs.bottomPointer.repeat(length)
    }

    val padding = span.column - 1
    val length =
      if (span.endColumn > span.column) {
        span.endColumn - span.column
      } else {
        1
      }
    return " ".repeat(padding) + glyphs.bottomPointer.repeat(length)
  }

  private fun buildBottomPointer(start: Int, end: Int): String {
    val length = (end - start).coerceAtLeast(1)
    return " ".repeat(start) + glyphs.bottomPointer.repeat(length)
  }

  private fun highlightLineRange(line: String, start: Int, end: Int, style: Style): List<Run> {
    val clampedStart = start.coerceIn(0, line.length)
    val clampedEnd = end.coerceIn(clampedStart, line.length)
    return buildList {
      add(Run(line.substring(0, clampedStart)))
      add(Run(line.substring(clampedStart, clampedEnd), style))
      add(Run(line.substring(clampedEnd)))
    }
  }

  private fun highlightRange(
    line: String,
    span: DiagnosticSpan,
    lineIndex: Int,
    totalLines: Int,
    style: Style,
  ): List<Run> {
    val (start, end) =
      when {
        totalLines == 1 -> {
          val start = (span.column - 1).coerceIn(0, line.length)
          val end =
            if (span.endColumn > span.column) {
              (span.endColumn - 1).coerceIn(start, line.length)
            } else {
              (start + 1).coerceAtMost(line.length)
            }
          start to end
        }
        lineIndex == 0 -> {
          val start = (span.column - 1).coerceIn(0, line.length)
          start to line.length
        }
        lineIndex == totalLines - 1 -> {
          val end =
            if (span.endColumn > 0) {
              (span.endColumn - 1).coerceIn(0, line.length)
            } else {
              line.length
            }
          0 to end
        }
        else -> 0 to line.length
      }

    return buildList {
      add(Run(line.substring(0, start)))
      add(Run(line.substring(start, end), style))
      add(Run(line.substring(end)))
    }
  }

  private fun renderSimilarBindings(
    writer: LineWriter,
    section: DiagnosticSection.SimilarBindings,
    context: RenderContext,
  ) {
    writer.line(listOf(Run("similar bindings:")), SECTION_INDENT)
    for (item in section.items) {
      writer.line(
        buildList {
          add(Run("${glyphs.bullet} ", Style.DIM))
          addAll(item.key.resolve(context))
          add(Run(" (${item.description})"))
          item.location?.let {
            add(Run(glyphs.locationSeparator, Style.DIM))
            add(Run(it, Style.DIM))
          }
        },
        indent = CONTENT_INDENT,
        hangingIndent = CONTENT_INDENT + 2,
      )
    }
  }

  private fun renderNote(writer: LineWriter, note: Note, context: RenderContext) {
    val (label, style) =
      when (note.kind) {
        NoteKind.HELP -> "help:" to Style.INFO
        NoteKind.NOTE -> "note:" to Style.DIM
      }
    renderNoteLine(writer, label, style, note.text, context)
  }

  private fun renderNoteLine(
    writer: LineWriter,
    label: String,
    style: Style,
    text: Text,
    context: RenderContext,
  ) {
    writer.line(
      buildList {
        add(Run(label, style))
        add(Run(" "))
        addAll(text.resolve(context))
      },
      indent = SECTION_INDENT,
      hangingIndent = SECTION_INDENT + label.length + 1,
    )
  }

  /** Cycle node names render emphasized unless the span already carries a style. */
  private fun emphasize(style: Style): Style = if (style == Style.NONE) Style.EMPHASIS else style

  public companion object {
    private const val SECTION_INDENT = 2
    private const val CONTENT_INDENT = 6
    private const val NESTED_INDENT = 8
  }
}

private fun MetroDiagnostic.titleRuns(severityStyle: Style, context: RenderContext): List<Run> =
  buildList {
    add(Run("[${id.fullId}]", severityStyle))
    add(Run(" "))
    addAll(title.resolve(context))
  }

private data class SourceFrameItem(
  val span: DiagnosticSpan,
  val label: Text?,
  val excerpt: SourceExcerpt? = null,
  val includeLeadingAnnotations: Boolean = true,
)

private data class SourceExcerpt(val line: String, val start: Int, val end: Int)

private fun String.toSourceExcerpt(): SourceExcerpt? {
  val lines = lines()
  for (index in 0 until lines.lastIndex) {
    val markerLine = lines[index + 1]
    if ('~' !in markerLine) continue
    if (!markerLine.all { it == ' ' || it == '~' }) continue

    val start = markerLine.indexOf('~')
    val end = markerLine.lastIndexOf('~') + 1
    return SourceExcerpt(lines[index], start, end)
  }
  return null
}

private fun DiagnosticSpan.renderDisplayPath(): String {
  val path = displayPath ?: filePath
  val normalized = path.removePrefix("file://")
  val isAbsolute = normalized.startsWith("/") || WINDOWS_ABSOLUTE_PATH.matches(normalized)
  return if (isAbsolute) normalized.fileName() else normalized
}

private fun String.fileName(): String = substringAfterLast('/').substringAfterLast('\\')

private val WINDOWS_ABSOLUTE_PATH = Regex("[A-Za-z]:[/\\\\].*")

private fun MetroSeverity.toStyle(): Style =
  when (this) {
    MetroSeverity.ERROR -> Style.ERROR
    MetroSeverity.WARNING -> Style.WARNING
  }

/** Resolves styled [Text] to layout [Run]s, deciding simple vs fully-qualified type renders. */
public fun Text.resolve(context: RenderContext): List<Run> = spans.map { span ->
  when (span) {
    is Text.Span.Plain -> Run(span.text, span.style)
    is Text.Span.Code -> Run("`${span.text}`")
    is Text.Span.Type ->
      Run(
        if (span.fqName in context.fullyQualifiedTypeNames) span.fqRender else span.simpleRender,
        span.style,
      )
  }
}
