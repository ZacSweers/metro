// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k2420_beta2

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k2420_beta1.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.name.Name

public class CompatContextImpl : CompatContext by DelegateType() {
  override fun IrConstructorCall.getAnnotationArgumentCompat(name: Name): IrExpression? {
    return (this as IrAnnotation).argumentMapping[name]
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.4.20-Beta2"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
