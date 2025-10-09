package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.name.ClassId

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

context(context: IrMetroContext)
internal fun IrType.optionalKind(declaration: IrDeclaration?): Pair<OptionalKind, IrType>? {
  return optionalKind(context, declaration)
}

internal fun IrType.optionalKind(context: IrMetroContext? = null, declaration: IrDeclaration? = null): Pair<OptionalKind, IrType>? {
  val classId = rawTypeOrNull()?.classId ?: return null
  val kind = when (classId) {
    Symbols.ClassIds.JavaOptional -> OptionalKind.JAVA
    else -> return null
  }
  val type = when (val typeArg = requireSimpleType(declaration).arguments[0]) {
    is IrStarProjection -> {
      val message = "Optional type argument is star projection"
      context?.reportCompat(declaration, MetroDiagnostics.METRO_ERROR, message) ?: error(message)
      return null
    }
    is IrTypeProjection -> typeArg.type
  }
  return kind to type
}

internal enum class OptionalKind(val classId: ClassId) {
  JAVA(Symbols.ClassIds.JavaOptional),
  // Other types would go here
}
