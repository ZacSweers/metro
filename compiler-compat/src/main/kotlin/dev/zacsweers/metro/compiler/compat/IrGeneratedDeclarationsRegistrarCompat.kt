// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat

import org.jetbrains.kotlin.backend.common.extensions.IrGeneratedDeclarationsRegistrar
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall

/**
 * Compat wrapper around the real [IrGeneratedDeclarationsRegistrar] with compat for the
 * IrAnnotation migration
 */
interface IrGeneratedDeclarationsRegistrarCompat {
  fun getMetadataVisibleAnnotationsForElement(
    declaration: IrDeclaration
  ): MutableList<IrConstructorCall>

  fun addMetadataVisibleAnnotationsToElement(
    declaration: IrDeclaration,
    annotations: List<IrConstructorCall>,
  )

  fun addMetadataVisibleAnnotationsToElement(
    declaration: IrDeclaration,
    vararg annotations: IrConstructorCall,
  ) {
    addMetadataVisibleAnnotationsToElement(declaration, annotations.toList())
  }

  fun registerFunctionAsMetadataVisible(irFunction: IrSimpleFunction)

  fun registerConstructorAsMetadataVisible(irConstructor: IrConstructor)

  // TODO: KT-63881
  // fun registerPropertyAsMetadataVisible(irProperty: IrProperty)

  fun addCustomMetadataExtension(irDeclaration: IrDeclaration, pluginId: String, data: ByteArray)

  fun getCustomMetadataExtension(irDeclaration: IrDeclaration, pluginId: String): ByteArray?
}

@JvmInline
internal value class IrConstructorCallIrGeneratedDeclarationsRegistrarCompat(
  private val delegate: IrGeneratedDeclarationsRegistrar
) : IrGeneratedDeclarationsRegistrarCompat {
  override fun getMetadataVisibleAnnotationsForElement(declaration: IrDeclaration) =
    delegate.getMetadataVisibleAnnotationsForElement(declaration)

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
