// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression

/**
 * Covers replacing the `asContribution()` compiler intrinsic with just the instance itself.
 * Essentially, this _removes_ the function call and trusts that FIR has correctly validated this
 * cast.
 */
internal object AsContributionTransformer {
  fun visitCall(expression: IrCall, metroContext: IrMetroContext): IrExpression? {
    val callee = expression.symbol.owner
    when (callee.symbol) {
      metroContext.metroSymbols.asContribution -> {
        return metroContext.createIrBuilder(expression.symbol).run {
          // The ugly Kotlin 2.1.20+ way of getting the receiver
          expression.arguments[
              expression.symbol.owner.parameters.indexOfFirst {
                it.kind == IrParameterKind.ExtensionReceiver
              }]!!
        }
      }
    }

    return null
  }
}
