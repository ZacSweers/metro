package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.name.CallableId

internal sealed interface BindsLikeCallable {
  val callableMetadata: IrCallableMetadata
  val callableId: CallableId
    get() = callableMetadata.callableId

  val function: IrSimpleFunction
    get() = callableMetadata.function
}

@Poko
internal class BindsCallable(
  override val callableMetadata: IrCallableMetadata,
  val source: IrTypeKey,
  val target: IrTypeKey,
) : BindsLikeCallable

@Poko
internal class MultibindsCallable(
  override val callableMetadata: IrCallableMetadata,
  val typeKey: IrTypeKey,
) : BindsLikeCallable

context(context: IrMetroContext)
internal fun MetroSimpleFunction.toBindsCallable(): BindsCallable {
  return BindsCallable(
    ir.irCallableMetadata(annotations),
    IrContextualTypeKey.from(ir.nonDispatchParameters.single()).typeKey,
    IrContextualTypeKey.from(ir).typeKey,
  )
}

context(context: IrMetroContext)
internal fun MetroSimpleFunction.toMultibindsCallable(): MultibindsCallable {
  return MultibindsCallable(
    ir.irCallableMetadata(annotations),
    IrContextualTypeKey.from(ir).typeKey,
  )
}
