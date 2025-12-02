// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph.sharding

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.generateDefaultConstructorBody
import dev.zacsweers.metro.compiler.ir.graph.PropertyInitializer
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.Name

/**
 * Generates IR for shard classes when a graph is partitioned.
 *
 * Creates inner shard classes with properties and an initialize() function for their bindings.
 */
internal class IrGraphShardGenerator(context: IrMetroContext) : IrMetroContext by context {

  /**
   * Converts planned groups to property bindings and merge all unplanned bindings in the final
   * group.
   */
  fun planShardGroups(
    propertyBindings: List<PropertyBinding>,
    plannedGroups: List<List<IrTypeKey>>?,
  ): List<List<PropertyBinding>> {
    if (propertyBindings.isEmpty() || plannedGroups.isNullOrEmpty()) {
      return listOf(propertyBindings)
    }

    val bindingsByKey = propertyBindings.associateBy { it.typeKey }.toMutableMap()
    val groups =
      plannedGroups.mapNotNull { group ->
        group.mapNotNull(bindingsByKey::remove).takeIf { it.isNotEmpty() }
      }

    return when {
      groups.isEmpty() -> listOf(propertyBindings)
      bindingsByKey.isEmpty() -> groups
      else -> groups + listOf(bindingsByKey.values.toList())
    }
  }

  /**
   * Generates inner shard classes with initialize() functions. Also adds shard instance properties
   * to the parent graph.
   */
  fun generateShards(
    graphClass: IrClass,
    shardGroups: List<List<PropertyBinding>>,
  ): List<ShardInfo> {
    val graphReceiver = graphClass.thisReceiverOrFail
    return shardGroups.mapIndexed { index, bindings ->
      val shardName = "Shard${index + 1}"
      val shardClass =
        irFactory
          .buildClass {
            name = Name.identifier(shardName)
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            isInner = true
          }
          .apply {
            superTypes = listOf(irBuiltIns.anyType)
            createThisReceiverParameter()
            parent = graphClass
            graphClass.addChild(this)
          }

      shardClass
        .addConstructor {
          isPrimary = true
          origin = Origins.Default
        }
        .apply {
          setDispatchReceiver(graphReceiver.copyTo(this, type = graphClass.defaultType))
          body = generateDefaultConstructorBody()
        }

      val shardInstanceProperty =
        graphClass
          .addProperty {
            this.name = Name.identifier(shardName.replaceFirstChar { it.lowercase() })
            visibility = DescriptorVisibilities.PRIVATE
          }
          .apply {
            addBackingField { type = shardClass.defaultType }

            addGetter { returnType = shardClass.defaultType }
              .apply {
                val getterReceiver = graphReceiver.copyTo(this)
                setDispatchReceiver(getterReceiver)

                body =
                  createIrBuilder(symbol).irBlockBody {
                    +irReturn(irGetField(irGet(getterReceiver), backingField!!))
                  }
              }
          }

      val initializeFunction =
        shardClass.addFunction("initialize", irBuiltIns.unitType).apply {
          val shardReceiver = shardClass.thisReceiverOrFail.copyTo(this)
          setDispatchReceiver(shardReceiver)
        }

      val graphInstanceParam =
        initializeFunction.addValueParameter("graphInstance", graphClass.defaultType)

      // First pass: generate expressions and collect out-of-scope parameters
      val inScopeParams = setOf(graphInstanceParam)
      val collector = OuterReceiverCollector(inScopeParams)
      val generatedExpressions = mutableListOf<Pair<PropertyBinding, IrExpression>>()

      createIrBuilder(initializeFunction.symbol).run {
        for (binding in bindings) {
          val backingField = binding.property.backingField
          if (backingField != null) {
            val initValue =
              binding.initializer.invoke(this@run, graphInstanceParam, binding.typeKey)
            initValue.acceptChildrenVoid(collector)
            generatedExpressions.add(binding to initValue)
          }
        }
      }

      val outerReceiverParams = mutableListOf<IrValueParameter>()
      val paramMapping = mutableMapOf<IrValueParameter, IrValueParameter>()
      for (outerParam in collector.outOfScopeParams) {
        val newParam =
          initializeFunction.addValueParameter(
            "outer_${outerParam.name.asString()}",
            outerParam.type,
          )
        paramMapping[outerParam] = newParam
        outerReceiverParams.add(outerParam)
      }

      val remapper = if (paramMapping.isNotEmpty()) ParameterRemapper(paramMapping) else null
      initializeFunction.body =
        createIrBuilder(initializeFunction.symbol).irBlockBody {
          for ((binding, initValue) in generatedExpressions) {
            val backingField = binding.property.backingField!!
            val remappedValue = remapper?.remap(initValue) ?: initValue
            +irSetField(irGet(graphInstanceParam), backingField, remappedValue)
          }
        }

      ShardInfo(
        index = index,
        shardClass = shardClass,
        instanceProperty = shardInstanceProperty,
        initializeFunction = initializeFunction,
        bindings = bindings,
        outerReceiverParams = outerReceiverParams,
      )
    }
  }
}

/** Property with its type key and initializer. */
internal data class PropertyBinding(
  val property: IrProperty,
  val typeKey: IrTypeKey,
  val initializer: PropertyInitializer,
)

/** Generated shard class info, with its parent graph property and the initialize function. */
internal data class ShardInfo(
  val index: Int,
  val shardClass: IrClass,
  val instanceProperty: IrProperty,
  val initializeFunction: IrSimpleFunction,
  val bindings: List<PropertyBinding>,
  val outerReceiverParams: List<IrValueParameter>,
)

/**
 * Collects all [IrValueParameter] references from an expression that are not in the given scope.
 * Used to detect when binding code references parameters from outer class constructors.
 */
private class OuterReceiverCollector(private val inScopeParams: Set<IrValueParameter>) :
  IrVisitorVoid() {
  val outOfScopeParams = mutableSetOf<IrValueParameter>()

  override fun visitElement(element: IrElement) {
    element.acceptChildrenVoid(this)
  }

  override fun visitGetValue(expression: IrGetValue) {
    val owner = expression.symbol.owner
    if (owner is IrValueParameter && owner !in inScopeParams) {
      outOfScopeParams.add(owner)
    }
    super.visitGetValue(expression)
  }
}

/** Remaps [IrGetValue] nodes to use substituted parameters. */
private class ParameterRemapper(private val mapping: Map<IrValueParameter, IrValueParameter>) :
  IrElementTransformerVoid() {

  fun remap(expression: IrExpression): IrExpression {
    return expression.transform(this, null)
  }

  override fun visitGetValue(expression: IrGetValue): IrExpression {
    val owner = expression.symbol.owner
    if (owner is IrValueParameter && owner in mapping) {
      return IrGetValueImpl(
        expression.startOffset,
        expression.endOffset,
        mapping.getValue(owner).symbol,
      )
    }
    return super.visitGetValue(expression)
  }
}
