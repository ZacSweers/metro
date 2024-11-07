package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.ir.isAnnotatedWithAny
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass

internal class ComponentProcessingTransformer(context: LatticeTransformerContext) :
  IrElementTransformerVoidWithContext(), LatticeTransformerContext by context {
  override fun visitClassNew(declaration: IrClass): IrStatement {
    log("Reading <$declaration>")

    val isAnnotatedWithComponent = declaration.isAnnotatedWithAny(symbols.componentAnnotations)
    if (!isAnnotatedWithComponent) return super.visitClassNew(declaration)

    return super.visitClassNew(declaration)
  }
}
