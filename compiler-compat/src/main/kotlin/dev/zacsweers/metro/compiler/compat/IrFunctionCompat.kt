// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat

import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

/** Avoids the binary-incompatible return type change in Kotlin 2.5's equivalent helper. */
public val IrFunction.propertyIfAccessorCompat: IrDeclarationWithName
  get() = (this as? IrSimpleFunction)?.correspondingPropertySymbol?.owner ?: this
