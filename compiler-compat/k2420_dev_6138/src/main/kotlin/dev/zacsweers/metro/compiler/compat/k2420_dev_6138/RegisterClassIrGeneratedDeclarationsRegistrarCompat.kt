// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k2420_dev_6138

import org.jetbrains.kotlin.backend.common.extensions.IrGeneratedDeclarationsRegistrar
import org.jetbrains.kotlin.ir.declarations.IrClass

/** Extends the `IrAnnotation`-based registrar compat with `registerClassAsMetadataVisible`. */
internal class RegisterClassIrGeneratedDeclarationsRegistrarCompat(
  delegate: IrGeneratedDeclarationsRegistrar
) : IrAnnotationIrGeneratedDeclarationsRegistrarCompat(delegate) {
  override fun registerClassAsMetadataVisible(irClass: IrClass) =
    delegate.registerClassAsMetadataVisible(
      irClass.apply {
        convertAnnotations()
        typeParameters.forEach { it.convertAnnotations() }
      }
    )
}
