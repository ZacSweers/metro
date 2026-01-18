// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.buildBlockBody
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.graph.expressions.BindingExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.expressions.GraphExpressionGenerator
import dev.zacsweers.metro.compiler.ir.graph.sharding.ShardExpressionContext
import dev.zacsweers.metro.compiler.ir.irGetProperty
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.stripOuterProviderOrLazy
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.backend.common.lower.irThrow
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irConcat
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.addArgument
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name

/**
 * Generates a SwitchingProvider nested class for fastInit mode.
 *
 * SwitchingProvider consolidates provider instantiation into a single class with a switch
 * statement, reducing class loading time by deferring binding creation until first access.
 *
 * Example generated structure:
 * ```kotlin
 * private class SwitchingProvider<T>(
 *   private val graph: GraphImpl,
 *   private val id: Int
 * ) : Provider<T> {
 *   override fun invoke(): T = when (id) {
 *     0 -> ServiceA() as T
 *     1 -> ServiceB(graph.serviceAProvider()) as T
 *     else -> error("Unknown switching id")
 *   }
 * }
 * ```
 */
internal class SwitchingProviderGenerator(
  metroContext: IrMetroContext,
  private val graphOrShardClass: IrClass,
  private val switchingBindings: List<SwitchingBinding>,
  private val expressionGeneratorFactory: GraphExpressionGenerator.Factory,
  private val shardExprContext: ShardExpressionContext?,
) : IrMetroContext by metroContext {

  /**
   * Represents a binding that will be dispatched via the SwitchingProvider.
   *
   * @param id The unique ID for this binding within the switch statement
   * @param binding The binding to create
   * @param contextKey The contextual type key for accessing dependencies
   */
  data class SwitchingBinding(
    val id: Int,
    val binding: IrBinding,
    val contextKey: IrContextualTypeKey,
  )

  data class SwitchingProvider(val irClass: IrClass, val constructor: IrConstructor)

  /**
   * Generates the SwitchingProvider nested class.
   *
   * @return The generated class, or null if there are no switching bindings
   */
  fun generate(): SwitchingProvider? {
    if (switchingBindings.isEmpty()) {
      return null
    }

    val switchingClass =
      irFactory
        .buildClass {
          // TODO nameallocator
          name = "SwitchingProvider".asName()
          visibility = DescriptorVisibilities.PRIVATE
        }
        .apply {
          graphOrShardClass.addChild(this)

          createThisReceiverParameter()
        }
    // Add type parameter T
    val typeParam = switchingClass.addTypeParameter("T", irBuiltIns.anyNType)

    // Implement Provider<T>
    switchingClass.superTypes =
      listOf(irBuiltIns.anyType, metroSymbols.metroProvider.typeWith(typeParam.defaultType))

    // Add constructor with (graph, id) params and backing fields
    val (constructor, graphProperty, idProperty) = switchingClass.addConstructorAndFields()

    // Implement invoke(): T
    switchingClass.addInvokeFunction(typeParam, graphProperty, idProperty)

    return SwitchingProvider(switchingClass, constructor)
  }

  /**
   * Adds the constructor and backing field properties for graph reference and ID.
   *
   * @return Triple of (constructor, graphProperty, idProperty)
   */
  private fun IrClass.addConstructorAndFields(): Triple<IrConstructor, IrProperty, IrProperty> {
    // TODO switch to direct initializers?
    val graphProperty =
      addProperty {
          name = Name.identifier(Symbols.StringNames.GRAPH)
          visibility = DescriptorVisibilities.PRIVATE
        }
        .apply { addBackingFieldCompat { type = graphOrShardClass.defaultType } }

    val idProperty =
      addProperty {
          name = Name.identifier("id")
          visibility = DescriptorVisibilities.PRIVATE
        }
        .apply { addBackingFieldCompat { type = irBuiltIns.intType } }

    // Add constructor
    val constructor =
      addConstructor { isPrimary = true }
        .apply {
          val graphParam = addValueParameter {
            name = Name.identifier(Symbols.StringNames.GRAPH)
            type = graphOrShardClass.defaultType
          }
          val idParam = addValueParameter {
            name = Name.identifier("id")
            type = irBuiltIns.intType
          }

          val switchingThisReceiver = this@addConstructorAndFields.thisReceiverOrFail

          buildBlockBody {
            // Call super constructor (Any)
            +irDelegatingConstructorCall(irBuiltIns.anyClass.owner.primaryConstructor!!)

            // Initialize graph field
            +irSetField(
              irGet(switchingThisReceiver),
              graphProperty.backingField!!,
              irGet(graphParam),
            )

            // Initialize id field
            +irSetField(irGet(switchingThisReceiver), idProperty.backingField!!, irGet(idParam))
          }
        }

    return Triple(constructor, graphProperty, idProperty)
  }

  /** Adds the `invoke(): T` function that implements Provider<T>. */
  private fun IrClass.addInvokeFunction(
    typeParam: IrTypeParameter,
    graphProperty: IrProperty,
    idProperty: IrProperty,
  ) {
    addFunction {
        name = Symbols.Names.invoke
        returnType = typeParam.defaultType
        modality = Modality.OPEN
      }
      .apply {
        // Pass explicit type to avoid type parameter remapping (class has T, function has none)
        val localDispatchReceiver =
          this@addInvokeFunction.thisReceiverOrFail.copyTo(
            this,
            type = this@addInvokeFunction.defaultType,
          )
        setDispatchReceiver(localDispatchReceiver)
        overriddenSymbols = listOf(metroSymbols.providerInvoke)

        body =
          createIrBuilder(symbol).run {
            // Generate: when (id) { 0 -> ..., 1 -> ..., else -> first branch result (fallback) }
            val whenExpr =
              generateWhenExpression(
                switchingProviderThisReceiver = localDispatchReceiver,
                graphProperty = graphProperty,
                idProperty = idProperty,
                typeParam = typeParam,
              )

            irBlockBody { +irReturn(whenExpr) }
          }
      }
  }

  /** Generates the `when` expression that dispatches based on the ID. */
  context(scope: IrBuilderWithScope)
  private fun generateWhenExpression(
    switchingProviderThisReceiver: IrValueParameter,
    graphProperty: IrProperty,
    idProperty: IrProperty,
    typeParam: IrTypeParameter,
  ): IrExpression =
    with(scope) {
      // Create a ShardExpressionContext for SwitchingProvider that ensures all property access
      // goes through the graph property
      val switchingProviderContext =
        ShardExpressionContext(
          graphProperty = graphProperty,
          shardThisReceiver = switchingProviderThisReceiver,
          currentShardIndex = ShardExpressionContext.SWITCHING_PROVIDER_SHARD_INDEX,
          // Inherit shard fields from the parent context if we're in a shard
          shardFields = shardExprContext?.shardFields ?: emptyMap(),
          ancestorGraphProperties = shardExprContext?.ancestorGraphProperties ?: emptyMap(),
          // For SwitchingProvider inside a shard, include the shard's graph property for
          // cross-shard access (this.graph.shardGraphProperty.shardField.property)
          shardGraphProperty = shardExprContext?.graphProperty,
          // Track which shard we're inside for same-shard optimization
          parentShardIndex = shardExprContext?.currentShardIndex,
        )

      // Build branches for each switching binding
      val branches = ArrayList<IrBranch>(switchingBindings.size + 1)

      branches +=
        switchingBindings.map { switchingBinding ->
          val condition =
            irEquals(
              irGetProperty(irGet(switchingProviderThisReceiver), idProperty),
              irInt(switchingBinding.id),
            )
          val result =
            irImplicitCast(
              generateBindingExpression(
                switchingBinding,
                switchingProviderThisReceiver,
                switchingProviderContext,
              ),
              typeParam.defaultType,
            )
          irBranch(condition, result)
        }

      // For the else branch, throw an Assertion. This should never be reached since IDs are
      // assigned
      // sequentially and all valid IDs have explicit branches
      val errorString = irConcat()
      errorString.addArgument(irString("Unexpected SwitchingProvider id: "))
      errorString.addArgument(irGetProperty(irGet(switchingProviderThisReceiver), idProperty))
      branches +=
        irElseBranch(
          irThrow(irInvoke(callee = metroSymbols.stdlibErrorFunction, args = listOf(errorString)))
        )

      return irWhen(typeParam.defaultType, branches)
    }

  /** Generates the expression to create the binding instance. */
  private fun IrBuilderWithScope.generateBindingExpression(
    switchingBinding: SwitchingBinding,
    switchingProviderThisReceiver: IrValueParameter,
    switchingProviderContext: ShardExpressionContext,
  ): IrExpression {
    // Use the expression generator to create the binding code
    // For SwitchingProvider, we always want INSTANCE access type since we're creating the
    // binding instance directly (the Provider wrapping is handled by the SwitchingProvider itself)
    //
    // We pass the SwitchingProvider's dispatch receiver and a ShardExpressionContext configured
    // with isSwitchingProvider=true so that all property access goes through this.graph
    //
    // Strip outer provider wrapping from contextKey since the property stores Provider<T> but
    // we want to generate the T instance (the SwitchingProvider itself provides the Provider
    // wrapper)
    val instanceContextKey = switchingBinding.contextKey.stripOuterProviderOrLazy()
    return expressionGeneratorFactory
      .create(thisReceiver = switchingProviderThisReceiver, shardContext = switchingProviderContext)
      .generateBindingCode(
        binding = switchingBinding.binding,
        contextualTypeKey = instanceContextKey,
        accessType = BindingExpressionGenerator.AccessType.INSTANCE,
        fieldInitKey = instanceContextKey.typeKey,
      )
  }
}
