// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.explanation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stable decision codes shared by frontend adapters, structured reports, and IDE presentation. */
@Serializable
public enum class BindingReason {
  @SerialName("selected_explicit") SELECTED_EXPLICIT,
  @SerialName("selected_generated") SELECTED_GENERATED,
  @SerialName("selected_multibinding") SELECTED_MULTIBINDING,
  @SerialName("selected_optional") SELECTED_OPTIONAL,
  @SerialName("selected_implicit") SELECTED_IMPLICIT,
  @SerialName("selected_parent") SELECTED_PARENT,
  @SerialName("assisted_target") ASSISTED_TARGET,
  @SerialName("conflict") CONFLICT,
  @SerialName("qualifier_mismatch") QUALIFIER_MISMATCH,
  @SerialName("earlier_optional") EARLIER_OPTIONAL,
  @SerialName("higher_precedence") HIGHER_PRECEDENCE,
  @SerialName("not_visible") NOT_VISIBLE,
  @SerialName("contribution_unavailable") CONTRIBUTION_UNAVAILABLE,
  @SerialName("overridden") OVERRIDDEN,
  @SerialName("private_to_graph") PRIVATE_TO_GRAPH,
  @SerialName("dynamic_replacement") DYNAMIC_REPLACEMENT,
  @SerialName("other_graph") OTHER_GRAPH,
  @SerialName("nearer_input") NEARER_INPUT,
  @SerialName("excluded") EXCLUDED,
  @SerialName("incompatible_scope") INCOMPATIBLE_SCOPE,
  @SerialName("contribution_scope") CONTRIBUTION_SCOPE,
  @SerialName("container_unavailable") CONTAINER_UNAVAILABLE,
  @SerialName("replaced") REPLACED,
  @SerialName("lower_priority") LOWER_PRIORITY,
  @SerialName("incompatible_map_value") INCOMPATIBLE_MAP_VALUE,
}
