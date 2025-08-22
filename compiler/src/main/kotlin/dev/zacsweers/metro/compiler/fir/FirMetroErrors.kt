// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.error0
import dev.zacsweers.metro.compiler.error1
import dev.zacsweers.metro.compiler.error2
import dev.zacsweers.metro.compiler.warning0
import dev.zacsweers.metro.compiler.warning1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticRenderers.TO_STRING
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.DECLARATION_RETURN_TYPE
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.MODALITY_MODIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.NAME_IDENTIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.OVERRIDE_MODIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.PARAMETER_VARARG_MODIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.TYPE_PARAMETERS_LIST
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.VISIBILITY_MODIFIER
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers.STRING
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory

internal object FirMetroErrors : BaseDiagnosticRendererFactory() {

  // Common
  val FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION by error2<String, String>(NAME_IDENTIFIER)
  val METRO_DECLARATION_ERROR by error1<String>(NAME_IDENTIFIER)
  val METRO_DECLARATION_VISIBILITY_ERROR by error2<String, String>(VISIBILITY_MODIFIER)
  val METRO_TYPE_PARAMETERS_ERROR by error1<String>(TYPE_PARAMETERS_LIST)

  // DependencyGraph factory errors
  val GRAPH_CREATORS_ERROR by error1<String>(NAME_IDENTIFIER)
  val GRAPH_CREATORS_VARARG_ERROR by error1<String>(PARAMETER_VARARG_MODIFIER)

  // DependencyGraph errors
  val DEPENDENCY_GRAPH_ERROR by error1<String>(NAME_IDENTIFIER)

  // Inject constructor errors
  val SUGGEST_CLASS_INJECTION by warning0(NAME_IDENTIFIER)

  // Inject/assisted constructor errors
  val CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS by error0(NAME_IDENTIFIER)
  val CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS by error0(NAME_IDENTIFIER)
  val ASSISTED_FACTORIES_CANNOT_BE_LAZY by error2<String, String>(NAME_IDENTIFIER)
  val ONLY_CLASSES_CAN_BE_INJECTED by error0(NAME_IDENTIFIER)
  val ONLY_FINAL_AND_OPEN_CLASSES_CAN_BE_INJECTED by error0(MODALITY_MODIFIER)
  val LOCAL_CLASSES_CANNOT_BE_INJECTED by error0(NAME_IDENTIFIER)
  val INJECTED_CLASSES_MUST_BE_VISIBLE by error1<String>(VISIBILITY_MODIFIER)

  // Assisted factory/inject errors
  val ASSISTED_INJECTION_ERROR by error1<String>(NAME_IDENTIFIER)

  // Provides errors
  val PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_ERROR by error1<String>(VISIBILITY_MODIFIER)
  val PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING by warning1<String>(VISIBILITY_MODIFIER)
  val PROVIDES_PROPERTIES_CANNOT_BE_PRIVATE by error1<String>(VISIBILITY_MODIFIER)
  // TODO make this severity configurable
  val PROVIDES_COULD_BE_BINDS by warning1<String>(NAME_IDENTIFIER)
  val PROVIDER_OVERRIDES by error0(MODALITY_MODIFIER)
  val PROVIDES_ERROR by error1<String>(NAME_IDENTIFIER)
  val PROVIDES_WARNING by warning1<String>(NAME_IDENTIFIER)
  val BINDS_ERROR by error1<String>(NAME_IDENTIFIER)
  val AGGREGATION_ERROR by error1<String>(NAME_IDENTIFIER)
  val CREATE_GRAPH_ERROR by error1<String>(NAME_IDENTIFIER)
  val AS_CONTRIBUTION_ERROR by error1<String>(NAME_IDENTIFIER)
  val MULTIBINDS_ERROR by error1<String>(NAME_IDENTIFIER)
  val MULTIBINDS_OVERRIDE_ERROR by error1<String>(OVERRIDE_MODIFIER)
  val MEMBERS_INJECT_ERROR by error1<String>(NAME_IDENTIFIER)
  val MEMBERS_INJECT_STATUS_ERROR by error1<String>(MODALITY_MODIFIER)
  val MEMBERS_INJECT_WARNING by warning1<String>(NAME_IDENTIFIER)
  val MEMBERS_INJECT_RETURN_TYPE_WARNING by warning1<String>(DECLARATION_RETURN_TYPE)
  val MEMBERS_INJECT_TYPE_PARAMETERS_ERROR by error1<String>(TYPE_PARAMETERS_LIST)
  val DAGGER_REUSABLE_ERROR by error0(NAME_IDENTIFIER)
  val FUNCTION_INJECT_ERROR by error1<String>(NAME_IDENTIFIER)
  val FUNCTION_INJECT_TYPE_PARAMETERS_ERROR by error1<String>(TYPE_PARAMETERS_LIST)
  val BINDING_CONTAINER_ERROR by error1<String>(NAME_IDENTIFIER)
  val BINDING_CONTAINER_CONSTRUCTOR_VISIBILITY_ERROR by error1<String>(VISIBILITY_MODIFIER)

  override val MAP: KtDiagnosticFactoryToRendererMap =
    KtDiagnosticFactoryToRendererMap("FirMetroErrors").apply {
      // Common errors
      put(
        FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION,
        "{0} must have exactly one abstract function but found {1}.",
        TO_STRING,
        TO_STRING,
      )
      put(
        LOCAL_CLASSES_CANNOT_BE_INJECTED,
        "Local classes cannot be annotated with @Inject or have @Inject-annotated constructors.",
      )
      put(METRO_DECLARATION_ERROR, "{0}", TO_STRING)
      put(METRO_DECLARATION_VISIBILITY_ERROR, "{0} must be {1}.", TO_STRING, STRING)
      put(METRO_TYPE_PARAMETERS_ERROR, "{0}", STRING)

      // DependencyGraph creator errors
      put(GRAPH_CREATORS_ERROR, "{0}", STRING)
      put(GRAPH_CREATORS_VARARG_ERROR, "{0}", STRING)

      // DependencyGraph errors
      put(DEPENDENCY_GRAPH_ERROR, "{0}", STRING)

      // Inject Constructor errors
      put(
        SUGGEST_CLASS_INJECTION,
        "There is only one @Inject-annotated constructor. Consider moving the annotation to the class instead.",
      )

      // Inject/assisted Constructor errors
      put(CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS, "Only one `@Inject` constructor is allowed.")
      put(
        CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS,
        "You should annotate either a class XOR constructor with `@Inject` but not both.",
      )
      put(
        ASSISTED_FACTORIES_CANNOT_BE_LAZY,
        "Metro does not support injecting Lazy<{0}> because {1} is an @AssistedFactory-annotated type.",
        STRING,
        STRING,
      )
      // TODO eventually this will change to allow function injection
      put(
        ONLY_CLASSES_CAN_BE_INJECTED,
        "Only classes can be annotated with @Inject or have @Inject-annotated constructors.",
      )
      put(
        ONLY_FINAL_AND_OPEN_CLASSES_CAN_BE_INJECTED,
        "Only final and open classes be annotated with @Inject or have @Inject-annotated constructors.",
      )
      put(INJECTED_CLASSES_MUST_BE_VISIBLE, "Injected classes must be {0}.", STRING)
      put(ASSISTED_INJECTION_ERROR, "{0}", STRING)
      put(PROVIDES_ERROR, "{0}", STRING)
      put(PROVIDES_WARNING, "{0}", STRING)
      put(AGGREGATION_ERROR, "{0}", STRING)
      put(CREATE_GRAPH_ERROR, "{0}", STRING)
      put(AS_CONTRIBUTION_ERROR, "{0}", STRING)
      put(MEMBERS_INJECT_ERROR, "{0}", STRING)
      put(MEMBERS_INJECT_STATUS_ERROR, "{0}", STRING)
      put(MEMBERS_INJECT_WARNING, "{0}", STRING)
      put(MEMBERS_INJECT_RETURN_TYPE_WARNING, "{0}", STRING)
      put(MEMBERS_INJECT_TYPE_PARAMETERS_ERROR, "{0}", STRING)
      put(BINDS_ERROR, "{0}", STRING)
      put(MULTIBINDS_ERROR, "{0}", STRING)
      put(MULTIBINDS_OVERRIDE_ERROR, "{0}", STRING)
      put(PROVIDES_COULD_BE_BINDS, "{0}", STRING)
      put(PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_ERROR, "{0}", STRING)
      put(PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING, "{0}", STRING)
      put(PROVIDES_PROPERTIES_CANNOT_BE_PRIVATE, "{0}", STRING)
      put(FUNCTION_INJECT_ERROR, "{0}", STRING)
      put(FUNCTION_INJECT_TYPE_PARAMETERS_ERROR, "{0}", STRING)
      put(BINDING_CONTAINER_ERROR, "{0}", STRING)
      put(
        PROVIDER_OVERRIDES,
        "Do not override `@Provides` declarations. Consider using `@ContributesTo.replaces`, `@ContributesBinding.replaces`, and `@DependencyGraph.excludes` instead.",
      )
      put(
        DAGGER_REUSABLE_ERROR,
        "Dagger's `@Reusable` is not supported in Metro. See https://zacsweers.github.io/metro/faq#why-doesnt-metro-support-reusable for more information.",
      )
    }

  init {
    RootDiagnosticRendererFactory.registerFactory(this)
  }
}
