package dev.zacsweers.lattice.ir

import dev.zacsweers.lattice.transformers.LatticeTransformerContext
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.util.render

internal class IrAnnotation(val ir: IrConstructorCall) {
  val hashKey by lazy { ir.computeAnnotationHash() }

  fun LatticeTransformerContext.isQualifier() = ir.type.rawType().isQualifierAnnotation

  fun LatticeTransformerContext.isScope() = ir.type.rawType().isScopeAnnotation

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as IrAnnotation

    return hashKey == other.hashKey
  }

  override fun hashCode(): Int = hashKey

  override fun toString() = ir.render()
}