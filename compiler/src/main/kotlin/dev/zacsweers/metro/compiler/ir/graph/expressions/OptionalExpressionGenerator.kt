// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.expressions

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.Symbols
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

internal object IrOptionalExpressionGenerator : IrWrappedTypeGenerator {
  override val key: String = "Optional"

  context(context: IrMetroContext, scope: IrBuilderWithScope)
  override fun generate(
    binding: IrBinding.CustomWrapper,
    instanceExpression: IrExpression?,
  ): IrExpression =
    with(scope) {
      val targetType = binding.typeKey.type
      val kind = targetType.optionalKind() ?: reportCompilerBug("Optional kind not set on $binding")

      if (instanceExpression == null) {
        return empty(kind, binding.wrappedType)
      }
      return of(kind, binding.wrappedType, instanceExpression)
    }

  /** Generates an `Optional.empty()` call. */
  context(context: IrMetroContext)
  fun IrBuilderWithScope.empty(kind: OptionalKind, type: IrType): IrExpression {
    val callee: IrFunctionSymbol
    val typeHint: IrType
    when (kind) {
      OptionalKind.JAVA -> {
        callee = context.metroSymbols.javaOptionalEmpty
        typeHint = context.metroSymbols.javaOptional.typeWith(type)
      }
    }
    return irInvoke(callee = callee, typeArgs = listOf(type), typeHint = typeHint)
  }

  /** Generates an `Optional.of(...)` call around an [instanceExpression]. */
  context(context: IrMetroContext)
  fun IrBuilderWithScope.of(
    kind: OptionalKind,
    type: IrType,
    instanceExpression: IrExpression,
  ): IrExpression {
    val callee: IrFunctionSymbol
    val typeHint: IrType
    when (kind) {
      OptionalKind.JAVA -> {
        callee = context.metroSymbols.javaOptionalOf
        typeHint = context.metroSymbols.javaOptional.typeWith(type)
      }
    }
    return irInvoke(
      callee = callee,
      args = listOf(instanceExpression),
      typeArgs = listOf(type),
      typeHint = typeHint,
    )
  }
}

internal fun IrType.optionalKind(): OptionalKind? {
  val classId = rawTypeOrNull()?.classId ?: return null
  return when (classId) {
    Symbols.ClassIds.JavaOptional -> OptionalKind.JAVA
    else -> return null
  }
}

context(context: IrMetroContext)
internal fun IrType.optionalType(declaration: IrDeclaration?): IrType? {
  return when (val typeArg = requireSimpleType(declaration).arguments[0]) {
    is IrStarProjection -> {
      val message = "Optional type argument is star projection"
      declaration?.let { context.reportCompat(it, MetroDiagnostics.METRO_ERROR, message) }
        ?: error(message)
      return null
    }
    is IrTypeProjection -> typeArg.type
  }
}

internal enum class OptionalKind(val classId: ClassId) {
  JAVA(Symbols.ClassIds.JavaOptional)
  // Other types would go here
}
