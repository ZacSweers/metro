/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
// Declaration ordering:
// https://github.com/JetBrains/kotlin/blob/build-2.5.20-dev-3047/compiler/ir/ir.tree/src/org/jetbrains/kotlin/ir/util/DumpIrTree.kt
// Type ordering:
// https://github.com/JetBrains/kotlin/blob/build-2.5.20-dev-3047/compiler/ir/ir.tree/src/org/jetbrains/kotlin/ir/util/dumpKotlinLike.kt
// Initializer-order correction:
// https://github.com/JetBrains/kotlin/commit/cb358ad632cda3e0e2dcaba584555f729b00d268
package dev.zacsweers.metro.compiler.compat

import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.render

/**
 * Applies Kotlin's corrected `stableOrdered()` policy across compiler versions. Properties with
 * backing-field initializers retain their relative order because initialization order matters.
 */
public fun List<IrDeclaration>.stableDeclarationOrder(): List<IrDeclaration> {
  val strictOrder = hashMapOf<IrDeclaration, Int>()
  var idx = 0
  forEach {
    val shouldPreserveRelativeOrder =
      when (it) {
        is IrProperty -> it.backingField?.initializer != null && !it.isConst
        is IrAnonymousInitializer,
        is IrEnumEntry,
        is IrField -> true
        else -> false
      }
    if (shouldPreserveRelativeOrder) {
      strictOrder[it] = idx++
    }
  }
  return sortedWith { a, b ->
    val strictA = strictOrder[a] ?: Int.MAX_VALUE
    val strictB = strictOrder[b] ?: Int.MAX_VALUE
    if (strictA == strictB) {
      a.render().compareTo(b.render())
    } else {
      strictA - strictB
    }
  }
}

/** Matches Kotlin's stable dump ordering for supertypes and type-parameter bounds. */
public fun List<IrType>.stableTypeOrder(): List<IrType> {
  fun isNonInterfaceType(type: IrType): Boolean =
    type.classifierOrNull?.let { it !is IrClassSymbol || !it.owner.isInterface } ?: true

  val (classTypes, interfaceTypes) = partition(::isNonInterfaceType)
  return classTypes.sortedBy(IrType::render) + interfaceTypes.sortedBy(IrType::render)
}
