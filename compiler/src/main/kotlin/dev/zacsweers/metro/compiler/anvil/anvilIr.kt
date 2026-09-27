// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.anvil

import dev.zacsweers.metro.compiler.ir.getAnnotation
import dev.zacsweers.metro.compiler.ir.originClassOrNull
import org.jetbrains.kotlin.ir.declarations.IrClass

/**
 * Returns the class that an Anvil-generated binding module binds, or null for any other class.
 *
 * Anvil generates these modules for `@ContributesBinding` and `@ContributesMultibinding`.
 */
internal fun IrClass.anvilBindingModuleOrigin(): IrClass? {
  val marker = getAnnotation(AnvilSymbols.InternalBindingMarker.asSingleFqName()) ?: return null
  return marker.originClassOrNull()
}
