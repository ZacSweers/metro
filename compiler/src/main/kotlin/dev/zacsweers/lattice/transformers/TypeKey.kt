package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.ir.IrAnnotation
import dev.zacsweers.lattice.unsafeLazy
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.render

internal data class TypeKey(val type: IrType, val qualifier: IrAnnotation? = null) {
  private val cachedToString by unsafeLazy {
    buildString {
      qualifier?.let {
        append("@" + it.ir.render())
        append(" ")
      }
      append(type.render())
    }
  }

  override fun toString(): String = cachedToString

  companion object {
    fun from(
      context: LatticeTransformerContext,
      function: IrFunction,
      type: IrType = function.returnType,
    ): TypeKey = TypeKey(type, with(context) { function.qualifierAnnotation() })

    fun from(
      context: LatticeTransformerContext,
      parameter: IrValueParameter,
      type: IrType = parameter.type,
    ): TypeKey = TypeKey(type, with(context) { parameter.qualifierAnnotation() })
  }
}
