// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics

/**
 * A structured Metro diagnostic.
 *
 * This is the renderer-independent model for one problem Metro wants to report. Detection code
 * builds the model; the configured console renderer renders it later.
 */
internal data class MetroDiagnostic(
  val id: MetroDiagnosticId,
  val severity: MetroSeverity,
  /** One-line headline. Rendered after the `[Metro/...]` tag; kotlinc prepends its own severity. */
  val title: Text,
  /** The source location this diagnostic is anchored to, when known. */
  val primarySpan: DiagnosticSpan? = null,
  val sections: List<DiagnosticSection> = emptyList(),
  val notes: List<Note> = emptyList(),
  /** When true, renderers append a `docs:` line pointing at [MetroDiagnosticId.docsUrl]. */
  val includeDocsUrl: Boolean = true,
)

internal enum class MetroSeverity {
  ERROR,
  WARNING,
}

/**
 * A trailing annotation on a diagnostic.
 *
 * [NoteKind.HELP] is an actionable suggestion, while [NoteKind.NOTE] is supporting context.
 */
internal data class Note(val kind: NoteKind, val text: Text) {
  companion object {
    fun help(text: Text): Note = Note(NoteKind.HELP, text)

    fun help(text: String): Note = help(textOf(text))

    fun note(text: Text): Note = Note(NoteKind.NOTE, text)

    fun note(text: String): Note = note(textOf(text))
  }
}

internal enum class NoteKind {
  HELP,
  NOTE,
}

/** Builds the invalid-assisted-binding diagnostic shared by FIR and IR validation. */
internal fun invalidAssistedBindingDiagnostic(
  assistedType: Text,
  injectionSite: Text?,
  assistedFactory: Text?,
): MetroDiagnostic {
  val notes = buildList {
    add(Note.help("inject a corresponding @AssistedFactory type instead"))
    if (assistedFactory != null) {
      add(
        Note.note(
          buildText {
            append("it looks like the @AssistedFactory for ")
            append(assistedType)
            append(" is ")
            append(assistedFactory)
          }
        )
      )
    }
  }
  return MetroDiagnostic(
    id = MetroDiagnosticId.INVALID_BINDING,
    severity = MetroSeverity.ERROR,
    title =
      buildText {
        append(assistedType)
        append(" uses assisted injection and cannot be injected directly")
        if (injectionSite == null) {
          append(" here")
        } else {
          append(" into ")
          append(injectionSite)
        }
      },
    notes = notes,
  )
}

/**
 * A resolved source location. Lines and columns are 1-based.
 *
 * [filePath] is used to read source text. [displayPath], when present, is the shorter path shown in
 * rendered output. [label] annotates the span in source-frame profiles.
 */
internal data class DiagnosticSpan(
  val filePath: String,
  val line: Int,
  val column: Int,
  val endLine: Int = line,
  val endColumn: Int = column,
  val label: Text? = null,
  val displayPath: String? = null,
)
