// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.expressions

import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.extensionReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.irLambda
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType

/**
 * Wraps the construction of a value whose dependencies include multiple suspend resolutions in
 * `coroutineScope { val a0 = async { … }; …; build(a0.await(), …) }` so each suspend resolution
 * runs in parallel.
 *
 * - Falls back to a serial inline build if fewer than 2 args are flagged suspend.
 * - Falls back to a serial inline build if the kotlinx.coroutines runtime symbols aren't on the
 *   classpath, or if the parallel-suspend option is off.
 *
 * The caller's enclosing IR function must already be `suspend` (or inside a suspend lambda) since
 * `coroutineScope` is itself a suspend function. Callers ensure this by only invoking the helper
 * from suspend accessor bodies and suspend factory invoke bodies.
 *
 * @param args each pair is `(expression?, isSuspendResolution)`. A null expression means the
 *   parameter is to be filled with its default value at the call site (no expression, no
 *   parallelisation contribution). `isSuspendResolution = true` means computing the expression
 *   requires a suspend call (so it benefits from parallelisation).
 * @param parent the enclosing IR declaration parent for the synthetic lambda(s).
 * @param resultType the result type of [build]'s expression.
 * @param build a builder for the final call/expression once each arg is resolved. Receives the
 *   resolved expressions in the same order as [args]. Null entries pass through unchanged.
 */
context(scope: IrBuilderWithScope, context: IrMetroContext)
internal fun parallelizeSuspendArgs(
  args: List<Pair<IrExpression?, Boolean>>,
  parent: IrDeclarationParent,
  resultType: IrType,
  build: IrBuilderWithScope.(resolved: List<IrExpression?>) -> IrExpression,
): IrExpression {
  val symbols = context.metroSymbols
  val suspendCount = args.count { it.second && it.first != null }
  val coroutineScopeFn = symbols.coroutineScopeFunction
  val asyncFn = symbols.asyncFunction
  val awaitFn = symbols.deferredAwait
  val coroutineScopeClass = symbols.coroutineScopeClass
  val deferredClass = symbols.deferredClass

  val canParallelize =
    context.options.enableSuspendProviders &&
      suspendCount >= 2 &&
      coroutineScopeFn != null &&
      asyncFn != null &&
      awaitFn != null &&
      coroutineScopeClass != null &&
      deferredClass != null

  if (!canParallelize) {
    return scope.build(args.map { it.first })
  }

  val coroutineScopeType = coroutineScopeClass!!.owner.defaultType

  // Build: coroutineScope { val a0 = async { expr0 }; ... ; build(a0.await(), ...) }
  val outerLambda =
    with(context.pluginContext) {
      irLambda(
        parent = parent,
        receiverParameter = coroutineScopeType,
        valueParameters = emptyList(),
        returnType = resultType,
        suspend = true,
      ) { lambdaFn ->
        val coroutineScopeReceiver = lambdaFn.extensionReceiverParameterCompat!!

        // For each suspend arg, declare `val deferredN = scope.async { expr }`.
        val deferredVars = mutableListOf<org.jetbrains.kotlin.ir.declarations.IrVariable?>()
        for ((expr, isSuspend) in args) {
          if (isSuspend && expr != null) {
            val argType = expr.type
            val asyncLambda =
              irLambda(
                parent = lambdaFn,
                receiverParameter = coroutineScopeType,
                valueParameters = emptyList(),
                returnType = argType,
                suspend = true,
              ) { _ ->
                +irReturn(expr)
              }
            val asyncCall =
              irInvoke(
                callee = asyncFn!!,
                extensionReceiver = irGet(coroutineScopeReceiver),
                typeArgs = listOf(argType),
                typeHint = deferredClass!!.typeWith(argType),
                args = listOf(asyncLambda),
              )
            val tmp = irTemporary(asyncCall, nameHint = "deferred")
            +tmp
            deferredVars += tmp
          } else {
            deferredVars += null
          }
        }

        // Build the final call, replacing suspend args with deferred.await()
        val resolved: List<IrExpression?> = args.mapIndexed { i, (expr, isSuspend) ->
          when {
            expr == null -> null
            isSuspend -> {
              val tmp = deferredVars[i]!!
              irInvoke(callee = awaitFn!!, dispatchReceiver = irGet(tmp), typeHint = expr.type)
            }
            else -> expr
          }
        }
        +irReturn(build(resolved))
      }
    }

  return with(scope) {
    irInvoke(
      callee = coroutineScopeFn!!,
      typeArgs = listOf(resultType),
      typeHint = resultType,
      args = listOf(outerLambda),
    )
  }
}
