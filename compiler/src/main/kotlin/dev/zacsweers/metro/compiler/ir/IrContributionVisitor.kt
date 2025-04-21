package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.exitProcessing
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.visitors.IrVisitor

// Scan IR symbols in this compilation
internal class IrContributionVisitor(private val metroContext: IrMetroContext) :
  IrVisitor<Unit, IrContributionData>() {
  override fun visitElement(element: IrElement, data: IrContributionData) {}

  override fun visitClass(declaration: IrClass, data: IrContributionData) {
    val metroContribution =
      declaration.findAnnotations(Symbols.ClassIds.metroContribution).singleOrNull()
    if (metroContribution != null) {
      val scope =
        metroContribution.scopeOrNull()
          ?: with(metroContext) {
            declaration.reportError("No scope found for @MetroContribution annotation")
            exitProcessing()
          }
      data.addContribution(scope, declaration.defaultType)
      return
    }

    // Check if it's a plain old ContributesTo
    for (contributesToAnno in
      declaration.annotationsIn(metroContext.symbols.classIds.contributesToLikeAnnotations)) {
      val scope =
        contributesToAnno.scopeOrNull()
          ?: with(metroContext) {
            declaration.reportError(
              "No scope found for @${contributesToAnno.annotationClass.name} annotation"
            )
            exitProcessing()
          }
      data.addContribution(scope, declaration.defaultType)
      return
    }
  }
}
