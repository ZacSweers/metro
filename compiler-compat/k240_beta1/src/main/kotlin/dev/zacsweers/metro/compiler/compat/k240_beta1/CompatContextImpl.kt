// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k240_beta1

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k240_dev_2124.CompatContextImpl as DelegateType
import dev.zacsweers.metro.compiler.compat.k240_dev_2124.unwrapOr
import kotlin.reflect.KClass
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirExpressionEvaluator
import org.jetbrains.kotlin.fir.expressions.PrivateConstantEvaluatorAPI

public class CompatContextImpl : CompatContext by DelegateType() {
  override fun <T : FirElement> FirExpression.evaluateAsCompat(
    session: FirSession,
    tKlass: KClass<T>,
  ): T? {
    @OptIn(PrivateConstantEvaluatorAPI::class)
    return FirExpressionEvaluator.evaluateExpression(this, session)?.unwrapOr {}
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.4.0-Beta1"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
