// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.parameters

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.typeWith

context(context: IrMetroContext)
internal fun IrType.wrapInMembersInjector(): IrType {
  return wrapIn(context.symbols.metroMembersInjector)
}

internal fun IrType.wrapInProvider(providerType: IrType): IrType {
  return wrapInProvider(providerType.classOrFail)
}

internal fun IrType.wrapInProvider(providerType: IrClassSymbol): IrType {
  return providerType.typeWith(this)
}

internal fun IrType.wrapInLazy(symbols: Symbols): IrType {
  return wrapIn(symbols.stdlibLazy)
}

private fun IrType.wrapIn(target: IrClassSymbol): IrType {
  return target.typeWith(this)
}
