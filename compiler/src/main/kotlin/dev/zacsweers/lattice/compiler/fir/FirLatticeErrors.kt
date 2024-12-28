/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.compiler.fir

import org.jetbrains.kotlin.diagnostics.AbstractSourceElementPositioningStrategy
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory0DelegateProvider
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory1DelegateProvider
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory2DelegateProvider
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticRenderers.TO_STRING
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.MODALITY_MODIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.NAME_IDENTIFIER
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

internal object FirLatticeErrors : BaseDiagnosticRendererFactory() {

  // Common
  val FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION by error2<String, String>(NAME_IDENTIFIER)
  val LATTICE_DECLARATION_ERROR by error1<String>(NAME_IDENTIFIER)
  val LATTICE_DECLARATION_VISIBILITY_ERROR by error1<String>(VISIBILITY_MODIFIER)

  // DependencyGraph factory errors
  val GRAPH_CREATORS_FACTORY_PARAMS_MUST_BE_UNIQUE by error0(NAME_IDENTIFIER)
  val GRAPH_CREATORS_FACTORY_PARAMS_MUST_BE_BINDSINSTANCE_OR_GRAPHS by error0(NAME_IDENTIFIER)

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
  // All errors are just passed through this one
  val ASSISTED_INJECTION by error1<String>(NAME_IDENTIFIER)

  // Provides errors
  val PROVIDES_SHOULD_BE_PRIVATE by warning0(VISIBILITY_MODIFIER)
  val PROVIDER_OVERRIDES by error0(MODALITY_MODIFIER)
  val PROVIDES_ERROR by error1<String>(NAME_IDENTIFIER)

  override val MAP: KtDiagnosticFactoryToRendererMap =
    KtDiagnosticFactoryToRendererMap("Lattice").apply {
      // Common errors
      put(
        FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION,
        "{0} classes must have exactly one abstract function but found {1}.",
        TO_STRING,
        TO_STRING,
      )
      put(
        LOCAL_CLASSES_CANNOT_BE_INJECTED,
        "Local classes cannot be annotated with @Inject or have @(Assisted)Inject-constructors.",
      )
      put(LATTICE_DECLARATION_ERROR, "{0}", TO_STRING)
      put(LATTICE_DECLARATION_VISIBILITY_ERROR, "{0} must be public or internal.", TO_STRING)

      // DependencyGraph creator errors
      put(
        GRAPH_CREATORS_FACTORY_PARAMS_MUST_BE_UNIQUE,
        "DependencyGraph.Factory abstract function parameters must be unique.",
      )
      put(
        GRAPH_CREATORS_FACTORY_PARAMS_MUST_BE_BINDSINSTANCE_OR_GRAPHS,
        "DependencyGraph.Factory abstract function parameters must be either annotated with `@BindsInstance` or be types annotated with `@DependencyGraph`.",
      )

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
        "Only classes can be annotated with @Inject or have @(Assisted)Inject-constructors.",
      )
      put(
        ONLY_FINAL_CLASSES_CAN_BE_INJECTED,
        "Only final classes be annotated with @Inject or have @(Assisted)Inject-constructors.",
      )
      put(
        INJECTED_CLASSES_MUST_BE_VISIBLE,
        "Injected classes must be visible, either `public` or `internal`.",
      )
      put(ASSISTED_INJECTION, "{0}", STRING)
      put(PROVIDES_ERROR, "{0}", STRING)
      put(PROVIDES_SHOULD_BE_PRIVATE, "`@Provides` declarations should be private.")
      put(
        PROVIDER_OVERRIDES,
        "Do not override `@Provides` declarations. Consider using `@ContributesTo.replaces`, `@ContributesBinding.replaces`, and `@DependencyGraph.excludes` instead.",
      )
    }

  init {
    RootDiagnosticRendererFactory.registerFactory(this)
  }
}
