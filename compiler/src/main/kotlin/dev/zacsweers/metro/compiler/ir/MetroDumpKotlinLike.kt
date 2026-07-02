// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.compat.CompatContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.util.KotlinLikeDumpOptions

context(compatContext: CompatContext)
public fun IrElement.metroDumpKotlinLike(
  options: KotlinLikeDumpOptions = KotlinLikeDumpOptions(),
  classNameTransformer: (context: IrDeclaration?, declaration: IrDeclarationWithName) -> String =
    ::nestedClassNameRenderer,
): String {
  return with(compatContext) {
    this@metroDumpKotlinLike.dumpKotlinLikeCompat(options, classNameTransformer) {
      betterDumpKotlinLike(options, classNameTransformer)
    }
  }
}
