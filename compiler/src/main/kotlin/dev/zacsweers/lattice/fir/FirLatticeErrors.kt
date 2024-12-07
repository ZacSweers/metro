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
package dev.zacsweers.lattice.fir

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticRenderers.TO_STRING
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.MODALITY_MODIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.NAME_IDENTIFIER
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies.VISIBILITY_MODIFIER
import org.jetbrains.kotlin.diagnostics.error0
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.error2
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.RootDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.warning0

internal object FirLatticeErrors {
  // Common
  val FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION by error2<PsiElement, String, String>(NAME_IDENTIFIER)
  val FACTORY_CLASS_CANNOT_BE_LOCAL by error1<PsiElement, String>(NAME_IDENTIFIER)
  val FACTORY_SHOULD_BE_INTERFACE_OR_ABSTRACT by error1<PsiElement, String>(NAME_IDENTIFIER)
  val FACTORY_MUST_BE_VISIBLE by error1<PsiElement, String>(NAME_IDENTIFIER)
  val FACTORY_FACTORY_FUNCTION_MUST_BE_VISIBLE by error1<PsiElement, String>(NAME_IDENTIFIER)

  // Component creator errors
  val COMPONENT_CREATORS_FACTORY_PARAMS_MUST_BE_UNIQUE by error0<PsiElement>(NAME_IDENTIFIER)
  val COMPONENT_CREATORS_FACTORY_PARAMS_MUST_BE_BINDSINSTANCE_OR_COMPONENTS by
    error0<PsiElement>(NAME_IDENTIFIER)

  // Component errors
  val COMPONENT_SHOULD_BE_CLASS_OR_INTERFACE by error0<PsiElement>(NAME_IDENTIFIER)

  // Inject constructor errors
  val SUGGEST_CLASS_INJECTION_IF_NO_PARAMS by warning0<PsiElement>(NAME_IDENTIFIER)

  // Inject/assisted constructor errors
  val CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS by error0<PsiElement>(NAME_IDENTIFIER)
  val CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS by error0<PsiElement>(NAME_IDENTIFIER)
  val ONLY_CLASSES_CAN_BE_INJECTED by error0<PsiElement>(NAME_IDENTIFIER)
  val ONLY_FINAL_CLASSES_CAN_BE_INJECTED by error0<PsiElement>(MODALITY_MODIFIER)
  val LOCAL_CLASSES_CANNOT_BE_INJECTED by error0<PsiElement>(NAME_IDENTIFIER)
  val INJECTED_CLASSES_MUST_BE_VISIBLE by error0<PsiElement>(VISIBILITY_MODIFIER)
  val INJECTED_CONSTRUCTOR_MUST_BE_VISIBLE by error0<PsiElement>(VISIBILITY_MODIFIER)

  init {
    RootDiagnosticRendererFactory.registerFactory(FirLatticeErrorMessages)
  }
}

private object FirLatticeErrorMessages : BaseDiagnosticRendererFactory() {
  override val MAP: KtDiagnosticFactoryToRendererMap =
    KtDiagnosticFactoryToRendererMap("Lattice").apply {
      // Common errors
      put(
        FirLatticeErrors.FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION,
        "{0} classes must have exactly one abstract function but found {1}.",
        TO_STRING,
        TO_STRING,
      )
      put(
        FirLatticeErrors.LOCAL_CLASSES_CANNOT_BE_INJECTED,
        "Local classes cannot be annotated with @Inject or have @(Assisted)Inject-constructors.",
      )
      put(
        FirLatticeErrors.FACTORY_CLASS_CANNOT_BE_LOCAL,
        "{0} classes cannot be local classes.",
        TO_STRING,
      )
      put(
        FirLatticeErrors.FACTORY_SHOULD_BE_INTERFACE_OR_ABSTRACT,
        "{0} classes should be non-sealed abstract classes or interfaces.",
        TO_STRING,
      )
      put(FirLatticeErrors.FACTORY_MUST_BE_VISIBLE, "{0} must be public or internal.", TO_STRING)
      put(
        FirLatticeErrors.FACTORY_FACTORY_FUNCTION_MUST_BE_VISIBLE,
        "{0} classes' single abstract functions must be public or internal.",
        TO_STRING,
      )

      // Component creator errors
      put(
        FirLatticeErrors.COMPONENT_CREATORS_FACTORY_PARAMS_MUST_BE_UNIQUE,
        "Component.Factory abstract function parameters must be unique.",
      )
      put(
        FirLatticeErrors.COMPONENT_CREATORS_FACTORY_PARAMS_MUST_BE_BINDSINSTANCE_OR_COMPONENTS,
        "Component.Factory abstract function parameters must be either annotated with `@BindsInstance` or be types annotated with `@Component`.",
      )

      // Component errors
      put(
        FirLatticeErrors.COMPONENT_SHOULD_BE_CLASS_OR_INTERFACE,
        "@Component-annotated types should be abstract classes or interfaces.",
      )

      // Inject Constructor errors
      put(
        FirLatticeErrors.SUGGEST_CLASS_INJECTION_IF_NO_PARAMS,
        "There are no parameters on the @Inject-annotated constructor. Consider moving the annotation to the class instead.",
      )

      // Inject/assisted Constructor errors
      put(
        FirLatticeErrors.CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS,
        "Only one `@Inject` constructor is allowed.",
      )
      put(
        FirLatticeErrors.CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS,
        "You should annotate either a class XOR constructor with `@Inject` but not both.",
      )
      // TODO eventually this will change to allow function injection
      put(
        FirLatticeErrors.ONLY_CLASSES_CAN_BE_INJECTED,
        "Only classes can be annotated with @Inject or have @(Assisted)Inject-constructors.",
      )
      put(
        FirLatticeErrors.ONLY_FINAL_CLASSES_CAN_BE_INJECTED,
        "Only final classes be annotated with @Inject or have @(Assisted)Inject-constructors.",
      )
      put(
        FirLatticeErrors.INJECTED_CLASSES_MUST_BE_VISIBLE,
        "Injected classes must be visible, either `public` or `internal`.",
      )
      put(
        FirLatticeErrors.INJECTED_CONSTRUCTOR_MUST_BE_VISIBLE,
        "Injected constructors must be visible, either `public` or `internal`.",
      )
    }
}
