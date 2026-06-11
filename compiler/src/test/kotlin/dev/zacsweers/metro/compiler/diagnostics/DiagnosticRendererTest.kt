// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.diagnostics

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.diagnostics.render.AnsiCodes
import dev.zacsweers.metro.compiler.diagnostics.render.DiagnosticRenderer
import dev.zacsweers.metro.compiler.diagnostics.render.RenderContext
import dev.zacsweers.metro.compiler.diagnostics.render.RenderProfile
import org.junit.Test

class DiagnosticRendererTest {

  private val plain = DiagnosticRenderer(RenderProfile.PLAIN)
  private val rich = DiagnosticRenderer(RenderProfile.RICH)

  private fun missingBinding(): MetroDiagnostic =
    MetroDiagnostic(
      id = MetroDiagnosticId.MISSING_BINDING,
      severity = MetroSeverity.ERROR,
      title =
        buildText {
          append("No binding found for ")
          appendType("test.Dependency")
        },
      sections =
        listOf(
          DiagnosticSection.Chain(
            listOf(
              textOf("AppGraph.repo"),
              buildText { appendType("test.RepositoryImpl") },
              buildText { appendType("test.Dependency") },
            )
          ),
          DiagnosticSection.BindingTrace(
            graphName = "AppGraph",
            entries =
              listOf(
                TraceEntry(
                  key = buildText { appendType("test.Dependency") },
                  usage = "is injected at",
                  context = textOf("RepositoryImpl(…, dep)"),
                ),
                TraceEntry(
                  key = buildText { appendType("test.RepositoryImpl") },
                  usage = "is requested at",
                  context = textOf("AppGraph.repo"),
                ),
              ),
          ),
          DiagnosticSection.SimilarBindings(
            listOf(
              SimilarBindingItem(
                key =
                  buildText {
                    append("@Named(\"prod\") ")
                    appendType("test.Dependency")
                  },
                description = "same type, different qualifier",
                location = "Bindings.kt:14:3",
              )
            )
          ),
        ),
      notes =
        listOf(
          Note.help(
            buildText {
              append("add an @Inject constructor to ")
              appendType("test.Dependency")
              append(" or a @Provides function to ")
              appendType("test.AppGraph")
            }
          )
        ),
    )

  @Test
  fun `plain missing binding renders full layout`() {
    assertThat(plain.render(missingBinding()))
      .isEqualTo(
        """
        [Metro/MissingBinding] No binding found for Dependency

          AppGraph.repo -> RepositoryImpl -> Dependency

          trace (in AppGraph):
              Dependency is injected at RepositoryImpl(…, dep)
              RepositoryImpl is requested at AppGraph.repo

          similar bindings:
              - @Named("prod") Dependency (same type, different qualifier) - Bindings.kt:14:3

          help: add an @Inject constructor to Dependency or a @Provides function to AppGraph
          docs: https://zacsweers.github.io/metro/latest/diagnostics/#missingbinding
        """
          .trimIndent()
      )
  }

  @Test
  fun `plain output contains no ansi codes`() {
    val rendered = plain.render(missingBinding())
    assertThat(rendered).isEqualTo(AnsiCodes.strip(rendered))
  }

  @Test
  fun `rich output styles the code tag and strips back to plain layout`() {
    val rendered = rich.render(missingBinding())
    assertThat(rendered).contains("${AnsiCodes.RED}[Metro/MissingBinding]${AnsiCodes.RESET}")
    assertThat(rendered).contains("${AnsiCodes.BOLD}Dependency${AnsiCodes.RESET}")
    // Same layout as plain modulo glyphs: stripping ANSI yields identical structure.
    val stripped = AnsiCodes.strip(rendered)
    assertThat(stripped).contains("AppGraph.repo → RepositoryImpl → Dependency")
    assertThat(stripped.lines().map { it.trimEnd() })
      .containsExactlyElementsIn(
        plain
          .render(missingBinding())
          .replace(" -> ", " → ")
          .replace(" - Bindings", " — Bindings")
          .replace("- @Named", "• @Named")
          .lines()
          .map { it.trimEnd() }
      )
      .inOrder()
  }

  @Test
  fun `warning severity uses warning tag`() {
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.SUSPICIOUS_UNUSED_MULTIBINDING,
        severity = MetroSeverity.WARNING,
        title = textOf("Unused multibinding"),
        includeDocsUrl = false,
      )
    assertThat(rich.render(diagnostic)).startsWith(AnsiCodes.YELLOW)
    assertThat(plain.render(diagnostic))
      .isEqualTo("[Metro/SuspiciousUnusedMultibinding] Unused multibinding")
  }

  @Test
  fun `notes wrap at 100 columns with hanging indent`() {
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.GENERIC,
        severity = MetroSeverity.ERROR,
        title = textOf("Title"),
        notes = listOf(Note.help("word ".repeat(40).trim())),
        includeDocsUrl = false,
      )
    val rendered = plain.render(diagnostic)
    val lines = rendered.lines()
    assertThat(lines.all { it.length <= 100 }).isTrue()
    val helpLines = lines.filter { it.contains("word") }
    assertThat(helpLines.size).isGreaterThan(1)
    assertThat(helpLines[0]).startsWith("  help: word")
    // Continuations align under the text, past the "help: " label.
    assertThat(helpLines[1]).startsWith("        word")
  }

  @Test
  fun `chains wrap at arrows keeping each step intact`() {
    val items = (1..12).map { textOf("SomeReasonablyLongTypeName$it") }
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.MISSING_BINDING,
        severity = MetroSeverity.ERROR,
        title = textOf("Title"),
        sections = listOf(DiagnosticSection.Chain(items)),
        includeDocsUrl = false,
      )
    val rendered = plain.render(diagnostic)
    val chainLines = rendered.lines().filter { it.contains("SomeReasonablyLongTypeName") }
    assertThat(chainLines.size).isGreaterThan(1)
    assertThat(chainLines.all { it.length <= 100 }).isTrue()
    // No step is split: every name is immediately preceded by start-of-chain or an arrow.
    for (line in chainLines.drop(1)) {
      assertThat(line.trimStart()).startsWith("-> ")
    }
  }

  @Test
  fun `identifiers longer than the budget overflow rather than truncate`() {
    val longName = "Very".repeat(40) + "LongType"
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.GENERIC,
        severity = MetroSeverity.ERROR,
        title = textOf("Title"),
        sections = listOf(DiagnosticSection.Generic(buildText { appendType("test.$longName") })),
        includeDocsUrl = false,
      )
    assertThat(plain.render(diagnostic)).contains(longName)
  }

  @Test
  fun `horizontal cycle closes the loop`() {
    val diagnostic = cycleDiagnostic()
    assertThat(plain.render(diagnostic))
      .isEqualTo(
        """
        [Metro/DependencyCycle] Found a dependency cycle

          cycle:
              +-> B -> A ~~> FakeA --+
              +----------------------+
        """
          .trimIndent()
      )
  }

  @Test
  fun `horizontal cycle uses unicode glyphs in rich mode`() {
    val stripped = AnsiCodes.strip(rich.render(cycleDiagnostic()))
    assertThat(stripped).contains("╭─▶ B → A ┄▶ FakeA ─╮")
    val top = stripped.lines().first { it.contains("╭─▶") }
    val bottom = stripped.lines().first { it.contains("╰") }
    assertThat(bottom.trimEnd().length).isEqualTo(top.trimEnd().length)
  }

  @Test
  fun `long cycles fall back to vertical layout`() {
    val nodes = (1..10).map { CycleNode(textOf("SomeVeryLongCycleParticipant$it")) }
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.DEPENDENCY_CYCLE,
        severity = MetroSeverity.ERROR,
        title = textOf("Found a dependency cycle"),
        sections = listOf(DiagnosticSection.Cycle(nodes)),
        includeDocsUrl = false,
      )
    val rendered = plain.render(diagnostic)
    assertThat(rendered).contains("+-> SomeVeryLongCycleParticipant1")
    assertThat(rendered).contains("|   -> SomeVeryLongCycleParticipant2")
    assertThat(rendered).contains("+-- back to SomeVeryLongCycleParticipant1")
  }

  @Test
  fun `types fully qualify only when marked ambiguous`() {
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.DUPLICATE_BINDING,
        severity = MetroSeverity.ERROR,
        title =
          buildText {
            append("Multiple bindings for ")
            appendType("com.foo.Thing")
            append(" and ")
            appendType("com.bar.Thing")
          },
        includeDocsUrl = false,
      )
    assertThat(plain.render(diagnostic)).contains("Multiple bindings for Thing and Thing")
    val disambiguated =
      plain.render(
        diagnostic,
        RenderContext(fullyQualifiedTypeNames = setOf("com.foo.Thing", "com.bar.Thing")),
      )
    assertThat(disambiguated).contains("Multiple bindings for com.foo.Thing and com.bar.Thing")
  }

  @Test
  fun `locations render code blocks under location lines`() {
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.DUPLICATE_BINDING,
        severity = MetroSeverity.ERROR,
        title =
          buildText {
            append("Multiple bindings found for ")
            appendType("kotlin.String")
          },
        sections =
          listOf(
            DiagnosticSection.Locations(
              header = null,
              items =
                listOf(
                  LocatedItem(
                    location = "Providers.kt:10:13",
                    code =
                      "@Provides fun provideString1(): String\n" +
                        "                                ~~~~~~",
                  ),
                  LocatedItem(
                    location = "Providers.kt:11:13",
                    code =
                      "@Provides fun provideString2(): String\n" +
                        "                                ~~~~~~",
                  ),
                ),
            )
          ),
        includeDocsUrl = false,
      )
    assertThat(plain.render(diagnostic))
      .isEqualTo(
        """
        [Metro/DuplicateBinding] Multiple bindings found for String

              Providers.kt:10:13
                @Provides fun provideString1(): String
                                                ~~~~~~

              Providers.kt:11:13
                @Provides fun provideString2(): String
                                                ~~~~~~
        """
          .trimIndent()
      )
  }

  @Test
  fun `deduped trace renders continuation pointer to sibling`() {
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.MISSING_BINDING,
        severity = MetroSeverity.ERROR,
        title = textOf("No binding found for Foo2"),
        sections =
          listOf(
            DiagnosticSection.BindingTrace(
              graphName = "AppGraph",
              entries =
                listOf(
                  TraceEntry(
                    key = buildText { appendType("test.Foo2") },
                    usage = "is injected at",
                    context = textOf("InjectedThing(…, foo2)"),
                  )
                ),
              continuation = buildText { appendType("test.Foo1") },
            )
          ),
        includeDocsUrl = false,
      )
    assertThat(plain.render(diagnostic))
      .isEqualTo(
        """
        [Metro/MissingBinding] No binding found for Foo2

          trace (in AppGraph):
              Foo2 is injected at InjectedThing(…, foo2)
              … same as for Foo1
        """
          .trimIndent()
      )
  }

  @Test
  fun `code spans render with backticks in both modes`() {
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.GENERIC,
        severity = MetroSeverity.ERROR,
        title =
          buildText {
            append("Use ")
            appendCode("Provider<Foo>")
          },
        includeDocsUrl = false,
      )
    assertThat(plain.render(diagnostic)).contains("Use `Provider<Foo>`")
    assertThat(AnsiCodes.strip(rich.render(diagnostic))).contains("Use `Provider<Foo>`")
  }

  @Test
  fun `rich mode renders labeled source frames and falls back when source is unreadable`() {
    val source =
      listOf(
        "interface Repository",
        "class RepositoryImpl(val dep: Dependency) : Repository",
      )
    val withSource =
      DiagnosticRenderer(
        RenderProfile.RICH,
        sourceLines = { path ->
          if (path == "/src/RepositoryImpl.kt") source else null
        },
      )
    val diagnostic =
      MetroDiagnostic(
        id = MetroDiagnosticId.MISSING_BINDING,
        severity = MetroSeverity.ERROR,
        title = textOf("No binding found for Dependency"),
        primarySpan =
          DiagnosticSpan(
            filePath = "/src/RepositoryImpl.kt",
            line = 2,
            column = 22,
            endLine = 2,
            endColumn = 41,
            label = textOf("injected here"),
            displayPath = "RepositoryImpl.kt",
          ),
        includeDocsUrl = false,
      )

    val rendered = AnsiCodes.strip(withSource.render(diagnostic))
    assertThat(rendered)
      .isEqualTo(
        """
        [Metro/MissingBinding] No binding found for Dependency

            ╭─[ RepositoryImpl.kt:2:22 ]
          2 │ class RepositoryImpl(val dep: Dependency) : Repository
            │                      ─────────┬─────────
            │                               ╰── injected here
            ╰─
        """
          .trimIndent()
      )

    // Unreadable source: the frame is silently skipped.
    val withoutSource = DiagnosticRenderer(RenderProfile.RICH)
    assertThat(AnsiCodes.strip(withoutSource.render(diagnostic)))
      .isEqualTo("[Metro/MissingBinding] No binding found for Dependency")

    // PLAIN ignores spans entirely.
    assertThat(plain.render(diagnostic))
      .isEqualTo("[Metro/MissingBinding] No binding found for Dependency")
  }

  private fun cycleDiagnostic() =
    MetroDiagnostic(
      id = MetroDiagnosticId.DEPENDENCY_CYCLE,
      severity = MetroSeverity.ERROR,
      title = textOf("Found a dependency cycle"),
      sections =
        listOf(
          DiagnosticSection.Cycle(
            listOf(
              CycleNode(textOf("B")),
              CycleNode(textOf("A"), aliasEdgeToNext = true),
              CycleNode(textOf("FakeA")),
            )
          )
        ),
      includeDocsUrl = false,
    )
}
