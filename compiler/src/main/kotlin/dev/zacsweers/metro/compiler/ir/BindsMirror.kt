// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.name.CallableId

internal data class BindsMirror(
  val ir: IrClass,
  /** Set of binds callables by their [CallableId]. */
  val bindsCallables: Set<BindsCallable>,
  /** Set of multibinds callables by their [BindsCallable]. */
  val multibindsCallables: Set<MultibindsCallable>,
  /**
   * Interoped optional types from `@BindsOptionalOf`. Only present if Dagger interop is enabled.
   */
  val optionalKeys: Set<BindsOptionalOfCallable>,
) {
  fun isEmpty() =
    bindsCallables.isEmpty() && multibindsCallables.isEmpty() && optionalKeys.isEmpty()
}
