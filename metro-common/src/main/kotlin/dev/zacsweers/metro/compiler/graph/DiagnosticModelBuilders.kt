// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.diagnostics.DiagnosticSection
import dev.zacsweers.metro.compiler.diagnostics.Text
import dev.zacsweers.metro.compiler.diagnostics.TraceEntry
import dev.zacsweers.metro.compiler.diagnostics.textOf
import org.jetbrains.kotlin.name.FqName

/**
 * Adapts binding-graph data into structured diagnostic sections.
 *
 * These helpers stay in the graph package so generic graph validation and IR-specific reporting use
 * the same type-key, trace, and chain rendering inputs.
 */
public fun BaseTypeKey<*, *, *>.toTypeSpan(): Text.Span.Type =
  Text.Span.Type(
    fqName = render(short = false),
    simpleRender = render(short = true),
    fqRender = render(short = false),
  )

public fun BaseTypeKey<*, *, *>.toText(): Text = Text(listOf(toTypeSpan()))

public fun BaseContextualTypeKey<*, *, *>.toTypeSpan(): Text.Span.Type =
  Text.Span.Type(
    fqName = render(short = false),
    simpleRender = render(short = true),
    fqRender = render(short = false),
  )

public fun BaseContextualTypeKey<*, *, *>.toText(): Text = Text(listOf(toTypeSpan()))

public fun BaseBindingStack.BaseEntry<*, *, *>.toTraceEntry(): TraceEntry =
  TraceEntry(
    key = displayTypeKey.toText(),
    usage = usage,
    context = graphContext?.let(::textOf),
  )

/** Converts a non-root binding stack to a trace section, or null when there is nothing to show. */
public fun BaseBindingStack<*, *, *, *, *>.toTraceSection(): DiagnosticSection.BindingTrace? {
  if (graphFqName == FqName.ROOT || entries.isEmpty()) return null
  return DiagnosticSection.BindingTrace(
    graphName = graphFqName.asString(),
    entries = entries.map { it.toTraceEntry() },
  )
}

/**
 * Builds the compact dependency path rendered under a missing-binding headline.
 *
 * The chain is a quick summary, for example `AppGraph.repo -> RepositoryImpl -> Dependency`. The
 * detailed trace section still carries the full binding context. Returns null when the stack is too
 * shallow for the summary to add useful signal.
 */
public fun BaseBindingStack<*, *, *, *, *>.toChainSection(): DiagnosticSection.Chain? {
  if (entries.size < 2) return null
  // entries[0] is innermost, usually the missing type. Render the chain from the graph outward.
  val outermostFirst = entries.asReversed()
  val items = buildList {
    // The outermost entry is usually the graph accessor. Its context, such as `AppGraph.repo`, is a
    // better chain root than the accessor return type.
    val outermost = outermostFirst.first()
    if (outermost.isSynthetic && outermost.graphContext != null) {
      add(textOf(outermost.graphContext!!))
    }
    for (entry in outermostFirst) {
      add(entry.displayTypeKey.toText())
    }
  }
  return if (items.size < 2) null else DiagnosticSection.Chain(items)
}
