package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.ir.IrAnnotation
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType

internal data class TypeKey(val type: IrType, val qualifier: IrAnnotation? = null) {
  companion object {
    fun from(context: LatticeTransformerContext, parameter: IrFunction): TypeKey =
      TypeKey(
        parameter.returnType,
        with(context) { parameter.qualifierAnnotation() },
      )

    fun from(context: LatticeTransformerContext, parameter: IrValueParameter): TypeKey =
      TypeKey(
        parameter.type,
        with(context) { parameter.qualifierAnnotation() },
      )
  }
}