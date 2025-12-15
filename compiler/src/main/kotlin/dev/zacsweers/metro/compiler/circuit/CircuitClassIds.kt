// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Circuit-specific ClassIds and CallableIds. */
internal object CircuitClassIds {
  private const val CIRCUIT_RUNTIME_BASE_PACKAGE = "com.slack.circuit.runtime"
  private const val CIRCUIT_RUNTIME_UI_PACKAGE = "$CIRCUIT_RUNTIME_BASE_PACKAGE.ui"
  private const val CIRCUIT_RUNTIME_SCREEN_PACKAGE = "$CIRCUIT_RUNTIME_BASE_PACKAGE.screen"
  private const val CIRCUIT_RUNTIME_PRESENTER_PACKAGE = "$CIRCUIT_RUNTIME_BASE_PACKAGE.presenter"
  private const val CIRCUIT_CODEGEN_ANNOTATIONS_PACKAGE = "com.slack.circuit.codegen.annotations"

  // Annotation
  val CircuitInject =
    ClassId(FqName(CIRCUIT_CODEGEN_ANNOTATIONS_PACKAGE), Name.identifier("CircuitInject"))

  // Runtime types
  val Screen = ClassId(FqName(CIRCUIT_RUNTIME_SCREEN_PACKAGE), Name.identifier("Screen"))
  val Navigator = ClassId(FqName(CIRCUIT_RUNTIME_BASE_PACKAGE), Name.identifier("Navigator"))
  val CircuitContext =
    ClassId(FqName(CIRCUIT_RUNTIME_BASE_PACKAGE), Name.identifier("CircuitContext"))
  val CircuitUiState =
    ClassId(FqName(CIRCUIT_RUNTIME_BASE_PACKAGE), Name.identifier("CircuitUiState"))

  // Compose Modifier
  val Modifier = ClassId(FqName("androidx.compose.ui"), Name.identifier("Modifier"))

  // Ui types
  val Ui = ClassId(FqName(CIRCUIT_RUNTIME_UI_PACKAGE), Name.identifier("Ui"))
  val UiFactory = Ui.createNestedClassId(Name.identifier("Factory"))

  // Presenter types
  val Presenter = ClassId(FqName(CIRCUIT_RUNTIME_PRESENTER_PACKAGE), Name.identifier("Presenter"))
  val PresenterFactory = Presenter.createNestedClassId(Name.identifier("Factory"))
}

internal object CircuitCallableIds {
  private val presenterPackage = FqName("com.slack.circuit.runtime.presenter")
  private val uiPackage = FqName("com.slack.circuit.runtime.ui")

  val presenterOf = CallableId(presenterPackage, Name.identifier("presenterOf"))
  val ui = CallableId(uiPackage, Name.identifier("ui"))
}

internal object CircuitNames {
  val Factory = Name.identifier("CircuitFactory")
  val create = Name.identifier("create")
  val screen = Name.identifier("screen")
  val navigator = Name.identifier("navigator")
  val context = Name.identifier("context")
  val state = Name.identifier("state")
  val modifier = Name.identifier("modifier")
  val provider = Name.identifier("provider")
  val factoryField = Name.identifier("factory")
}
