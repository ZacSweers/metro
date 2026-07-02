// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.buildBlockBody
import dev.zacsweers.metro.compiler.ir.graph.expressions.parallelizeSuspendArgs
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.stripOuterProviderOrLazy
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.ir.withIrBuilder
import dev.zacsweers.metro.compiler.ir.wrapInProvider
import dev.zacsweers.metro.compiler.ir.wrapInSuspendProvider
import dev.zacsweers.metro.compiler.newName
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor

/**
 * Generates IR-only private nested `SuspendFactory<T>` classes on a graph impl for bindings that
 * are *transitively* suspend in this graph but whose source declaration is not itself suspend — a
 * non-suspend `@Provides` or a constructor-injected class whose deps resolve suspend bindings.
 *
 * The nested class takes only the binding's dependencies (no graph reference): each dep is held as
 * a `SuspendProvider<X>` when `X` is suspend in this graph, or a `Provider<X>` otherwise (receivers
 * are held as plain instances). Its suspend `invoke()` awaits the suspend deps (optionally in
 * parallel via [parallelizeSuspendArgs]) and calls the source constructor/function directly.
 *
 * Example:
 * ```kotlin
 * class Graph_Impl : Graph {
 *   private class ProvideIntSuspendFactory(
 *     private val s: SuspendProvider<String>,
 *   ) : SuspendFactory<Int> {
 *     override suspend fun invoke(): Int = provideInt(s())
 *   }
 * }
 * ```
 *
 * These classes are generated per graph, only for bindings that need one (i.e. bindings stored in
 * `SuspendProvider<T>` fields), so bytecode cost scales with actual usage. They are not registered
 * in metadata.
 */
internal class GraphSuspendFactoryGenerator(
  metroContext: IrMetroContext,
  private val graphClass: IrClass,
  private val bindingGraph: IrBindingGraph,
) : IrMetroContext by metroContext {

  class NestedSuspendFactory(val irClass: IrClass, val constructor: IrConstructor)

  private val classNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
  private val cache = mutableMapOf<IrTypeKey, NestedSuspendFactory>()

  /**
   * Returns (creating if needed) the nested suspend factory for [binding].
   *
   * @param orderedParams the source callable's parameters in call order — the same order
   *   `generateBindingArguments` maps args (dispatch receiver for non-object provides, context
   *   params, extension receiver, regular params; assisted excluded). The generated constructor's
   *   parameters align 1:1 with this list.
   * @param buildSourceCall builds the source constructor/function call given resolved arg
   *   expressions aligned with [orderedParams].
   */
  fun getOrGenerate(
    binding: IrBinding,
    orderedParams: List<Parameter>,
    buildSourceCall: IrBuilderWithScope.(args: List<IrExpression?>) -> IrExpression,
  ): NestedSuspendFactory =
    cache.getOrPut(binding.typeKey) { generate(binding, orderedParams, buildSourceCall) }

  private fun generate(
    binding: IrBinding,
    orderedParams: List<Parameter>,
    buildSourceCall: IrBuilderWithScope.(args: List<IrExpression?>) -> IrExpression,
  ): NestedSuspendFactory {
    val boundType = binding.typeKey.type

    val factoryClass =
      irFactory
        .buildClass {
          name =
            classNameAllocator.newName(binding.nameHint.capitalizeUS() + "SuspendFactory").asName()
          visibility = DescriptorVisibilities.PRIVATE
        }
        .apply {
          graphClass.addChild(this)
          createThisReceiverParameter()
          superTypes =
            listOf(irBuiltIns.anyType, metroSymbols.metroSuspendFactory.typeWith(boundType))
        }

    // One field per source parameter. Receivers (graph/container instances) are held as plain
    // instances; deps are held as SuspendProvider<X> when suspend in this graph, Provider<X>
    // otherwise. Params already declared as deferred wrappers keep their declared shape.
    data class ParamField(
      val param: Parameter,
      val field: IrField,
      val isSuspendResolution: Boolean,
    )

    val fieldNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
    val paramFields = ArrayList<ParamField>(orderedParams.size)

    val constructor =
      factoryClass
        .addConstructor { isPrimary = true }
        .apply {
          val factoryThisReceiver = factoryClass.thisReceiverOrFail
          val paramInits =
            ArrayList<Pair<org.jetbrains.kotlin.ir.declarations.IrValueParameter, IrField>>()
          for (param in orderedParams) {
            val isReceiver =
              param.isGraphInstance ||
                param.kind == IrParameterKind.DispatchReceiver ||
                param.kind == IrParameterKind.ExtensionReceiver
            val ctxKey = param.contextualTypeKey
            val fieldTypeAndSuspend: Pair<org.jetbrains.kotlin.ir.types.IrType, Boolean> =
              when {
                isReceiver -> ctxKey.toIrType() to false
                ctxKey.isWrappedInSuspendProvider -> ctxKey.toIrType() to false
                ctxKey.isWrapped -> {
                  // Provider<X>/Lazy<X> etc. — X can't be suspend here (validated), hold a
                  // Provider<canonical> and let typeAsProviderArgument adapt.
                  var stripped = ctxKey
                  while (stripped.isWrapped) {
                    stripped = stripped.stripOuterProviderOrLazy()
                  }
                  stripped.wrapInProvider().toIrType() to false
                }
                bindingGraph.isTransitivelySuspend(ctxKey.typeKey) ->
                  ctxKey.wrapInSuspendProvider().toIrType() to true
                else -> ctxKey.wrapInProvider().toIrType() to false
              }
            val (fieldType, isSuspendResolution) = fieldTypeAndSuspend
            val valueParam =
              addValueParameter(
                name = fieldNameAllocator.newName(param.name.asString()).asName(),
                type = fieldType,
              )
            val property =
              factoryClass
                .addProperty {
                  name = valueParam.name
                  visibility = DescriptorVisibilities.PRIVATE
                }
                .apply { addBackingFieldCompat { type = fieldType } }
            val field = property.backingField!!
            paramInits += valueParam to field
            paramFields += ParamField(param, field, isSuspendResolution)
          }

          buildBlockBody {
            +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.primaryConstructor!!)
            for ((valueParam, field) in paramInits) {
              +irSetField(irGet(factoryThisReceiver), field, irGet(valueParam))
            }
          }
        }

    // suspend override fun invoke(): T
    factoryClass
      .addFunction {
        name = Symbols.Names.invoke
        returnType = boundType
        isSuspend = true
      }
      .apply {
        isOperator = true
        val localDispatchReceiver =
          factoryClass.thisReceiverOrFail.copyTo(this, type = factoryClass.defaultType)
        setDispatchReceiver(localDispatchReceiver)
        overriddenSymbols = listOf(metroSymbols.suspendProviderInvoke)

        val invokeFunction = this
        body =
          withIrBuilder(symbol) {
            val argPairs = paramFields.map { (param, field, isSuspendResolution) ->
              val fieldAccess = irGetField(irGet(localDispatchReceiver), field)
              val arg =
                typeAsProviderArgument(
                  param.contextualTypeKey,
                  fieldAccess,
                  isAssisted = false,
                  isGraphInstance = param.isGraphInstance,
                )
              arg to isSuspendResolution
            }
            irExprBodySafe(
              parallelizeSuspendArgs(
                args = argPairs,
                parent = invokeFunction,
                resultType = boundType,
              ) { resolved ->
                buildSourceCall(resolved)
              }
            )
          }
      }

    return NestedSuspendFactory(factoryClass, constructor)
  }
}
