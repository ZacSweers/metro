// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.explanation

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class BindingExplanationTest {
  @Test
  fun `snapshots round trip with decision codes and request optionality`() {
    val declaration =
      BindingDeclaration(
        "provider",
        "Providers.service",
        BindingSourceLocation("Providers.kt", 7, 3),
      )
    val snapshot =
      BindingExplanation(
        context = BindingExplanationContext("root-child", "Root -> Child"),
        phase = BindingExplanationPhase.LOOKUP,
        outcome = BindingExplanationOutcome.SELECTED,
        request =
          BindingExplanationRequest(
            "@Named(\"app\") Service",
            hasDefault = true,
            isOptional = true,
          ),
        candidates =
          listOf(
            BindingExplanationCandidate(
              id = "selected",
              key = "@Named(\"app\") Service",
              status = BindingCandidateStatus.SELECTED,
              reason = BindingReason.SELECTED_EXPLICIT,
              declaration = declaration,
              ownerGraphId = "root",
            ),
            BindingExplanationCandidate(
              id = "alternative",
              key = "Service",
              status = BindingCandidateStatus.REJECTED,
              reason = BindingReason.QUALIFIER_MISMATCH,
              relatedDeclarations = listOf(declaration),
              details = listOf("Observed in the source graph."),
            ),
          ),
      )

    val encoded = Json.encodeToString(snapshot)
    assertThat(Json.decodeFromString<BindingExplanation>(encoded)).isEqualTo(snapshot)
    assertThat(encoded).contains("\"reason\":\"qualifier_mismatch\"")
    val rendered = BindingExplanationRenderer.render(snapshot)
    assertThat(rendered).contains("This request allows an absent binding.")
    assertThat(rendered).contains("This request has a default value.")
    assertThat(rendered).contains("Graph: Root -> Child")
    assertThat(rendered).contains("The binding has a different qualifier.")
    assertThat(rendered).contains("Related: Providers.service (Providers.kt:7)")
    assertThat(rendered).contains("Observed in the source graph.")
  }

  @Test
  fun `filtering observations do not claim a lookup result`() {
    val snapshot =
      BindingExplanation(
        context = BindingExplanationContext("graph", "AppGraph"),
        phase = BindingExplanationPhase.CANDIDATE_FILTERING,
        outcome = BindingExplanationOutcome.FILTERED,
        candidates =
          listOf(
            BindingExplanationCandidate(
              "old",
              "Service",
              BindingCandidateStatus.REJECTED,
              BindingReason.REPLACED,
            )
          ),
        details = listOf("Only decisions observed during registration are included."),
      )

    val rendered = BindingExplanationRenderer.render(snapshot)
    assertThat(rendered).doesNotContain("Request:")
    assertThat(rendered).contains("Candidate filtering completed before dependency lookup.")
    assertThat(rendered).contains("Another contribution replaces this declaration.")
    assertThat(rendered).contains("Only decisions observed during registration are included.")
  }

  @Test
  fun `minimal snapshots decode without optional fields`() {
    val snapshot =
      Json.decodeFromString<BindingExplanation>(
        """{"context":{"id":"graph","label":"AppGraph"},"phase":"lookup","outcome":"missing","candidates":[],"request":{"key":"Service"}}"""
      )

    val request = checkNotNull(snapshot.request)
    assertThat(request.hasDefault).isFalse()
    assertThat(request.isOptional).isFalse()
    assertThat(BindingExplanationRenderer.render(snapshot)).contains("No binding was selected.")
  }

  @Test
  fun `identity components remain distinct when they contain separators`() {
    assertThat(bindingExplanationId("graph|child", "request"))
      .isNotEqualTo(bindingExplanationId("graph", "child|request"))
  }
}
