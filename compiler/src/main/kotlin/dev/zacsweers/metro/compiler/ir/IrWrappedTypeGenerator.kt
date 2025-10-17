// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.expressions.IrExpression

internal interface IrWrappedTypeGenerator {
  val key: String

  /**
   * Generates an expression for the given [binding] and [instanceExpression].
   *
   * If this binding allows absent and its dependency has a default, [instanceExpression] is null.
   */
  context(context: IrMetroContext, scope: IrBuilderWithScope)
  fun generate(binding: IrBinding.CustomWrapper, instanceExpression: IrExpression?): IrExpression
}
