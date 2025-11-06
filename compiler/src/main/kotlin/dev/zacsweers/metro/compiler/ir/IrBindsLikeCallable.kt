// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.name.CallableId

internal sealed interface BindsLikeCallable : IrBindingContainerCallable {
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
) : BindsLikeCallable {
  override val typeKey: IrTypeKey = target
}

@Poko
internal class MultibindsCallable(
  override val callableMetadata: IrCallableMetadata,
  override val typeKey: IrTypeKey,
) : BindsLikeCallable

@Poko
internal class BindsOptionalOfCallable(
  override val callableMetadata: IrCallableMetadata,
  override val typeKey: IrTypeKey,
) : BindsLikeCallable

context(context: IrMetroContext)
internal fun MetroSimpleFunction.toBindsCallable(isInterop: Boolean): BindsCallable {
  return BindsCallable(
    ir.irCallableMetadata(annotations, isInterop),
    IrContextualTypeKey.from(ir.nonDispatchParameters.single()).typeKey,
    IrContextualTypeKey.from(ir).typeKey,
  )
}

context(context: IrMetroContext)
internal fun MetroSimpleFunction.toMultibindsCallable(isInterop: Boolean): MultibindsCallable {
  return MultibindsCallable(
    ir.irCallableMetadata(annotations, isInterop),
    IrContextualTypeKey.from(ir, patchMutableCollections = isInterop).typeKey,
  )
}

context(context: IrMetroContext)
internal fun MetroSimpleFunction.toBindsOptionalOfCallable(): BindsOptionalOfCallable {
  // Wrap this in a Java Optional
  // TODO what if we support other optionals?
  val targetType = IrContextualTypeKey.from(ir, patchMutableCollections = true).typeKey
  val wrapped = context.metroSymbols.javaOptional.typeWith(targetType.type)
  val wrappedContextKey = targetType.copy(type = wrapped)

  return BindsOptionalOfCallable(
    ir.irCallableMetadata(annotations, isInterop = true),
    wrappedContextKey,
  )
}
