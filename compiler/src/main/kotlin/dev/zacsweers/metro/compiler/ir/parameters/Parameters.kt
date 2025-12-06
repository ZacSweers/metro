// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.parameters

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.compareTo
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.NOOP_TYPE_REMAPPER
import dev.zacsweers.metro.compiler.ir.contextParameters
import dev.zacsweers.metro.compiler.ir.extensionReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.descriptors.isObject
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.isPropertyAccessor
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.remapTypes
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
    get() = ir?.isPropertyAccessor == true

  val irProperty: IrProperty?
    get() {
      return if (isProperty) {
        (ir as IrSimpleFunction).propertyIfAccessor as? IrProperty
      } else {
        null
      }
    }

  val nonDispatchParameters: List<Parameter> by memoize {
    buildList {
      extensionReceiverParameter?.let(::add)
      addAll(contextParameters)
      addAll(regularParameters)
    }
  }

  val extensionOrFirstParameter: Parameter?
    get() = nonDispatchParameters.firstOrNull()

  val allParameters: List<Parameter> by memoize {
    buildList {
      dispatchReceiverParameter?.let(::add)
      addAll(nonDispatchParameters)
    }
  }

  fun copy(
    callableId: CallableId = this.callableId,
    dispatchReceiverParameter: Parameter? = this.dispatchReceiverParameter,
    extensionReceiverParameter: Parameter? = this.extensionReceiverParameter,
    regularParameters: List<Parameter> = this.regularParameters,
    contextParameters: List<Parameter> = this.contextParameters,
    ir: IrFunction? = this.ir,
  ): Parameters {
    return Parameters(
      callableId,
      dispatchReceiverParameter,
      extensionReceiverParameter,
      regularParameters,
      contextParameters,
      ir,
    )
  }

  val parametersMap by memoize { allParameters.associateBy { it.name } }

  operator fun get(name: Name): Parameter? = parametersMap[name]

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

  fun overlayQualifiers(qualifiers: List<IrAnnotation?>): Parameters {
    return Parameters(
      callableId = callableId,
      dispatchReceiverParameter = dispatchReceiverParameter,
      extensionReceiverParameter = extensionReceiverParameter,
      regularParameters =
        regularParameters.mapIndexed { i, param ->
          val qualifier = qualifiers[i] ?: return@mapIndexed param
          param.copy(
            contextualTypeKey =
              param.contextualTypeKey.withTypeKey(
                param.contextualTypeKey.typeKey.copy(qualifier = qualifier)
              )
          )
        },
      contextParameters = contextParameters,
      ir = ir,
    )
  }

  private val cachedToString by memoize {
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
      ir?.let {
        if (!it.returnType.isUnit()) {
          append(": ")
          val typeKey = IrTypeKey(it.returnType)
          append(typeKey.render(short = true, includeQualifier = false))
        }
      }
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

  fun with(other: MetroSimpleFunction): Parameters {
    return Parameters(
      callableId,
      dispatchReceiverParameter,
      extensionReceiverParameter,
      regularParameters,
      contextParameters,
      other.ir,
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

context(context: IrMetroContext)
internal fun IrFunction.parameters(
  remapper: TypeRemapper = NOOP_TYPE_REMAPPER,
  includeObjectDispatchReceivers: Boolean = false,
): Parameters {
  return Parameters(
    callableId = callableId,
    instance =
      dispatchReceiverParameter
        ?.takeUnless {
          !includeObjectDispatchReceivers && (it.type.rawTypeOrNull()?.kind?.isObject == true)
        }
        ?.toConstructorParameter(IrParameterKind.DispatchReceiver, remapper = remapper),
    extensionReceiver =
      extensionReceiverParameterCompat?.toConstructorParameter(
        IrParameterKind.ExtensionReceiver,
        remapper = remapper,
      ),
    regularParameters = regularParameters.mapToConstructorParameters(remapper),
    contextParameters = contextParameters.mapToConstructorParameters(remapper),
    ir = this,
  )
}

internal fun Parameters.remapTypes(remapper: TypeRemapper): Parameters {
  if (remapper == NOOP_TYPE_REMAPPER) return this
  return Parameters(
    callableId = callableId,
    instance = dispatchReceiverParameter?.remapTypes(remapper),
    extensionReceiver = extensionReceiverParameter?.remapTypes(remapper),
    regularParameters = regularParameters.map { it.remapTypes(remapper) },
    contextParameters = contextParameters.map { it.remapTypes(remapper) },
    ir = ir?.let { it.deepCopyWithSymbols(it.parent) { remapper } },
  )
}
