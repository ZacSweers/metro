// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import java.util.EnumSet
import java.util.Locale
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.test.directives.model.RegisteredDirectives
import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer

object MetroDirectives : SimpleDirectivesContainer() {
  val COMPILER_VERSION by stringDirective("Target kotlin compiler version, if any")
  // TODO eventually support multiple outputs
  val CUSTOM_TEST_DATA_PER_COMPILER_VERSION by
    directive("Generate custom test data files per compiler version")
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
  val MAX_IR_ERRORS_COUNT by
    valueDirective(
      "Maximum number of errors to report before exiting IR processing. Default is 20, must be > 0."
    ) {
      it.toInt()
    }
  val OPTIONAL_DEPENDENCY_BEHAVIOR by
    enumDirective<OptionalBindingBehavior>(
      "Controls the behavior of optional dependencies on a per-compilation basis."
    )
  val INTEROP_ANNOTATIONS_NAMED_ARG_SEVERITY by
    enumDirective<MetroOptions.DiagnosticSeverity>(
      "Control diagnostic severity reporting of interop annotations using positional arguments instead of named arguments."
    )
  val CONTRIBUTES_AS_INJECT by
    directive(
      "If enabled, treats `@Contributes*` annotations (except ContributesTo) as implicit `@Inject` annotations."
    )

  // Dependency directives.
  val WITH_ANVIL by directive("Add Anvil as dependency and configure custom annotations.")
  val WITH_KI_ANVIL by
    directive("Add kotlin-inject-nnvil as dependency and configure custom annotations.")
  val WITH_DAGGER by directive("Add Dagger as dependency and configure custom annotations.")
  val ENABLE_DAGGER_INTEROP by
    directive("Enable Dagger interop. This implicitly applies WITH_DAGGER directive as well.")
  val ENABLE_DAGGER_KSP by
    directive(
      "Enable Dagger KSP processing. This implicitly applies WITH_DAGGER and ENABLE_DAGGER_INTEROP directives as well."
    )
  val ENABLE_ANVIL_KSP by
    directive(
      "Enable Anvil KSP processing. This implicitly applies WITH_DAGGER, ENABLE_DAGGER_INTEROP, and WITH_ANVIL directives as well."
    )

  // Anvil KSP options
  val ANVIL_GENERATE_DAGGER_FACTORIES by
    valueDirective("Enable/disable generation of Dagger factories in Anvil KSP.") { it.toBoolean() }
  val ANVIL_GENERATE_DAGGER_FACTORIES_ONLY by
    valueDirective(
      "Enable/disable generating only Dagger factories in Anvil KSP, skip component merging. Default is true."
    ) {
      it.toBoolean()
    }
  val ANVIL_DISABLE_COMPONENT_MERGING by
    valueDirective("Enable/disable component merging in Anvil KSP.") { it.toBoolean() }
  val ANVIL_EXTRA_CONTRIBUTING_ANNOTATIONS by
    stringDirective(
      "Colon-separated list of extra contributing annotations for Anvil KSP. Example: 'com.example.MyAnnotation:com.example.OtherAnnotation'."
    )
  val KSP_LOG_SEVERITY by
    valueDirective("KSP logging directive.") { value ->
      when (val upper = value.uppercase(Locale.US)) {
        "VERBOSE" ->
          EnumSet.range(CompilerMessageSeverity.EXCEPTION, CompilerMessageSeverity.LOGGING)
        else -> EnumSet.of(CompilerMessageSeverity.valueOf(upper))
      }
    }

  fun enableDaggerRuntime(directives: RegisteredDirectives): Boolean {
    return WITH_DAGGER in directives ||
      ENABLE_DAGGER_INTEROP in directives ||
      ENABLE_DAGGER_KSP in directives ||
      ENABLE_ANVIL_KSP in directives
  }

  fun enableDaggerRuntimeInterop(directives: RegisteredDirectives): Boolean {
    return ENABLE_DAGGER_INTEROP in directives ||
      ENABLE_DAGGER_KSP in directives ||
      ENABLE_ANVIL_KSP in directives
  }

  fun enableDaggerKsp(directives: RegisteredDirectives): Boolean {
    return ENABLE_DAGGER_KSP in directives
  }

  fun enableAnvilKsp(directives: RegisteredDirectives): Boolean {
    return ENABLE_ANVIL_KSP in directives
  }
}
