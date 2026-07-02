// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.buildBlockBody
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameter.AssistedParameterKey.Companion.toAssistedParameterKey
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.stripOuterProviderOrLazy
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.ir.withIrBuilder
import dev.zacsweers.metro.compiler.ir.wrapInProvider
import dev.zacsweers.metro.compiler.ir.wrapInSuspendProvider
import dev.zacsweers.metro.compiler.newName
import dev.zacsweers.metro.compiler.reportCompilerBug
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
 * are held as plain instances). Its suspend `invoke()` awaits the suspend deps and calls the source
 * constructor/function directly.
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

  /**
   * One field per source parameter. Receivers (graph/container instances) are held as plain
   * instances; deps are held as SuspendProvider<X> when suspend in this graph, Provider<X>
   * otherwise. Params already declared as deferred wrappers keep their declared shape.
   */
  private data class ParamField(val param: Parameter, val field: IrField)

  private fun buildShell(name: String, supertype: org.jetbrains.kotlin.ir.types.IrType): IrClass =
    irFactory
      .buildClass {
        this.name = classNameAllocator.newName(name).asName()
        visibility = DescriptorVisibilities.PRIVATE
      }
      .apply {
        graphClass.addChild(this)
        createThisReceiverParameter()
        superTypes = listOf(irBuiltIns.anyType, supertype)
      }

  /** Adds a primary constructor with one param + backing field per [orderedParams] entry. */
  private fun IrClass.addDepsConstructor(
    orderedParams: List<Parameter>,
    paramFields: MutableList<ParamField>,
  ): IrConstructor {
    val factoryClass = this
    return addConstructor { isPrimary = true }
      .apply {
        val fieldNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
        val factoryThisReceiver = factoryClass.thisReceiverOrFail
        val paramInits =
          ArrayList<Pair<org.jetbrains.kotlin.ir.declarations.IrValueParameter, IrField>>()
        for (param in orderedParams) {
          val isReceiver =
            param.isGraphInstance ||
              param.kind == IrParameterKind.DispatchReceiver ||
              param.kind == IrParameterKind.ExtensionReceiver
          val ctxKey = param.contextualTypeKey
          val fieldType: org.jetbrains.kotlin.ir.types.IrType =
            when {
              isReceiver -> ctxKey.toIrType()
              ctxKey.isWrappedInSuspendProvider -> ctxKey.toIrType()
              ctxKey.isWrapped -> {
                // Provider<X>/Lazy<X> etc. — X can't be suspend here (validated), hold a
                // Provider<canonical> and let typeAsProviderArgument adapt.
                var stripped = ctxKey
                while (stripped.isWrapped) {
                  stripped = stripped.stripOuterProviderOrLazy()
                }
                stripped.wrapInProvider().toIrType()
              }
              bindingGraph.isTransitivelySuspend(ctxKey.typeKey) ->
                ctxKey.wrapInSuspendProvider().toIrType()
              else -> ctxKey.wrapInProvider().toIrType()
            }
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
          paramFields += ParamField(param, field)
        }

        buildBlockBody {
          +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.primaryConstructor!!)
          for ((valueParam, field) in paramInits) {
            +irSetField(irGet(factoryThisReceiver), field, irGet(valueParam))
          }
        }
      }
  }

  private fun generate(
    binding: IrBinding,
    orderedParams: List<Parameter>,
    buildSourceCall: IrBuilderWithScope.(args: List<IrExpression?>) -> IrExpression,
  ): NestedSuspendFactory {
    val boundType = binding.typeKey.type

    val factoryClass =
      buildShell(
        binding.nameHint.capitalizeUS() + "SuspendFactory",
        metroSymbols.metroSuspendFactory.typeWith(boundType),
      )

    val paramFields = ArrayList<ParamField>(orderedParams.size)
    val constructor = factoryClass.addDepsConstructor(orderedParams, paramFields)

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

        body =
          withIrBuilder(symbol) {
            val args = paramFields.map { (param, field) ->
              val fieldAccess = irGetField(irGet(localDispatchReceiver), field)
              typeAsProviderArgument(
                param.contextualTypeKey,
                fieldAccess,
                isAssisted = false,
                isGraphInstance = param.isGraphInstance,
              )
            }
            irExprBodySafe(buildSourceCall(args))
          }
      }

    return NestedSuspendFactory(factoryClass, constructor)
  }

  /**
   * Returns (creating if needed) an IR-only private nested impl of [binding]'s assisted factory
   * interface for graphs where the target consumes suspend bindings. Unlike the shared per-class
   * `*_Impl` (which delegates to the target's plain `Factory<T>`), this impl holds the target's
   * non-assisted deps directly (as `SuspendProvider<X>`/`Provider<X>` per graph suspend-ness) and
   * its suspend SAM override awaits them before calling the target constructor.
   *
   * @param buildTargetCall builds the target constructor call given resolved args aligned with the
   *   target constructor's full parameter list (assisted + non-assisted, in declaration order).
   */
  fun getOrGenerateAssistedImpl(
    binding: IrBinding.AssistedFactory,
    buildTargetCall: IrBuilderWithScope.(args: List<IrExpression?>) -> IrExpression,
  ): NestedSuspendFactory =
    cache.getOrPut(binding.typeKey) { generateAssistedImpl(binding, buildTargetCall) }

  private fun generateAssistedImpl(
    binding: IrBinding.AssistedFactory,
    buildTargetCall: IrBuilderWithScope.(args: List<IrExpression?>) -> IrExpression,
  ): NestedSuspendFactory {
    val target = binding.targetBinding
    val samFunction = binding.function
    val ctorParams = target.classFactory.targetFunctionParameters.regularParameters
    val nonAssistedParams = ctorParams.filterNot { it.isAssisted }

    val implClass =
      buildShell(binding.nameHint.capitalizeUS() + "SuspendImpl", binding.typeKey.type)

    val paramFields = ArrayList<ParamField>(nonAssistedParams.size)
    val constructor = implClass.addDepsConstructor(nonAssistedParams, paramFields)
    val fieldsByParam = paramFields.associateBy { it.param }

    // Suspend SAM override: assisted params flow straight through to the target constructor,
    // non-assisted deps resolve from the fields.
    implClass
      .addFunction {
        name = samFunction.name
        returnType = samFunction.returnType
        isSuspend = samFunction.isSuspend
      }
      .apply {
        val localDispatchReceiver =
          implClass.thisReceiverOrFail.copyTo(this, type = implClass.defaultType)
        setDispatchReceiver(localDispatchReceiver)
        overriddenSymbols = listOf(samFunction.symbol)

        val samValueParams =
          samFunction.regularParameters.map { samParam ->
            addValueParameter(name = samParam.name, type = samParam.type)
          }
        // Match SAM params to the target constructor's assisted params by assisted key
        // (type + @Assisted identifier), same as AssistedFactoryTransformer.
        val samParamsByKey =
          samFunction.regularParameters
            .mapIndexed { i, samParam ->
              val typeKey = binding.parameters.regularParameters[i].typeKey
              samParam.toAssistedParameterKey(metroSymbols, typeKey) to samValueParams[i]
            }
            .toMap()

        body =
          withIrBuilder(symbol) {
            val args = ctorParams.map { param ->
              if (param.isAssisted) {
                val samParam =
                  samParamsByKey[param.assistedParameterKey]
                    ?: reportCompilerBug(
                      "Could not find matching assisted parameter for ${param.assistedParameterKey} on ${implClass.name}"
                    )
                irGet(samParam)
              } else {
                val (_, field) = fieldsByParam.getValue(param)
                typeAsProviderArgument(
                  param.contextualTypeKey,
                  irGetField(irGet(localDispatchReceiver), field),
                  isAssisted = false,
                  isGraphInstance = param.isGraphInstance,
                )
              }
            }
            irExprBodySafe(buildTargetCall(args))
          }
      }

    return NestedSuspendFactory(implClass, constructor)
  }
}
