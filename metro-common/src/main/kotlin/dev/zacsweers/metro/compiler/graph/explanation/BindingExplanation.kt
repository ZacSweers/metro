// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.explanation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Captured decisions for one concrete graph context, independent of frontend objects and renderers.
 */
@Serializable
public data class BindingExplanation(
  val context: BindingExplanationContext,
  val phase: BindingExplanationPhase,
  val outcome: BindingExplanationOutcome,
  val candidates: List<BindingExplanationCandidate>,
  /** Filtering can happen before any dependency requests the candidates. */
  val request: BindingExplanationRequest? = null,
  /** Adapter-specific limits on the observed decisions, such as unavailable compiler phases. */
  val details: List<String> = emptyList(),
)

/**
 * [id] includes the concrete parent chain and dynamic caller when either changes graph membership.
 */
@Serializable public data class BindingExplanationContext(val id: String, val label: String)

/** The complete requested key, including wrappers, with optionality retained separately. */
@Serializable
public data class BindingExplanationRequest(
  val key: String,
  val declaration: BindingDeclaration? = null,
  val hasDefault: Boolean = false,
  val isOptional: Boolean = false,
)

/**
 * One observed candidate and its decision. [id] identifies it within the captured graph context.
 */
@Serializable
public data class BindingExplanationCandidate(
  val id: String,
  val key: String,
  val status: BindingCandidateStatus,
  val reason: BindingReason,
  val declaration: BindingDeclaration? = null,
  val ownerGraphId: String? = null,
  /**
   * Declarations responsible for the decision, such as a replacement or a higher-priority binding.
   */
  val relatedDeclarations: List<BindingDeclaration> = emptyList(),
  val details: List<String> = emptyList(),
)

/**
 * A source or generated declaration. IDs use stable names and relative locations when available.
 */
@Serializable
public data class BindingDeclaration(
  val id: String,
  val label: String,
  val source: BindingSourceLocation? = null,
)

/** Source positions are one-based; paths are relative or display paths chosen by the adapter. */
@Serializable
public data class BindingSourceLocation(
  val path: String,
  val line: Int? = null,
  val column: Int? = null,
)

/** Distinguishes requested lookups from registration and filtering before a request was made. */
@Serializable
public enum class BindingExplanationPhase {
  @SerialName("lookup") LOOKUP,
  @SerialName("registration") REGISTRATION,
  @SerialName("candidate_filtering") CANDIDATE_FILTERING,
}

/** The result of the observed phase. [FILTERED] does not assert that a request can be resolved. */
@Serializable
public enum class BindingExplanationOutcome {
  @SerialName("selected") SELECTED,
  @SerialName("missing") MISSING,
  @SerialName("conflict") CONFLICT,
  @SerialName("invalid_request") INVALID_REQUEST,
  @SerialName("filtered") FILTERED,
}

/** Conflicting candidates remain selected by lookup and require a duplicate-binding diagnostic. */
@Serializable
public enum class BindingCandidateStatus {
  @SerialName("selected") SELECTED,
  @SerialName("rejected") REJECTED,
  @SerialName("conflict") CONFLICT,
}

/** Builds an opaque deterministic ID without ambiguous separators or process-local identities. */
public fun bindingExplanationId(vararg parts: String): String =
  parts.joinToString("|") { "${it.length}:$it" }
