// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.test.directives.model.RegisteredDirectives
import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer

object MetroDirectives : SimpleDirectivesContainer() {
  val GENERATE_ASSISTED_FACTORIES by directive("Enable assisted factories generation.")
  val ENABLE_TOP_LEVEL_FUNCTION_INJECTION by directive("Enable top-level function injection.")
  val DISABLE_TRANSFORM_PROVIDERS_TO_PRIVATE by
    directive("Disables automatic transformation of providers to be private.")
  val GENERATE_JVM_CONTRIBUTION_HINTS_IN_FIR by
    directive(
      "Enable/disable generation of contribution hint generation in FIR for JVM compilations types."
    )
  val PUBLIC_PROVIDER_SEVERITY by
    enumDirective<MetroOptions.DiagnosticSeverity>(
      "Control diagnostic severity reporting of public providers."
    )
  val ASSISTED_INJECT_MIGRATION_SEVERITY by
    enumDirective<MetroOptions.DiagnosticSeverity>(
      "Control diagnostic severity reporting of assisted inject migration issues."
    )
  val SHRINK_UNUSED_BINDINGS by
    valueDirective("Enable/disable shrinking of unused bindings.") { it.toBoolean() }
  val CHUNK_FIELD_INITS by
    valueDirective("Enable/disable chunking of field initializers.") { it.toBoolean() }
  val ENABLE_FULL_BINDING_GRAPH_VALIDATION by
    directive(
      "Enable/disable full binding graph validation of binds and provides declarations even if they are unused."
    )
  val ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE by
    directive(
      "If true changes the return type of generated Graph Factories from the declared interface type to the generated Metro graph type. This is helpful for Dagger/Anvil interop."
    )

  // Dependency directives.
  val WITH_ANVIL by directive("Add Anvil as dependency and configure custom annotations.")
  val WITH_KI_ANVIL by directive("Add kotlin-inject-nnvil as dependency and configure custom annotations.")
  val WITH_DAGGER by directive("Add Dagger as dependency and configure custom annotations.")
  val ENABLE_DAGGER_INTEROP by
    directive("Enable Dagger interop. This implicitly applies WITH_DAGGER directive as well.")
  val ENABLE_DAGGER_KSP by
    directive(
      "Enable Dagger KSP processing. This implicitly applies WITH_DAGGER and ENABLE_DAGGER_INTEROP directives as well."
    )

  fun enableDaggerRuntime(directives: RegisteredDirectives): Boolean {
    return WITH_DAGGER in directives ||
      ENABLE_DAGGER_INTEROP in directives ||
      ENABLE_DAGGER_KSP in directives
  }

  fun enableDaggerRuntimeInterop(directives: RegisteredDirectives): Boolean {
    return ENABLE_DAGGER_INTEROP in directives || ENABLE_DAGGER_KSP in directives
  }

  fun enableDaggerKsp(directives: RegisteredDirectives): Boolean {
    return ENABLE_DAGGER_KSP in directives
  }
}
