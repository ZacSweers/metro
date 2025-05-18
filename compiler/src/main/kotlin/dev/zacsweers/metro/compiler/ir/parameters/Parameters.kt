// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.parameters

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.compareTo
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.contextParameters
import dev.zacsweers.metro.compiler.ir.dispatchReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.extensionReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.parameters.Parameter.Kind
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.declarations.isPropertyAccessor
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.remapTypeParameters
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.CallableId.Companion.PACKAGE_FQ_NAME_FOR_LOCAL
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

internal sealed interface Parameters<T : Parameter> : Comparable<Parameters<*>> {
  val callableId: CallableId
  val dispatchReceiverParameter: Parameter?
  val extensionReceiverParameter: T?
  val regularParameters: List<T>
  val contextParameters: List<T>
  val ir: IrFunction?

  val nonInstanceParameters: List<Parameter>
  val allParameters: List<Parameter>

  val isProperty: Boolean
    get() = (ir as? IrSimpleFunction?)?.isPropertyAccessor == true

  val irProperty: IrProperty?
    get() {
      return if (isProperty) {
        (ir as IrSimpleFunction).propertyIfAccessor as? IrProperty
      } else {
        null
      }
    }

  val extensionOrFirstParameter: T?
    get() = extensionReceiverParameter ?: regularParameters.firstOrNull()

  fun with(ir: IrFunction): Parameters<T>

  fun mergeValueParametersWithAll(
    others: List<Parameters<out Parameter>>
  ): Parameters<out Parameter> {
    return listOf(this).reduce { current, next ->
      @Suppress("UNCHECKED_CAST")
      current.mergeValueParametersWithUntyped(next) as Parameters<T>
    }
  }

  fun mergeValueParametersWith(other: Parameters<*>): Parameters<T> {
    @Suppress("UNCHECKED_CAST")
    return mergeValueParametersWithUntyped(other) as Parameters<T>
  }

  fun mergeValueParametersWithUntyped(other: Parameters<*>): Parameters<*> {
    return ParametersImpl(
      callableId,
      dispatchReceiverParameter,
      extensionReceiverParameter,
      regularParameters + other.regularParameters,
      contextParameters + other.contextParameters,
    )
  }

  override fun compareTo(other: Parameters<*>): Int = COMPARATOR.compare(this, other)

  companion object {
    private val EMPTY: Parameters<*> =
      ParametersImpl(
        CallableId(PACKAGE_FQ_NAME_FOR_LOCAL, null, SpecialNames.NO_NAME_PROVIDED),
        null,
        null,
        emptyList(),
        emptyList(),
      )

    @Suppress("UNCHECKED_CAST") fun <T : Parameter> empty(): Parameters<T> = EMPTY as Parameters<T>

    val COMPARATOR: Comparator<Parameters<*>> =
      compareBy<Parameters<*>> { it.dispatchReceiverParameter }
        .thenBy { it.extensionReceiverParameter }
        .thenComparator { a, b -> a.regularParameters.compareTo(b.regularParameters) }

    operator fun <T : Parameter> invoke(
      callableId: CallableId,
      instance: Parameter?,
      extensionReceiver: T?,
      regularParameters: List<T>,
      contextParameters: List<T>,
      ir: IrFunction?,
    ): Parameters<T> =
      ParametersImpl(callableId, instance, extensionReceiver, regularParameters, contextParameters).apply {
        ir?.let { this.ir = it }
      }
  }
}

@Poko
private class ParametersImpl<T : Parameter>(
  override val callableId: CallableId,
  override val dispatchReceiverParameter: Parameter?,
  override val extensionReceiverParameter: T?,
  override val regularParameters: List<T>,
  override val contextParameters: List<T>,
) : Parameters<T> {
  override var ir: IrFunction? = null

  private val cachedToString by unsafeLazy {
    buildString {
      if (ir is IrConstructor || regularParameters.firstOrNull() is MembersInjectParameter) {
        append("@Inject ")
      }
      // TODO render context receivers
      if (isProperty) {
        if (irProperty?.isLateinit == true) {
          append("lateinit ")
        }
        append("var ")
      } else if (ir is IrConstructor) {
        append("constructor")
      } else {
        append("fun ")
      }
      dispatchReceiverParameter?.let {
        append(it.typeKey.render(short = true, includeQualifier = false))
        append('.')
      }
      extensionReceiverParameter?.let {
        append(it.typeKey.render(short = true, includeQualifier = false))
        append('.')
      }
      val name: Name? =
        irProperty?.name
          ?: run {
            if (ir is IrConstructor) {
              null
            } else {
              ir?.name ?: callableId.callableName
            }
          }
      name?.let { append(it) }
      if (!isProperty) {
        append('(')
        regularParameters.joinTo(this)
        append(')')
      }
      append(": ")
      ir?.let {
        val typeKey = IrTypeKey(it.returnType)
        append(typeKey.render(short = true, includeQualifier = false))
      } ?: run { append("<error>") }
    }
  }

  override fun with(ir: IrFunction): Parameters<T> {
    return ParametersImpl(callableId, dispatchReceiverParameter, extensionReceiverParameter, regularParameters, contextParameters).apply {
      this.ir = ir
    }
  }

  override val nonInstanceParameters: List<T> by unsafeLazy {
    buildList {
      extensionReceiverParameter?.let(::add)
      addAll(regularParameters)
    }
  }

  override val allParameters: List<Parameter> by unsafeLazy {
    buildList {
      dispatchReceiverParameter?.let(::add)
      addAll(nonInstanceParameters)
    }
  }

  override fun toString(): String = cachedToString
}

internal fun IrFunction.parameters(
  context: IrMetroContext,
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
    callableId = callableId,
    instance =
      dispatchReceiverParameterCompat?.toConstructorParameter(
        context,
        Kind.INSTANCE,
        typeParameterRemapper = mapper,
      ),
    extensionReceiver =
      extensionReceiverParameterCompat?.toConstructorParameter(
        context,
        Kind.EXTENSION_RECEIVER,
        typeParameterRemapper = mapper,
      ),
    regularParameters = regularParameters.mapToConstructorParameters(context, mapper),
    contextParameters = contextParameters.mapToConstructorParameters(context, mapper),
    ir = this,
  )
}

internal fun IrFunction.memberInjectParameters(
  context: IrMetroContext,
  nameAllocator: dev.zacsweers.metro.compiler.NameAllocator,
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
          context = context,
          uniqueName = nameAllocator.newName(property.name.asString()).asName(),
          kind = Kind.VALUE,
          typeParameterRemapper = mapper,
        )
      )
    } else {
      regularParameters.mapToMemberInjectParameters(
        context = context,
        nameAllocator = nameAllocator,
        typeParameterRemapper = mapper,
      )
    }

  return Parameters(
    callableId = callableId,
    instance = null,
    regularParameters = valueParams,
    // TODO not supported for now
    extensionReceiver = null,
    contextParameters = emptyList(),
    ir = this,
  )
}
