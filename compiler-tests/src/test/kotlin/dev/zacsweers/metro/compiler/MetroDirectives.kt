// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer

object MetroDirectives : SimpleDirectivesContainer() {
  val GENERATE_ASSISTED_FACTORIES by directive("Enable assisted factories generation.")
  val PUBLIC_PROVIDER_SEVERITY by
    enumDirective<MetroOptions.DiagnosticSeverity>("Enable assisted factories generation.")

  // Dependency directives.
  val WITH_ANVIL by directive("Add Anvil as dependency and configure custom annotations.")
}
