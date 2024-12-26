/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.ir.parameters

import dev.zacsweers.lattice.NameAllocator
import dev.zacsweers.lattice.asName
import dev.zacsweers.lattice.ir.LatticeTransformerContext
import dev.zacsweers.lattice.ir.parameters.Parameter.Kind
import dev.zacsweers.lattice.unsafeLazy
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.declarations.isPropertyAccessor
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.remapTypeParameters

internal sealed interface Parameters<T : Parameter> : Comparable<Parameters<*>> {
  val instance: Parameter?
  val extensionReceiver: T?
  val valueParameters: List<T>
  val ir: IrFunction

  val nonInstanceParameters: List<Parameter>
  val allParameters: List<Parameter>

  fun with(ir: IrFunction): Parameters<T>

  fun mergeValueParametersWith(other: Parameters<T>): Parameters<T> {
    return ParametersImpl<T>(instance, extensionReceiver, valueParameters + other.valueParameters)
  }

  override fun compareTo(other: Parameters<*>): Int = COMPARATOR.compare(this, other)

  companion object {
    private val EMPTY: Parameters<*> = ParametersImpl(null, null, emptyList())

    @Suppress("UNCHECKED_CAST") fun <T : Parameter> empty(): Parameters<T> = EMPTY as Parameters<T>

    val COMPARATOR =
      compareBy<Parameters<*>> { it.instance }
        .thenBy { it.extensionReceiver }
        .thenComparator { a, b -> compareValues(a, b) }

    operator fun <T : Parameter> invoke(
      instance: Parameter?,
      extensionReceiver: T?,
      valueParameters: List<T>,
      ir: IrFunction?,
    ): Parameters<T> =
      ParametersImpl<T>(instance, extensionReceiver, valueParameters).apply {
        ir?.let { this.ir = it }
      }
  }
}

private data class ParametersImpl<T : Parameter>(
  override val instance: Parameter?,
  override val extensionReceiver: T?,
  override val valueParameters: List<T>,
) : Parameters<T> {
  override lateinit var ir: IrFunction

  override fun with(ir: IrFunction): Parameters<T> {
    return copy().apply { this.ir = ir }
  }

  override val nonInstanceParameters: List<T> by unsafeLazy {
    buildList {
      extensionReceiver?.let(::add)
      addAll(valueParameters)
    }
  }
  override val allParameters: List<Parameter> by unsafeLazy {
    buildList {
      instance?.let(::add)
      addAll(nonInstanceParameters)
    }
  }
}

internal fun IrFunction.parameters(
  context: LatticeTransformerContext,
  parentClass: IrClass? = parentClassOrNull,
  originClass: IrTypeParametersContainer? = null,
): Parameters<ConstructorParameter> {
  val mapper =
    if (this is IrConstructor && originClass != null && parentClass != null) {
      val typeParameters = parentClass.typeParameters
      val srcToDstParameterMap: Map<IrTypeParameter, IrTypeParameter> =
        originClass.typeParameters.zip(typeParameters).associate { (src, target) -> src to target }
      // Returning this inline breaks kotlinc for some reason
      val innerMapper: ((IrType) -> IrType) = { type ->
        type.remapTypeParameters(originClass, parentClass, srcToDstParameterMap)
      }
      innerMapper
    } else {
      null
    }

  return Parameters(
    instance =
      dispatchReceiverParameter?.toConstructorParameter(
        context,
        Kind.INSTANCE,
        typeParameterRemapper = mapper,
      ),
    extensionReceiver =
      extensionReceiverParameter?.toConstructorParameter(
        context,
        Kind.EXTENSION_RECEIVER,
        typeParameterRemapper = mapper,
      ),
    valueParameters = valueParameters.mapToConstructorParameters(context, mapper),
    ir = this,
  )
}

internal fun IrFunction.memberInjectParameters(
  context: LatticeTransformerContext,
  nameAllocator: NameAllocator,
  parentClass: IrClass = parentClassOrNull!!,
  originClass: IrTypeParametersContainer? = null,
): Parameters<MembersInjectParameter> {
  val mapper =
    if (originClass != null) {
      val typeParameters = parentClass.typeParameters
      val srcToDstParameterMap: Map<IrTypeParameter, IrTypeParameter> =
        originClass.typeParameters.zip(typeParameters).associate { (src, target) -> src to target }
      // Returning this inline breaks kotlinc for some reason
      val innerMapper: ((IrType) -> IrType) = { type ->
        type.remapTypeParameters(originClass, parentClass, srcToDstParameterMap)
      }
      innerMapper
    } else {
      null
    }

  val valueParams =
    if (isPropertyAccessor) {
      val property = propertyIfAccessor as IrProperty
      listOf(
        property.toMemberInjectParameter(
          context,
          parentClass.classIdOrFail,
          uniqueName = nameAllocator.newName(property.name.asString()).asName(),
          Kind.VALUE,
          typeParameterRemapper = mapper,
        )
      )
    } else {
      valueParameters.mapToMemberInjectParameters(
        context,
        nameAllocator,
        parentClass.classIdOrFail,
        mapper,
      )
    }

  return Parameters(
    instance =
      dispatchReceiverParameter?.toConstructorParameter(
        context,
        Kind.INSTANCE,
        typeParameterRemapper = mapper,
      ),
    // TODO not supported for now
    extensionReceiver = null,
    valueParameters = valueParams,
    ir = this,
  )
}
