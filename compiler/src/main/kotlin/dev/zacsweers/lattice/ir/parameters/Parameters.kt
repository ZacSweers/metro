package dev.zacsweers.lattice.ir.parameters

import dev.zacsweers.lattice.ir.LatticeTransformerContext
import dev.zacsweers.lattice.ir.parameters.Parameter.Kind
import dev.zacsweers.lattice.unsafeLazy
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.remapTypeParameters

internal sealed interface Parameters : Comparable<Parameters> {
  val instance: Parameter?
  val extensionReceiver: Parameter?
  val valueParameters: List<Parameter>
  val ir: IrFunction

  val nonInstanceParameters: List<Parameter>
  val allParameters: List<Parameter>

  fun with(ir: IrFunction): Parameters

  override fun compareTo(other: Parameters): Int = COMPARATOR.compare(this, other)

  companion object {
    val EMPTY: Parameters = ParametersImpl(null, null, emptyList())
    val COMPARATOR =
      compareBy<Parameters> { it.instance }
        .thenBy { it.extensionReceiver }
        .thenComparator { a, b -> compareValues(a, b) }

    operator fun invoke(
      instance: Parameter?,
      extensionReceiver: Parameter?,
      valueParameters: List<Parameter>,
      ir: IrFunction?,
    ): Parameters =
      ParametersImpl(instance, extensionReceiver, valueParameters).apply {
        ir?.let { this.ir = it }
      }
  }
}

private data class ParametersImpl(
  override val instance: Parameter?,
  override val extensionReceiver: Parameter?,
  override val valueParameters: List<Parameter>,
) : Parameters {
  override lateinit var ir: IrFunction

  override fun with(ir: IrFunction): Parameters {
    return copy().apply { this.ir = ir }
  }

  override val nonInstanceParameters: List<Parameter> by unsafeLazy {
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
): Parameters {
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