// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k250_dev_7307

import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.containingClassForStaticMemberAttr
import org.jetbrains.kotlin.fir.declarations.impl.FirDeclarationStatusImpl
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.isObject

/**
 * Repairs companion-block metadata after registering IR-generated declarations
 * ([KT-90383](https://youtrack.jetbrains.com/issue/KT-90383)).
 */
internal class CompanionBlockMetadataRegistrar(
  private val delegate: IrGeneratedDeclarationsRegistrarCompat,
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
    // Kotlin's class registrar creates callable metadata recursively.
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
    // This declaration must be updated in place because the metadata service retains it.
    val status = function.status as FirDeclarationStatusImpl
    status.isStatic = true
    status.isOperator = isOperator
    function.containingClassForStaticMemberAttr = ownerClass.symbol.toLookupTag()
  }
}
