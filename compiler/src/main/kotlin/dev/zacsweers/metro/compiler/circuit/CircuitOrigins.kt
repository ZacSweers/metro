// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin

/** FIR declaration origin keys and IR origins for Circuit-generated declarations. */
internal object CircuitOrigins {
  /** Key for generated Circuit factory classes. */
  data class FactoryClass(val type: FactoryType?) : GeneratedDeclarationKey()

  /** Key for generated Circuit factory constructors. */
  object FactoryConstructor : GeneratedDeclarationKey()

  /** Key for generated Circuit factory `create()` functions. */
  object FactoryCreateFunction : GeneratedDeclarationKey()

  // IR Origins
//  val IrFactoryClass = IrDeclarationOrigin.GeneratedByPlugin(FactoryClass)
  val IrFactoryConstructor = IrDeclarationOrigin.GeneratedByPlugin(FactoryConstructor)
  val IrFactoryCreateFunction = IrDeclarationOrigin.GeneratedByPlugin(FactoryCreateFunction)
}
