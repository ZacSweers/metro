// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import org.jetbrains.kotlin.name.ClassId

/**
 * ClassIds for Metro's native Circuit code generation (the `enable-circuit-codegen` option),
 * mirroring the compiler's `CircuitClassIds`.
 */
internal object CircuitIds {
  val CIRCUIT_INJECT: ClassId =
    ClassId.fromString("com/slack/circuit/codegen/annotations/CircuitInject")

  val SCREEN: ClassId = ClassId.fromString("com/slack/circuit/runtime/screen/Screen")
  val NAVIGATOR: ClassId = ClassId.fromString("com/slack/circuit/runtime/Navigator")
  val CIRCUIT_CONTEXT: ClassId = ClassId.fromString("com/slack/circuit/runtime/CircuitContext")
  val CIRCUIT_UI_STATE: ClassId = ClassId.fromString("com/slack/circuit/runtime/CircuitUiState")
  val MODIFIER: ClassId = ClassId.fromString("androidx/compose/ui/Modifier")

  val UI: ClassId = ClassId.fromString("com/slack/circuit/runtime/ui/Ui")
  val UI_FACTORY: ClassId = ClassId.fromString("com/slack/circuit/runtime/ui/Ui.Factory")
  val PRESENTER: ClassId = ClassId.fromString("com/slack/circuit/runtime/presenter/Presenter")
  val PRESENTER_FACTORY: ClassId =
    ClassId.fromString("com/slack/circuit/runtime/presenter/Presenter.Factory")
}
