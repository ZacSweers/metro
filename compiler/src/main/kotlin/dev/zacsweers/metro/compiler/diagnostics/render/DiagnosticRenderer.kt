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
internal class RenderContext(val fullyQualifiedTypeNames: Set<String> = emptySet()) {
  companion object {
    val EMPTY = RenderContext()
  }
}

/**
 * Renders [MetroDiagnostic]s to console text.
 *
 * All console modes share the same layout. [profile] chooses glyphs, styling, source snippets, and
 * the column budget.
 */
internal class DiagnosticRenderer(
  private val profile: RenderProfile,
  /** Source text lookup for frame rendering; only consulted when the profile enables snippets. */
  private val sourceLines: (String) -> List<String>? = { null },
) {

  private val glyphs: GlyphSet
    get() = profile.glyphs

  fun render(diagnostic: MetroDiagnostic, context: RenderContext = RenderContext.EMPTY): String {
    val writer = LineWriter(profile.styler, profile.width)

    val tagStyle =
      when (diagnostic.severity) {
        MetroSeverity.ERROR -> Style.ERROR
        MetroSeverity.WARNING -> Style.WARNING
      }

    writer.line(
      buildList {
        add(Run("[${diagnostic.id.fullId}]", tagStyle))
        add(Run(" "))
        addAll(diagnostic.title.resolve(context))
      },
      indent = 0,
      hangingIndent = 4,
    )

    if (profile.renderSourceSnippets) {
      diagnostic.primarySpan?.let { span ->
        if (renderFrames(writer, listOf(span to span.label), context)) {
          // Primary span frames render before section content.
        }
      }
    }

    for (section in diagnostic.sections) {
      writer.blankLine()
      renderSection(writer, section, context)
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
  ) {
    when (section) {
      is DiagnosticSection.Chain -> renderChain(writer, section, context)
      is DiagnosticSection.BindingTrace -> renderBindingTrace(writer, section, context)
      is DiagnosticSection.Cycle -> renderCycle(writer, section, context)
      is DiagnosticSection.Locations -> renderLocations(writer, section, context)
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
  ) {
    section.header?.let { writer.line(it.resolve(context), SECTION_INDENT, SECTION_INDENT) }

    // Merge spans by file so related locations can share one source frame.
    val framed = mutableSetOf<LocatedItem>()
    if (profile.renderSourceSnippets) {
      section.items
        .filter { it.span != null }
        .groupBy { it.span!!.filePath }
        .forEach { (_, items) ->
          val spans = items.map { it.span!! to (it.span.label ?: it.description) }
          if (renderFrames(writer, spans, context)) {
            framed += items
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

  /**
   * Renders a miette-style source frame for [spans]. All spans must be in the same file.
   *
   * Example:
   * ```
   *     ╭─[ RepositoryImpl.kt:6:38 ]
   *   6 │ class RepositoryImpl(val api: Api, val dep: Dependency) : Repository
   *     │                                             ─────┬────
   *     │                                                  ╰── Dependency is injected here
   *     ╰─
   * ```
   *
   * Returns false without writing anything when the source file is unavailable.
   */
  private fun renderFrames(
    writer: LineWriter,
    spans: List<Pair<DiagnosticSpan, Text?>>,
    context: RenderContext,
  ): Boolean {
    val first = spans.first().first
    val lines = sourceLines(first.filePath) ?: return false
    val sorted = spans.sortedBy { it.first.line }
    val gutterWidth = sorted.maxOf { it.first.line }.toString().length
    val pad = " ".repeat(gutterWidth)
    val contentBudget = profile.width - SECTION_INDENT - gutterWidth - 3

    writer.blankLine()
    val displayPath = first.displayPath ?: first.filePath.substringAfterLast('/')
    writer.raw(
      "$pad ${glyphs.frameOpen} $displayPath:${first.line}:${first.column} ]",
      SECTION_INDENT,
      Style.DIM,
    )
    for ((span, label) in sorted) {
      val source = lines.getOrNull(span.line - 1) ?: continue
      val visible =
        if (source.length > contentBudget) source.take(contentBudget - 1) + "…" else source
      writer.rawRuns(
        listOf(
          Run("${span.line.toString().padStart(gutterWidth)} ${glyphs.vbar} ", Style.DIM),
          Run(visible),
        ),
        SECTION_INDENT,
      )
      val start = (span.column - 1).coerceIn(0, visible.length)
      val rawLength =
        if (span.endLine == span.line && span.endColumn > span.column) {
          span.endColumn - span.column
        } else {
          // Span continues past this line (or has no end): underline to the end of the line.
          source.trimEnd().length - start
        }
      val length = rawLength.coerceIn(1, (visible.length - start).coerceAtLeast(1))
      val underline =
        if (label == null) {
          glyphs.hline.repeat(length)
        } else {
          val tee = (length - 1) / 2
          glyphs.hline.repeat(tee) + glyphs.underlineTee + glyphs.hline.repeat(length - tee - 1)
        }
      writer.rawRuns(
        listOf(
          Run("$pad ${glyphs.vbar} ", Style.DIM),
          Run(" ".repeat(start)),
          Run(underline, Style.DIM),
        ),
        SECTION_INDENT,
      )
      if (label != null) {
        val elbowAt = start + (length - 1) / 2
        writer.rawRuns(
          buildList {
            add(Run("$pad ${glyphs.vbar} ", Style.DIM))
            add(Run(" ".repeat(elbowAt)))
            add(Run("${glyphs.labelElbow} ", Style.DIM))
            addAll(label.resolve(context))
          },
          SECTION_INDENT,
        )
      }
    }
    writer.raw("$pad ${glyphs.frameClose}", SECTION_INDENT, Style.DIM)
    return true
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
        NoteKind.HELP -> "help:" to Style.SUCCESS
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

  companion object {
    private const val SECTION_INDENT = 2
    private const val CONTENT_INDENT = 6
    private const val NESTED_INDENT = 8
  }
}

/** Resolves styled [Text] to layout [Run]s, deciding simple vs fully-qualified type renders. */
internal fun Text.resolve(context: RenderContext): List<Run> = spans.map { span ->
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
