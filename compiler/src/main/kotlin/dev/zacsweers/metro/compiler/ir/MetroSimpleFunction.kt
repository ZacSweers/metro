// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.MetroAnnotations
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.name.CallableId

/** Simple holder with resolved annotations to save us lookups. */
// TODO cache these in a transformer context?
@Poko
internal class MetroSimpleFunction(
  @Poko.Skip val ir: IrSimpleFunction,
  val annotations: MetroAnnotations<IrAnnotation>,
  val callableId: CallableId = ir.callableId,
) : Comparable<MetroSimpleFunction> {
  override fun toString() = callableId.toString()

  override fun compareTo(other: MetroSimpleFunction): Int {
    return callableId.toString().compareTo(other.callableId.toString())
  }
}

internal fun IrMetroContext.metroFunctionOf(
  ir: IrSimpleFunction,
  annotations: MetroAnnotations<IrAnnotation> = metroAnnotationsOf(ir),
): MetroSimpleFunction {
  return MetroSimpleFunction(ir, annotations)
}
