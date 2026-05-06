// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k240_dev_2124

import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import org.jetbrains.kotlin.backend.common.extensions.IrGeneratedDeclarationsRegistrar
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl

@JvmInline
internal value class IrAnnotationIrGeneratedDeclarationsRegistrarCompat(
  private val delegate: IrGeneratedDeclarationsRegistrar
) : IrGeneratedDeclarationsRegistrarCompat {
  @Suppress("UNCHECKED_CAST")
  override fun getMetadataVisibleAnnotationsForElement(
    declaration: IrDeclaration
  ): MutableList<IrConstructorCall> =
    delegate.getMetadataVisibleAnnotationsForElement(declaration) as MutableList<IrConstructorCall>

  override fun addMetadataVisibleAnnotationsToElement(
    declaration: IrDeclaration,
    annotations: List<IrConstructorCall>,
  ) = delegate.addMetadataVisibleAnnotationsToElement(declaration, annotations.mapToIrAnnotation())

  override fun registerFunctionAsMetadataVisible(irFunction: IrSimpleFunction) =
    delegate.registerFunctionAsMetadataVisible(
      irFunction.apply { this.annotations = annotations.mapToIrAnnotation() }
    )

  override fun registerConstructorAsMetadataVisible(irConstructor: IrConstructor) =
    delegate.registerConstructorAsMetadataVisible(
      irConstructor.apply { this.annotations = annotations.mapToIrAnnotation() }
    )

  override fun addCustomMetadataExtension(
    irDeclaration: IrDeclaration,
    pluginId: String,
    data: ByteArray,
  ) = delegate.addCustomMetadataExtension(irDeclaration, pluginId, data)

  override fun getCustomMetadataExtension(irDeclaration: IrDeclaration, pluginId: String) =
    delegate.getCustomMetadataExtension(irDeclaration, pluginId)

  private fun List<IrConstructorCall>.mapToIrAnnotation(): List<IrAnnotation> {
    return map { it.toIrAnnotation() }
  }

  private fun IrConstructorCall.toIrAnnotation(): IrAnnotation {
    if (this is IrAnnotation) return this
    val call = this
    return IrAnnotationImpl(
        startOffset = startOffset,
        endOffset = endOffset,
        type = type,
        symbol = symbol,
        typeArgumentsCount = typeArguments.size,
        constructorTypeArgumentsCount = constructorTypeArgumentsCount,
        origin = origin,
        source = source,
      )
      .apply {
        for (param in call.symbol.owner.parameters) {
          arguments[param.indexInParameters] = call.arguments[param.indexInParameters]
        }
      }
  }
}
