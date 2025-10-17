// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.AGGREGATION_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.ASSISTED_FACTORIES_CANNOT_BE_LAZY
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.ASSISTED_INJECTION_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.ASSISTED_INJECTION_WARNING
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.AS_CONTRIBUTION_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.BINDING_CONTAINER_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.BINDS_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.BINDS_OPTIONAL_OF_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.BINDS_OPTIONAL_OF_WARNING
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.CONFLICTING_PROVIDES_SCOPE
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.CREATE_DYNAMIC_GRAPH_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.CREATE_GRAPH_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.DAGGER_REUSABLE_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.DEPENDENCY_GRAPH_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.FUNCTION_INJECT_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.FUNCTION_INJECT_TYPE_PARAMETERS_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.GRAPH_CREATORS_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.GRAPH_CREATORS_VARARG_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.GRAPH_DEPENDENCY_CYCLE
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.INJECTED_CLASSES_MUST_BE_VISIBLE
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.INTEROP_ANNOTATION_ARGS_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.INTEROP_ANNOTATION_ARGS_WARNING
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.LOCAL_CLASSES_CANNOT_BE_INJECTED
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.MAP_KEY_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.MAP_KEY_TYPE_PARAM_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.MEMBERS_INJECT_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.MEMBERS_INJECT_RETURN_TYPE_WARNING
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.MEMBERS_INJECT_STATUS_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.MEMBERS_INJECT_TYPE_PARAMETERS_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.MEMBERS_INJECT_WARNING
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.METRO_DECLARATION_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.METRO_DECLARATION_VISIBILITY_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.METRO_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.METRO_TYPE_PARAMETERS_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.METRO_WARNING
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.MULTIBINDS_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.MULTIBINDS_OVERRIDE_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.ONLY_CLASSES_CAN_BE_INJECTED
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.ONLY_FINAL_AND_OPEN_CLASSES_CAN_BE_INJECTED
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.OPTIONAL_DEPENDENCY_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.OPTIONAL_DEPENDENCY_WARNING
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.PROVIDERS_OF_LAZY_CANNOT_BE_ACCESSORS
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.PROVIDERS_OF_LAZY_MUST_BE_METRO_ONLY
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.PROVIDER_OVERRIDES
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.PROVIDES_COULD_BE_BINDS
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.PROVIDES_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.PROVIDES_PROPERTIES_CANNOT_BE_PRIVATE
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.PROVIDES_WARNING
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.REDUNDANT_PROVIDES
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.SUGGEST_CLASS_INJECTION
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.SUSPICIOUS_OBJECT_INJECTION_WARNING
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticRenderers.TO_STRING
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.DECLARATION_RETURN_TYPE
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.MODALITY_MODIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.NAME_IDENTIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.OVERRIDE_MODIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.PARAMETER_VARARG_MODIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.TYPE_PARAMETERS_LIST
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.VISIBILITY_MODIFIER
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.error2
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers.STRING
import org.jetbrains.kotlin.diagnostics.warning0
import org.jetbrains.kotlin.diagnostics.warning1
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtElement

internal object MetroDiagnostics : KtDiagnosticsContainer() {

  // Common
  val FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION by error2<KtClass, String, String>(NAME_IDENTIFIER)
  val METRO_DECLARATION_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val METRO_DECLARATION_VISIBILITY_ERROR by error2<KtElement, String, String>(VISIBILITY_MODIFIER)
  val METRO_TYPE_PARAMETERS_ERROR by error1<KtElement, String>(TYPE_PARAMETERS_LIST)
  val SUSPICIOUS_OBJECT_INJECTION_WARNING by warning1<KtElement, String>(TYPE_PARAMETERS_LIST)

  // DependencyGraph factory errors
  val GRAPH_CREATORS_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val GRAPH_CREATORS_VARARG_ERROR by error1<KtElement, String>(PARAMETER_VARARG_MODIFIER)

  // DependencyGraph errors
  val DEPENDENCY_GRAPH_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)

  // Inject constructor errors
  val SUGGEST_CLASS_INJECTION by warning0<KtElement>(NAME_IDENTIFIER)

  // Inject/assisted constructor errors
  val CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS by error0<KtElement>(NAME_IDENTIFIER)
  val CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS by error0<KtElement>(NAME_IDENTIFIER)
  val ASSISTED_FACTORIES_CANNOT_BE_LAZY by error2<KtElement, String, String>(NAME_IDENTIFIER)
  val ONLY_CLASSES_CAN_BE_INJECTED by error0<KtElement>(NAME_IDENTIFIER)
  val ONLY_FINAL_AND_OPEN_CLASSES_CAN_BE_INJECTED by error0<KtElement>(MODALITY_MODIFIER)
  val LOCAL_CLASSES_CANNOT_BE_INJECTED by error0<KtElement>(NAME_IDENTIFIER)
  val INJECTED_CLASSES_MUST_BE_VISIBLE by error1<KtElement, String>(VISIBILITY_MODIFIER)
  val PROVIDERS_OF_LAZY_MUST_BE_METRO_ONLY by error2<KtElement, String, String>(NAME_IDENTIFIER)
  val PROVIDERS_OF_LAZY_CANNOT_BE_ACCESSORS by error0<KtElement>(NAME_IDENTIFIER)

  // Assisted factory/inject errors
  val ASSISTED_INJECTION_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val ASSISTED_INJECTION_WARNING by warning1<KtElement, String>(NAME_IDENTIFIER)

  // Provides errors
  val PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_ERROR by error1<KtElement, String>(VISIBILITY_MODIFIER)
  val PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING by
    warning1<KtElement, String>(VISIBILITY_MODIFIER)
  val PROVIDES_PROPERTIES_CANNOT_BE_PRIVATE by error1<KtElement, String>(VISIBILITY_MODIFIER)
  // TODO make this severity configurable
  val PROVIDES_COULD_BE_BINDS by warning1<KtElement, String>(NAME_IDENTIFIER)
  val PROVIDER_OVERRIDES by error0<KtElement>(MODALITY_MODIFIER)
  val PROVIDES_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val PROVIDES_WARNING by warning1<KtElement, String>(NAME_IDENTIFIER)
  val REDUNDANT_PROVIDES by warning1<KtElement, String>(NAME_IDENTIFIER)
  val CONFLICTING_PROVIDES_SCOPE by warning1<KtElement, String>(NAME_IDENTIFIER)
  val BINDS_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val BINDS_OPTIONAL_OF_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val BINDS_OPTIONAL_OF_WARNING by warning1<KtElement, String>(NAME_IDENTIFIER)
  val AGGREGATION_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val CREATE_GRAPH_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val CREATE_DYNAMIC_GRAPH_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val AS_CONTRIBUTION_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val MULTIBINDS_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val MULTIBINDS_OVERRIDE_ERROR by error1<KtElement, String>(OVERRIDE_MODIFIER)
  val MAP_KEY_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val MAP_KEY_TYPE_PARAM_ERROR by error1<KtElement, String>(TYPE_PARAMETERS_LIST)
  val MEMBERS_INJECT_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val MEMBERS_INJECT_STATUS_ERROR by error1<KtElement, String>(MODALITY_MODIFIER)
  val MEMBERS_INJECT_WARNING by warning1<KtElement, String>(NAME_IDENTIFIER)
  val MEMBERS_INJECT_RETURN_TYPE_WARNING by warning1<KtElement, String>(DECLARATION_RETURN_TYPE)
  val MEMBERS_INJECT_TYPE_PARAMETERS_ERROR by error1<KtElement, String>(TYPE_PARAMETERS_LIST)
  val DAGGER_REUSABLE_ERROR by error0<KtElement>(NAME_IDENTIFIER)
  val FUNCTION_INJECT_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val FUNCTION_INJECT_TYPE_PARAMETERS_ERROR by error1<KtElement, String>(TYPE_PARAMETERS_LIST)
  val BINDING_CONTAINER_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)

  val OPTIONAL_DEPENDENCY_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val OPTIONAL_DEPENDENCY_WARNING by warning1<KtElement, String>(NAME_IDENTIFIER)

  // Interop warnings
  val INTEROP_ANNOTATION_ARGS_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val INTEROP_ANNOTATION_ARGS_WARNING by warning1<KtElement, String>(NAME_IDENTIFIER)

  // IR errors
  val GRAPH_DEPENDENCY_CYCLE by error1<KtElement, String>(NAME_IDENTIFIER)
  val METRO_ERROR by error1<KtElement, String>(NAME_IDENTIFIER)
  val METRO_WARNING by warning1<KtElement, String>(NAME_IDENTIFIER)

  override fun getRendererFactory(): BaseDiagnosticRendererFactory {
    return FirMetroErrorMessages
  }
}

private object FirMetroErrorMessages : BaseDiagnosticRendererFactory() {
  override val MAP by
    KtDiagnosticFactoryToRendererMap("Metro") { map ->
      map.apply {
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
        put(SUSPICIOUS_OBJECT_INJECTION_WARNING, "{0}", STRING)

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
        put(
          CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS,
          "Only one `@Inject` constructor is allowed.",
        )
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
        put(
          PROVIDERS_OF_LAZY_MUST_BE_METRO_ONLY,
          "Cannot mix intrinsic types across libraries for Provider<Lazy<T>> types. They must be dev.zacsweers.metro.Provider and kotlin.Lazy but found {0} and {1}.",
          STRING,
          STRING,
        )
        put(PROVIDERS_OF_LAZY_CANNOT_BE_ACCESSORS, "Provider<Lazy<T>> accessors are not supported.")
        put(ASSISTED_INJECTION_ERROR, "{0}", STRING)
        put(ASSISTED_INJECTION_WARNING, "{0}", STRING)
        put(PROVIDES_ERROR, "{0}", STRING)
        put(PROVIDES_WARNING, "{0}", STRING)
        put(REDUNDANT_PROVIDES, "{0}", STRING)
        put(CONFLICTING_PROVIDES_SCOPE, "{0}", STRING)
        put(AGGREGATION_ERROR, "{0}", STRING)
        put(CREATE_GRAPH_ERROR, "{0}", STRING)
        put(CREATE_DYNAMIC_GRAPH_ERROR, "{0}", STRING)
        put(AS_CONTRIBUTION_ERROR, "{0}", STRING)
        put(MEMBERS_INJECT_ERROR, "{0}", STRING)
        put(MEMBERS_INJECT_STATUS_ERROR, "{0}", STRING)
        put(MEMBERS_INJECT_WARNING, "{0}", STRING)
        put(MEMBERS_INJECT_RETURN_TYPE_WARNING, "{0}", STRING)
        put(MEMBERS_INJECT_TYPE_PARAMETERS_ERROR, "{0}", STRING)
        put(BINDS_ERROR, "{0}", STRING)
        put(BINDS_OPTIONAL_OF_ERROR, "{0}", STRING)
        put(BINDS_OPTIONAL_OF_WARNING, "{0}", STRING)
        put(MULTIBINDS_ERROR, "{0}", STRING)
        put(MULTIBINDS_OVERRIDE_ERROR, "{0}", STRING)
        put(MAP_KEY_ERROR, "{0}", STRING)
        put(MAP_KEY_TYPE_PARAM_ERROR, "{0}", STRING)
        put(PROVIDES_COULD_BE_BINDS, "{0}", STRING)
        put(PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_ERROR, "{0}", STRING)
        put(PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING, "{0}", STRING)
        put(PROVIDES_PROPERTIES_CANNOT_BE_PRIVATE, "{0}", STRING)
        put(FUNCTION_INJECT_ERROR, "{0}", STRING)
        put(FUNCTION_INJECT_TYPE_PARAMETERS_ERROR, "{0}", STRING)
        put(BINDING_CONTAINER_ERROR, "{0}", STRING)
        put(OPTIONAL_DEPENDENCY_WARNING, "{0}", STRING)
        put(OPTIONAL_DEPENDENCY_ERROR, "{0}", STRING)
        put(INTEROP_ANNOTATION_ARGS_ERROR, "{0}", STRING)
        put(INTEROP_ANNOTATION_ARGS_WARNING, "{0}", STRING)
        put(
          PROVIDER_OVERRIDES,
          "Do not override `@Provides` declarations. Consider using `@ContributesTo.replaces`, `@ContributesBinding.replaces`, and `@DependencyGraph.excludes` instead.",
        )
        put(
          DAGGER_REUSABLE_ERROR,
          "Dagger's `@Reusable` is not supported in Metro. See https://zacsweers.github.io/metro/latest/faq#why-doesnt-metro-support-reusable for more information.",
        )

        // IR diagnostics
        put(METRO_ERROR, "{0}", TO_STRING)
        put(METRO_WARNING, "{0}", TO_STRING)
        put(GRAPH_DEPENDENCY_CYCLE, "[Metro/GraphDependencyCycle] {0}", TO_STRING)
      }
    }
}
