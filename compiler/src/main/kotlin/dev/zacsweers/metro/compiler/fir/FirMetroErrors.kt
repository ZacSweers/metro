// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import org.jetbrains.kotlin.diagnostics.AbstractSourceElementPositioningStrategy
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory0DelegateProvider
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory1DelegateProvider
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory2DelegateProvider
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticRenderers.TO_STRING
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.DECLARATION_RETURN_TYPE
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.MODALITY_MODIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.NAME_IDENTIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.OVERRIDE_MODIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.TYPE_PARAMETERS_LIST
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.VISIBILITY_MODIFIER
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers.STRING
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory

/**
 * The compiler and the IDE use a different version of this class, so use reflection to find the
 * available version.
 */
// Adapted from
// https://github.com/TadeasKriz/K2PluginBase/blob/main/kotlin-plugin/src/main/kotlin/com/tadeaskriz/example/ExamplePluginErrors.kt#L8
private val psiElementClass by lazy {
  try {
      Class.forName("org.jetbrains.kotlin.com.intellij.psi.PsiElement")
    } catch (_: ClassNotFoundException) {
      Class.forName("com.intellij.psi.PsiElement")
    }
    .kotlin
}

/* Copies of errors/warnings with a hack for the correct `PsiElement` class. */
private fun warning0(
  positioningStrategy: AbstractSourceElementPositioningStrategy =
    SourceElementPositioningStrategies.DEFAULT
) =
  DiagnosticFactory0DelegateProvider(
    severity = Severity.WARNING,
    positioningStrategy = positioningStrategy,
    psiType = psiElementClass,
  )

private fun <T> warning1(
  positioningStrategy: AbstractSourceElementPositioningStrategy =
    SourceElementPositioningStrategies.DEFAULT
) =
  DiagnosticFactory1DelegateProvider<T>(
    severity = Severity.WARNING,
    positioningStrategy = positioningStrategy,
    psiType = psiElementClass,
  )

private fun error0(
  positioningStrategy: AbstractSourceElementPositioningStrategy =
    SourceElementPositioningStrategies.DEFAULT
) =
  DiagnosticFactory0DelegateProvider(
    severity = Severity.ERROR,
    positioningStrategy = positioningStrategy,
    psiType = psiElementClass,
  )

private fun <A> error1(
  positioningStrategy: AbstractSourceElementPositioningStrategy =
    SourceElementPositioningStrategies.DEFAULT
) =
  DiagnosticFactory1DelegateProvider<A>(
    severity = Severity.ERROR,
    positioningStrategy = positioningStrategy,
    psiType = psiElementClass,
  )

private fun <A, B> error2(
  positioningStrategy: AbstractSourceElementPositioningStrategy =
    SourceElementPositioningStrategies.DEFAULT
) =
  DiagnosticFactory2DelegateProvider<A, B>(
    severity = Severity.ERROR,
    positioningStrategy = positioningStrategy,
    psiType = psiElementClass,
  )

internal object FirMetroErrors : BaseDiagnosticRendererFactory() {

  // Common
  val FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION by error2<String, String>(NAME_IDENTIFIER)
  val METRO_DECLARATION_ERROR by error1<String>(NAME_IDENTIFIER)
  val METRO_DECLARATION_VISIBILITY_ERROR by error1<String>(VISIBILITY_MODIFIER)
  val METRO_TYPE_PARAMETERS_ERROR by error1<String>(TYPE_PARAMETERS_LIST)

  // DependencyGraph factory errors
  val GRAPH_CREATORS_ERROR by error1<String>(NAME_IDENTIFIER)

  // DependencyGraph errors
  val DEPENDENCY_GRAPH_ERROR by error1<String>(NAME_IDENTIFIER)

  // Inject constructor errors
  val SUGGEST_CLASS_INJECTION_IF_NO_PARAMS by warning0(NAME_IDENTIFIER)

  // Inject/assisted constructor errors
  val CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS by error0(NAME_IDENTIFIER)
  val CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS by error0(NAME_IDENTIFIER)
  val ONLY_CLASSES_CAN_BE_INJECTED by error0(NAME_IDENTIFIER)
  val ONLY_FINAL_CLASSES_CAN_BE_INJECTED by error0(MODALITY_MODIFIER)
  val LOCAL_CLASSES_CANNOT_BE_INJECTED by error0(NAME_IDENTIFIER)
  val INJECTED_CLASSES_MUST_BE_VISIBLE by error0(VISIBILITY_MODIFIER)

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
  val MULTIBINDS_ERROR by error1<String>(NAME_IDENTIFIER)
  val MULTIBINDS_OVERRIDE_ERROR by error1<String>(OVERRIDE_MODIFIER)
  val MEMBERS_INJECT_ERROR by error1<String>(NAME_IDENTIFIER)
  val MEMBERS_INJECT_STATUS_ERROR by error1<String>(MODALITY_MODIFIER)
  val MEMBERS_INJECT_WARNING by warning1<String>(NAME_IDENTIFIER)
  val MEMBERS_INJECT_RETURN_TYPE_WARNING by warning1<String>(DECLARATION_RETURN_TYPE)

  override val MAP: KtDiagnosticFactoryToRendererMap =
    KtDiagnosticFactoryToRendererMap("Metro").apply {
      // Common errors
      put(
        FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION,
        "{0} classes must have exactly one abstract function but found {1}.",
        TO_STRING,
        TO_STRING,
      )
      put(
        LOCAL_CLASSES_CANNOT_BE_INJECTED,
        "Local classes cannot be annotated with @Inject or have @Inject-annotated constructors.",
      )
      put(METRO_DECLARATION_ERROR, "{0}", TO_STRING)
      put(METRO_DECLARATION_VISIBILITY_ERROR, "{0} must be public or internal.", TO_STRING)
      put(METRO_TYPE_PARAMETERS_ERROR, "{0}", STRING)

      // DependencyGraph creator errors
      put(GRAPH_CREATORS_ERROR, "{0}", STRING)

      // DependencyGraph errors
      put(DEPENDENCY_GRAPH_ERROR, "{0}", STRING)

      // Inject Constructor errors
      put(
        SUGGEST_CLASS_INJECTION_IF_NO_PARAMS,
        "There are no parameters on the @Inject-annotated constructor. Consider moving the annotation to the class instead.",
      )

      // Inject/assisted Constructor errors
      put(CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS, "Only one `@Inject` constructor is allowed.")
      put(
        CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS,
        "You should annotate either a class XOR constructor with `@Inject` but not both.",
      )
      // TODO eventually this will change to allow function injection
      put(
        ONLY_CLASSES_CAN_BE_INJECTED,
        "Only classes can be annotated with @Inject or have @Inject-annotated constructors.",
      )
      put(
        ONLY_FINAL_CLASSES_CAN_BE_INJECTED,
        "Only final classes be annotated with @Inject or have @Inject-annotated constructors.",
      )
      put(
        INJECTED_CLASSES_MUST_BE_VISIBLE,
        "Injected classes must be visible, either `public` or `internal`.",
      )
      put(ASSISTED_INJECTION_ERROR, "{0}", STRING)
      put(PROVIDES_ERROR, "{0}", STRING)
      put(PROVIDES_WARNING, "{0}", STRING)
      put(AGGREGATION_ERROR, "{0}", STRING)
      put(MEMBERS_INJECT_ERROR, "{0}", STRING)
      put(MEMBERS_INJECT_STATUS_ERROR, "{0}", STRING)
      put(MEMBERS_INJECT_WARNING, "{0}", STRING)
      put(MEMBERS_INJECT_RETURN_TYPE_WARNING, "{0}", STRING)
      put(BINDS_ERROR, "{0}", STRING)
      put(MULTIBINDS_ERROR, "{0}", STRING)
      put(MULTIBINDS_OVERRIDE_ERROR, "{0}", STRING)
      put(PROVIDES_COULD_BE_BINDS, "{0}", STRING)
      put(PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_ERROR, "{0}", STRING)
      put(PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING, "{0}", STRING)
      put(PROVIDES_PROPERTIES_CANNOT_BE_PRIVATE, "{0}", STRING)
      put(
        PROVIDER_OVERRIDES,
        "Do not override `@Provides` declarations. Consider using `@ContributesTo.replaces`, `@ContributesBinding.replaces`, and `@DependencyGraph.excludes` instead.",
      )
    }

  init {
    RootDiagnosticRendererFactory.registerFactory(this)
  }
}
