// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer

object MetroDirectives : SimpleDirectivesContainer() {
  val GENERATE_ASSISTED_FACTORIES by directive("Enable assisted factories generation.")
  val DISABLE_TRANSFORM_PROVIDERS_TO_PRIVATE by
    directive("Disables automatic transformation of providers to be private.")
  val PUBLIC_PROVIDER_SEVERITY by
    enumDirective<MetroOptions.DiagnosticSeverity>(
      "Control diagnostic severity reporting of public providers."
    )

  // Dependency directives.
  val WITH_ANVIL by directive("Add Anvil as dependency and configure custom annotations.")
  val WITH_DAGGER by directive("Add Dagger as dependency and configure custom annotations.")
}
