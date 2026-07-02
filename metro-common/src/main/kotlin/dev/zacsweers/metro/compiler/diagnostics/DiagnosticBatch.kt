// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics

import dev.zacsweers.metro.compiler.diagnostics.render.RenderContext

/**
 * Prepares a set of structured diagnostics for rendering.
 *
 * This performs the report-level passes that need to see diagnostics together:
 * 1. Type-name disambiguation: simple names are used unless two distinct fqNames share one.
 * 2. Trace deduplication: repeated binding-trace tails are printed once, then referenced by later
 *    diagnostics in the same batch.
 */
public object DiagnosticBatch {

  public data class Prepared(val diagnostic: MetroDiagnostic, val renderContext: RenderContext)

  public fun prepare(diagnostics: List<MetroDiagnostic>): List<Prepared> {
    val deduped = dedupTraces(diagnostics)
    return deduped.map { Prepared(it, disambiguationContextFor(it)) }
  }

  private fun disambiguationContextFor(diagnostic: MetroDiagnostic): RenderContext {
    val bySimpleName = mutableMapOf<String, MutableSet<String>>()

    for (text in diagnostic.allTexts()) {
      for (span in text.typeSpans) {
        bySimpleName.getOrPut(span.simpleRender, ::mutableSetOf).add(span.fqName)
      }
    }

    val ambiguous = bySimpleName.values.filter { it.size > 1 }.flatMapTo(mutableSetOf()) { it }

    return if (ambiguous.isEmpty()) RenderContext.EMPTY else RenderContext(ambiguous)
  }

  private fun dedupTraces(diagnostics: List<MetroDiagnostic>): List<MetroDiagnostic> {
    // Map each trace tail to the first diagnostic subject that printed it.
    val seenTails = mutableMapOf<List<String>, Text>()
    return diagnostics.map { diagnostic ->
      val trace =
        diagnostic.sections.filterIsInstance<DiagnosticSection.BindingTrace>().singleOrNull()
          ?: return@map diagnostic

      // Keep short traces intact; the continuation line would not save much space.
      if (trace.entries.size < 3 || trace.continuation != null) {
        return@map diagnostic
      }

      val tail = buildList {
        add(trace.graphName)
        trace.entries.drop(1).mapTo(this) { entry ->
          "${entry.graphName}|${entry.key}|${entry.usage}|${entry.context}"
        }
      }

      val subject = diagnostic.subjectText()

      val original = seenTails[tail]
      if (original == null) {
        if (subject != null) {
          seenTails[tail] = subject
        }
        diagnostic
      } else {
        val truncated = trace.copy(entries = trace.entries.take(1), continuation = original)
        diagnostic.copy(sections = diagnostic.sections.map { if (it === trace) truncated else it })
      }
    }
  }

  /** The diagnostic's subject for cross-references: the first type mentioned in its title. */
  private fun MetroDiagnostic.subjectText(): Text? =
    title.typeSpans.firstOrNull()?.let { Text(listOf(it)) }

  private fun MetroDiagnostic.allTexts(): Sequence<Text> = sequence {
    yield(title)

    for (note in notes) {
      yield(note.text)
    }

    primarySpan?.label?.let { yield(it) }

    for (section in sections) {
      when (section) {
        is DiagnosticSection.Chain -> yieldAll(section.items)
        is DiagnosticSection.BindingTrace -> {
          for (entry in section.entries) {
            yield(entry.key)
            entry.context?.let { yield(it) }
          }
          section.continuation?.let { yield(it) }
        }
        is DiagnosticSection.Cycle -> yieldAll(section.nodes.map { it.name })
        is DiagnosticSection.Locations -> {
          for (item in section.items) {
            item.description?.let { yield(it) }
          }
          section.header?.let { yield(it) }
        }
        is DiagnosticSection.SimilarBindings -> yieldAll(section.items.map { it.key })
        is DiagnosticSection.CodeBlock -> {}
        is DiagnosticSection.Generic -> yield(section.text)
      }
    }
  }
}
