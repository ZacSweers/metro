package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.findAnnotations
import dev.zacsweers.metro.compiler.ir.scopeOrNull
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.visitors.IrVisitor

// Scan IR symbols in this compilation
internal object IrContributionVisitor : IrVisitor<Unit, IrContributionData>() {
  override fun visitElement(element: IrElement, data: IrContributionData) {}

  override fun visitClass(declaration: IrClass, data: IrContributionData) {
    declaration.findAnnotations(Symbols.ClassIds.metroContribution).singleOrNull()?.let {
      val scope = it.scopeOrNull() ?: error("No scope found for @MetroContribution annotation")
      data.put(scope, declaration.defaultType)
    }
  }
}
