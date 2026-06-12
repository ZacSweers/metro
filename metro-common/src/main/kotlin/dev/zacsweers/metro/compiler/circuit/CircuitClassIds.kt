// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Circuit-specific ClassIds used by Metro's native Circuit code generation. */
public object CircuitClassIds {
  private const val CIRCUIT_RUNTIME_BASE_PACKAGE = "com.slack.circuit.runtime"
  private const val CIRCUIT_RUNTIME_UI_PACKAGE = "$CIRCUIT_RUNTIME_BASE_PACKAGE.ui"
  private const val CIRCUIT_RUNTIME_SCREEN_PACKAGE = "$CIRCUIT_RUNTIME_BASE_PACKAGE.screen"
  private const val CIRCUIT_RUNTIME_PRESENTER_PACKAGE = "$CIRCUIT_RUNTIME_BASE_PACKAGE.presenter"
  private const val CIRCUIT_CODEGEN_ANNOTATIONS_PACKAGE = "com.slack.circuit.codegen.annotations"

  // Annotation
  public val CircuitInject: ClassId =
    ClassId(FqName(CIRCUIT_CODEGEN_ANNOTATIONS_PACKAGE), Name.identifier("CircuitInject"))

  // Runtime types
  public val Screen: ClassId =
    ClassId(FqName(CIRCUIT_RUNTIME_SCREEN_PACKAGE), Name.identifier("Screen"))
  public val Navigator: ClassId =
    ClassId(FqName(CIRCUIT_RUNTIME_BASE_PACKAGE), Name.identifier("Navigator"))
  public val CircuitContext: ClassId =
    ClassId(FqName(CIRCUIT_RUNTIME_BASE_PACKAGE), Name.identifier("CircuitContext"))
  public val CircuitUiState: ClassId =
    ClassId(FqName(CIRCUIT_RUNTIME_BASE_PACKAGE), Name.identifier("CircuitUiState"))

  // Compose Modifier
  public val Modifier: ClassId = ClassId(FqName("androidx.compose.ui"), Name.identifier("Modifier"))

  // Ui types
  public val Ui: ClassId = ClassId(FqName(CIRCUIT_RUNTIME_UI_PACKAGE), Name.identifier("Ui"))
  public val UiFactory: ClassId = Ui.createNestedClassId(Name.identifier("Factory"))

  // Presenter types
  public val Presenter: ClassId =
    ClassId(FqName(CIRCUIT_RUNTIME_PRESENTER_PACKAGE), Name.identifier("Presenter"))
  public val PresenterFactory: ClassId = Presenter.createNestedClassId(Name.identifier("Factory"))
}
