// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k230

import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import org.jetbrains.kotlin.backend.common.extensions.IrGeneratedDeclarationsRegistrar
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall

@JvmInline
internal value class IrConstructorCallIrGeneratedDeclarationsRegistrarCompat(
  private val delegate: IrGeneratedDeclarationsRegistrar
) : IrGeneratedDeclarationsRegistrarCompat {
  override fun getMetadataVisibleAnnotationsForElement(declaration: IrDeclaration) =
    mutableListOf<IrConstructorCall>()

  override fun addMetadataVisibleAnnotationsToElement(
    declaration: IrDeclaration,
    annotations: List<IrConstructorCall>,
  ) = delegate.addMetadataVisibleAnnotationsToElement(declaration, annotations)

  override fun registerFunctionAsMetadataVisible(irFunction: IrSimpleFunction) =
    delegate.registerFunctionAsMetadataVisible(irFunction)

  override fun registerConstructorAsMetadataVisible(irConstructor: IrConstructor) =
    delegate.registerConstructorAsMetadataVisible(irConstructor)

  override fun addCustomMetadataExtension(
    irDeclaration: IrDeclaration,
    pluginId: String,
    data: ByteArray,
  ) = delegate.addCustomMetadataExtension(irDeclaration, pluginId, data)

  override fun getCustomMetadataExtension(irDeclaration: IrDeclaration, pluginId: String) =
    delegate.getCustomMetadataExtension(irDeclaration, pluginId)
}
