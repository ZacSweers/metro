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
) {
  override fun toString() = callableId.toString()
}

internal fun IrMetroContext.metroFunctionOf(
  ir: IrSimpleFunction,
  annotations: MetroAnnotations<IrAnnotation> = metroAnnotationsOf(ir),
): MetroSimpleFunction {
  return MetroSimpleFunction(ir, annotations)
}
