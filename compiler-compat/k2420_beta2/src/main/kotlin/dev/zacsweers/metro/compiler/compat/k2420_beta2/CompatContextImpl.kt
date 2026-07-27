// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k2420_beta2

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.k2420_beta1.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.builder.buildResolvedQualifier
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

public class CompatContextImpl : CompatContext by DelegateType() {
  override fun buildResolvedQualifierCompat(
    classId: ClassId,
    classSymbol: FirClassLikeSymbol<*>,
    classType: ConeKotlinType,
  ): FirResolvedQualifier {
    return buildResolvedQualifier {
      packageFqName = classId.packageFqName
      relativeClassFqName = classId.relativeClassName
      qualifierSymbol = classSymbol
      resolvedToCompanionObject = false
      explicitParent = buildResolvedQualifier {
        packageFqName = classId.packageFqName
        resolvedToCompanionObject = false
      }
      coneTypeOrNull = classType
    }
  }

  override fun IrConstructorCall.getAnnotationArgumentCompat(name: Name): IrExpression? {
    return (this as IrAnnotation).argumentMapping[name]
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.4.20-Beta2"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
