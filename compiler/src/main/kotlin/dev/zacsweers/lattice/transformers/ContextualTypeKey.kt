package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.expectAs
import dev.zacsweers.lattice.ir.IrAnnotation
import dev.zacsweers.lattice.ir.rawTypeOrNull
import kotlin.collections.contains
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.render

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
      type.asContextualTypeKey(
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
      type.asContextualTypeKey(context, with(context) { parameter.qualifierAnnotation() })
  }
}

internal fun IrType.asContextualTypeKey(
  context: LatticeTransformerContext,
  qualifierAnnotation: IrAnnotation?,
): ContextualTypeKey {
  check(this is IrSimpleType) { "Unrecognized IrType '${javaClass}': ${render()}" }

  val declaredType = this
  val rawTypeClass = declaredType.rawTypeOrNull()
  val rawType = rawTypeClass?.classId

  val isWrappedInProvider = rawType in context.symbols.providerTypes
  val isWrappedInLazy = rawType in context.symbols.lazyTypes
  val isLazyWrappedInProvider =
    isWrappedInProvider &&
      declaredType.arguments[0].typeOrFail.rawTypeOrNull()?.classId in context.symbols.lazyTypes

  val type =
    when {
      isLazyWrappedInProvider ->
        declaredType.arguments
          .single()
          .typeOrFail
          .expectAs<IrSimpleType>()
          .arguments
          .single()
          .typeOrFail
      isWrappedInProvider || isWrappedInLazy -> declaredType.arguments.single().typeOrFail
      else -> declaredType
    }
  val typeKey = TypeKey(type, qualifierAnnotation)
  return ContextualTypeKey(
    typeKey = typeKey,
    isWrappedInProvider = isWrappedInProvider,
    isWrappedInLazy = isWrappedInLazy,
    isLazyWrappedInProvider = isLazyWrappedInProvider,
  )
}