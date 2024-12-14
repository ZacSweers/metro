package dev.zacsweers.lattice.transformers

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType

internal data class ContextualTypeKey(
  val typeKey: TypeKey,
  val isWrappedInProvider: Boolean,
  val isWrappedInLazy: Boolean,
  val isLazyWrappedInProvider: Boolean,
) {
  // TODO cache these in ComponentTransformer or shared transformer data
  companion object {
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun from(
      context: LatticeTransformerContext,
      function: IrSimpleFunction,
      type: IrType = function.returnType,
    ): ContextualTypeKey =
      type.asTypeMetadata(
        context,
        with(context) {
          function.correspondingPropertySymbol?.owner?.qualifierAnnotation()
            ?: function.qualifierAnnotation()
        },
      )

    fun from(
      context: LatticeTransformerContext,
      parameter: IrValueParameter,
      type: IrType = parameter.type,
    ): ContextualTypeKey =
      type.asTypeMetadata(context, with(context) { parameter.qualifierAnnotation() })
  }
}