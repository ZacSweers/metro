// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k240_beta1

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k240_dev_2124.CompatContextImpl as DelegateType
import dev.zacsweers.metro.compiler.compat.k240_dev_2124.unwrapOr
import kotlin.reflect.KClass
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirExpressionEvaluator
import org.jetbrains.kotlin.fir.expressions.PrivateConstantEvaluatorAPI
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile

public class CompatContextImpl : CompatContext by DelegateType() {
  override fun <T : FirElement> FirExpression.evaluateAsCompat(
    session: FirSession,
    tKlass: KClass<T>,
  ): T? {
    @OptIn(PrivateConstantEvaluatorAPI::class)
    return FirExpressionEvaluator.evaluateExpression(this, session)?.unwrapOr {}
  }

  override fun <A : Any> IrDiagnosticReporter.reportAt(
    declaration: IrDeclaration,
    factory: KtDiagnosticFactory1<A>,
    a: A,
  ) {
    at(declaration).report(factory, a)
  }

  override fun <A : Any> IrDiagnosticReporter.reportAt(
    element: IrElement,
    file: IrFile,
    factory: KtDiagnosticFactory1<A>,
    a: A,
  ) {
    at(element, file).report(factory, a)
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.4.0-Beta1"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
