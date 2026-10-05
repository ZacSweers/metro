// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k250_beta1

import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.containingClassForStaticMemberAttr
import org.jetbrains.kotlin.fir.declarations.impl.FirDeclarationStatusImpl
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.isObject

/** Supplies the companion-block metadata flags omitted by Beta1's IR declaration registrar. */
internal class CompanionBlockMetadataRegistrar(
  private val delegate: IrGeneratedDeclarationsRegistrarCompat
) : IrGeneratedDeclarationsRegistrarCompat by delegate {
  override fun registerFunctionAsMetadataVisible(irFunction: IrSimpleFunction) {
    delegate.registerFunctionAsMetadataVisible(irFunction)
    irFunction.repairCompanionBlockMetadata()
  }

  override fun registerClassAsMetadataVisible(irClass: IrClass) {
    delegate.registerClassAsMetadataVisible(irClass)
    irClass.repairCompanionBlockMetadata()
  }

  private fun IrClass.repairCompanionBlockMetadata() {
    // Class registration recursively creates callable metadata inside the upstream registrar.
    for (declaration in declarations) {
      when (declaration) {
        is IrSimpleFunction -> declaration.repairCompanionBlockMetadata()
        is IrClass -> declaration.repairCompanionBlockMetadata()
        else -> {}
      }
    }
  }

  private fun IrSimpleFunction.repairCompanionBlockMetadata() {
    if (dispatchReceiverParameter != null) {
      return
    }
    val owner = parent as? IrClass ?: return
    if (owner.isObject) {
      return
    }
    val function = (metadata as? FirMetadataSource.Function)?.fir ?: return
    val ownerClass = (owner.metadata as? FirMetadataSource.Class)?.fir ?: return
    // The metadata service retains this declaration, so its flags must be updated in place.
    val status = function.status as FirDeclarationStatusImpl
    status.isStatic = true
    status.isOperator = isOperator
    function.containingClassForStaticMemberAttr = ownerClass.symbol.toLookupTag()
  }
}
