package dev.zacsweers.lattice.compiler.ir

import dev.drewhamilton.poko.Poko
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.name.CallableId

/**
 * Simple holder with resolved annotations to save us lookups.
 */
// TODO cache these in a transformer context?
@Poko
internal class LatticeSimpleFunction(
  @Poko.Skip val ir: IrSimpleFunction,
  val annotations: LatticeIrAnnotations,
  val callableId: CallableId = ir.callableId
) {
  override fun toString() = callableId.toString()
}

internal fun LatticeTransformerContext.latticeFunctionOf(ir: IrSimpleFunction): LatticeSimpleFunction {
  return LatticeSimpleFunction(ir, latticeAnnotationsOf(ir))
}
