// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ir.BindsCallable
import dev.zacsweers.metro.compiler.ir.BindsOptionalOfCallable
import dev.zacsweers.metro.compiler.ir.IrCallableMetadata
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.MultibindsCallable
import dev.zacsweers.metro.compiler.ir.toBindsCallable
import dev.zacsweers.metro.compiler.ir.toBindsOptionalOfCallable
import dev.zacsweers.metro.compiler.ir.toMultibindsCallable
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.isPropertyAccessor

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
    add(function, callableMetadata = null)
  }

  context(context: IrMetroContext)
  fun addDirect(function: MetroSimpleFunction) {
    val callableMetadata =
      IrCallableMetadata.forInCompilation(
        sourceFunction = function.ir,
        signatureFunction = function.ir,
        annotations = function.annotations,
        isPropertyAccessor = function.ir.isPropertyAccessor,
        newInstanceName = function.ir.name,
      )
    add(function, callableMetadata)
  }

  context(context: IrMetroContext)
  private fun add(function: MetroSimpleFunction, callableMetadata: IrCallableMetadata?) {
    if (function.annotations.isBinds) {
      val callable =
        if (callableMetadata == null) {
          function.toBindsCallable(isInterop)
        } else {
          function.toBindsCallable(isInterop, callableMetadata)
        }
      if (
        bindsCallables.none {
          it.callableId == callable.callableId &&
            it.source == callable.source &&
            it.rawTarget == callable.rawTarget
        }
      ) {
        bindsCallables += callable
      }
    } else if (function.annotations.isMultibinds) {
      val callable =
        if (callableMetadata == null) {
          function.toMultibindsCallable(isInterop)
        } else {
          function.toMultibindsCallable(isInterop, callableMetadata)
        }
      if (
        multibindsCallables.none {
          it.callableId == callable.callableId && it.typeKey == callable.typeKey
        }
      ) {
        multibindsCallables += callable
      }
    } else if (function.annotations.isBindsOptionalOf) {
      val callable =
        if (callableMetadata == null) {
          function.toBindsOptionalOfCallable()
        } else {
          function.toBindsOptionalOfCallable(callableMetadata)
        }
      if (
        optionalTypes.none {
          it.callableId == callable.callableId && it.typeKey == callable.typeKey
        }
      ) {
        optionalTypes += callable
      }
    } else {
      reportCompilerBug("Unexpected binds declaration: $function")
    }
  }

  fun buildMirror(clazz: IrClass): BindsMirror {
    return BindsMirror(clazz, bindsCallables, multibindsCallables, optionalTypes)
  }
}
