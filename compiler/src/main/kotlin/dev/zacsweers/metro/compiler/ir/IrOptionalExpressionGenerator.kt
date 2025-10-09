package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith

internal class IrOptionalExpressionGenerator(context: IrMetroContext) : IrMetroContext by context {
  /** Generates an `Optional.empty()` call. */
  context(scope: IrBuilderWithScope)
  fun empty(kind: OptionalKind, typeKey: IrTypeKey): IrExpression =
    with(scope) {
      val callee: IrFunctionSymbol
      val typeHint: IrType
      when (kind) {
        OptionalKind.JAVA -> {
          callee = metroSymbols.javaOptionalEmpty
          typeHint = metroSymbols.javaOptional.typeWith(typeKey.type)
        }
      }
      irInvoke(callee = callee, typeHint = typeHint)
    }

  /** Generates an `Optional.of(...)` call around an [instanceExpression]. */
  context(scope: IrBuilderWithScope)
  fun of(kind: OptionalKind, typeKey: IrTypeKey, instanceExpression: IrExpression): IrExpression =
    with(scope) {
      val callee: IrFunctionSymbol
      val typeHint: IrType
      when (kind) {
        OptionalKind.JAVA -> {
          callee = metroSymbols.javaOptionalOf
          typeHint = metroSymbols.javaOptional.typeWith(typeKey.type)
        }
      }
      irInvoke(callee = callee, args = listOf(instanceExpression), typeHint = typeHint)
    }
}
