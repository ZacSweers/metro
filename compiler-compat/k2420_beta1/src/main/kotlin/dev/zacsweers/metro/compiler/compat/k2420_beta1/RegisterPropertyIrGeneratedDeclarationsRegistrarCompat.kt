// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k2420_beta1

import dev.zacsweers.metro.compiler.compat.k2420_dev_6138.IrAnnotationIrGeneratedDeclarationsRegistrarCompat
import org.jetbrains.kotlin.backend.common.extensions.IrGeneratedDeclarationsRegistrar
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty

/** Extends the registrar compat with class and property registration. */
internal class RegisterPropertyIrGeneratedDeclarationsRegistrarCompat(
  delegate: IrGeneratedDeclarationsRegistrar
) : IrAnnotationIrGeneratedDeclarationsRegistrarCompat(delegate) {
  override fun registerClassAsMetadataVisible(irClass: IrClass) =
    delegate.registerClassAsMetadataVisible(
      irClass.apply {
        convertAnnotations()
        typeParameters.forEach { it.convertAnnotations() }
      }
    )

  override fun registerPropertyAsMetadataVisible(irProperty: IrProperty) {
    irProperty.convertAnnotations()
    irProperty.getter?.convertFunctionAnnotations()
    irProperty.setter?.convertFunctionAnnotations()
    irProperty.backingField?.convertAnnotations()
    delegate.registerPropertyAsMetadataVisible(irProperty)
  }
}
