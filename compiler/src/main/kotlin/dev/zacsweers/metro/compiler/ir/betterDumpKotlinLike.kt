// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.compat.CompatContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.util.KotlinLikeDumpOptions

/**
 * Dumps Kotlin-like IR with customizable class names using the selected compiler compatibility
 * implementation.
 *
 * The default transformer renders nested class names relative to the enclosing scope.
 *
 * @param classNameTransformer receives the current container and the declaration whose name is
 *   being rendered.
 */
context(compat: CompatContext)
public fun IrElement.betterDumpKotlinLike(
  options: KotlinLikeDumpOptions = KotlinLikeDumpOptions(),
  classNameTransformer: (context: IrDeclaration?, declaration: IrDeclarationWithName) -> String =
    ::nestedClassNameRenderer,
): String {
  return with(compat) {
    this@betterDumpKotlinLike.dumpKotlinLikeCompat(options, classNameTransformer)
  }
}
