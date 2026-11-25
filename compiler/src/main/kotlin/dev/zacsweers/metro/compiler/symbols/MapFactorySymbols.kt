// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.symbols

import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.nestedClasses

/**
 * Symbols for one runtime `Map*Factory` class and its nested `Builder`.
 *
 * Each framework resolves the factory class and its `builder`, `empty` and `singleton` functions in
 * its own way. Every member resolves lazily on first read. A runtime may not ship every factory
 * class.
 *
 * [emptyFunction] and [singletonFunction] are null when the framework runtime doesn't expose that
 * helper, like Dagger interop. Callers then use the builder path.
 */
internal abstract class MapFactorySymbols {
  protected abstract val factoryClass: IrClassSymbol
  abstract val builderFunction: IrSimpleFunctionSymbol
  abstract val emptyFunction: IrSimpleFunctionSymbol?
  abstract val singletonFunction: IrSimpleFunctionSymbol?

  val builderClass: IrClassSymbol by lazy {
    factoryClass.owner.nestedClasses.first { it.name.asString() == "Builder" }.symbol
  }

  val builderPutFunction: IrSimpleFunctionSymbol by lazy {
    builderClass.requireSimpleFunction("put")
  }

  val builderBuildFunction: IrSimpleFunctionSymbol by lazy {
    builderClass.requireSimpleFunction("build")
  }
}
