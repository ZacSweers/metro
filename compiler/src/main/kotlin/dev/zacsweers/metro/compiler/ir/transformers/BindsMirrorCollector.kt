// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ir.BindsCallable
import dev.zacsweers.metro.compiler.ir.BindsOptionalOfCallable
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.MultibindsCallable
import dev.zacsweers.metro.compiler.ir.toBindsCallable
import dev.zacsweers.metro.compiler.ir.toBindsOptionalOfCallable
import dev.zacsweers.metro.compiler.ir.toMultibindsCallable
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.declarations.IrClass

/**
 * Simple helper class to collect binds callables and build a [BindsMirror].
 *
 * @property isInterop Indicates if this reflects a binds mirror of an interoped dagger module,
 *   which won't actually have a true mirror class and just use the original class instead.
 */
internal class BindsMirrorCollector(private val isInterop: Boolean) {
  private val bindsCallables = mutableSetOf<BindsCallable>()
  private val multibindsCallables = mutableSetOf<MultibindsCallable>()
  private val optionalTypes = mutableSetOf<BindsOptionalOfCallable>()

  context(context: IrMetroContext)
  operator fun plusAssign(function: MetroSimpleFunction) {
    if (function.annotations.isBinds) {
      bindsCallables += function.toBindsCallable(isInterop)
    } else if (function.annotations.isMultibinds) {
      multibindsCallables += function.toMultibindsCallable(isInterop)
    } else if (function.annotations.isBindsOptionalOf) {
      optionalTypes += function.toBindsOptionalOfCallable()
    } else {
      reportCompilerBug("Unexpected binds declaration: $function")
    }
  }

  fun buildMirror(clazz: IrClass): BindsMirror {
    return BindsMirror(clazz, bindsCallables, multibindsCallables, optionalTypes)
  }
}
