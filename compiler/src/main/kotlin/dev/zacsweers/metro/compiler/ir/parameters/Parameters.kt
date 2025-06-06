// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.parameters

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.compareTo
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.NOOP_TYPE_REMAPPER
import dev.zacsweers.metro.compiler.ir.contextParameters
import dev.zacsweers.metro.compiler.ir.extensionReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.CallableId.Companion.PACKAGE_FQ_NAME_FOR_LOCAL
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

@Poko
internal class Parameters(
  val callableId: CallableId,
  val dispatchReceiverParameter: Parameter?,
  val extensionReceiverParameter: Parameter?,
  val regularParameters: List<Parameter>,
  val contextParameters: List<Parameter>,
  @Poko.Skip val ir: IrFunction? = null,
) : Comparable<Parameters> {

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

  val extensionOrFirstParameter: Parameter?
    get() = extensionReceiverParameter ?: regularParameters.firstOrNull()

  val nonDispatchParameters: List<Parameter> by unsafeLazy {
    buildList {
      extensionReceiverParameter?.let(::add)
      addAll(regularParameters)
    }
  }

  val allParameters: List<Parameter> by unsafeLazy {
    buildList {
      dispatchReceiverParameter?.let(::add)
      addAll(nonDispatchParameters)
    }
  }

  fun withCallableId(callableId: CallableId): Parameters {
    return Parameters(
      callableId,
      dispatchReceiverParameter,
      extensionReceiverParameter,
      regularParameters,
      contextParameters,
      ir,
    )
  }

  private val cachedToString by unsafeLazy {
    buildString {
      if (ir is IrConstructor || regularParameters.firstOrNull()?.isMember == true) {
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

  fun with(ir: IrFunction): Parameters {
    return Parameters(
      callableId,
      dispatchReceiverParameter,
      extensionReceiverParameter,
      regularParameters,
      contextParameters,
      ir,
    )
  }

  override fun toString(): String = cachedToString

  fun mergeValueParametersWith(other: Parameters): Parameters {
    return mergeValueParametersWithUntyped(other)
  }

  fun mergeValueParametersWithUntyped(other: Parameters): Parameters {
    return Parameters(
      callableId,
      dispatchReceiverParameter,
      extensionReceiverParameter,
      regularParameters + other.regularParameters,
      contextParameters + other.contextParameters,
    )
  }

  override fun compareTo(other: Parameters): Int = COMPARATOR.compare(this, other)

  companion object {
    private val EMPTY: Parameters =
      Parameters(
        CallableId(PACKAGE_FQ_NAME_FOR_LOCAL, null, SpecialNames.NO_NAME_PROVIDED),
        null,
        null,
        emptyList(),
        emptyList(),
      )

    fun empty(): Parameters = EMPTY

    val COMPARATOR: Comparator<Parameters> =
      compareBy<Parameters> { it.dispatchReceiverParameter }
        .thenBy { it.extensionReceiverParameter }
        .thenComparator { a, b -> a.regularParameters.compareTo(b.regularParameters) }

    operator fun invoke(
      callableId: CallableId,
      instance: Parameter?,
      extensionReceiver: Parameter?,
      regularParameters: List<Parameter>,
      contextParameters: List<Parameter>,
      ir: IrFunction?,
    ): Parameters =
      Parameters(callableId, instance, extensionReceiver, regularParameters, contextParameters, ir)
  }
}

internal fun IrFunction.parameters(
  context: IrMetroContext,
  remapper: TypeRemapper = NOOP_TYPE_REMAPPER,
): Parameters {
  return Parameters(
    callableId = callableId,
    instance =
      dispatchReceiverParameter?.toConstructorParameter(
        context,
        IrParameterKind.DispatchReceiver,
        remapper = remapper,
      ),
    extensionReceiver =
      extensionReceiverParameterCompat?.toConstructorParameter(
        context,
        IrParameterKind.ExtensionReceiver,
        remapper = remapper,
      ),
    regularParameters = regularParameters.mapToConstructorParameters(context, remapper),
    contextParameters = contextParameters.mapToConstructorParameters(context, remapper),
    ir = this,
  )
}
