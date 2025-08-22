// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.visitors.IrVisitor

// Scan IR symbols in this compilation
internal class IrContributionVisitor(context: IrMetroContext) :
  IrVisitor<Unit, IrContributionData>(), IrMetroContext by context {

  override fun visitElement(element: IrElement, data: IrContributionData) {
    element.acceptChildren(this, data)
  }

  override fun visitClass(declaration: IrClass, data: IrContributionData) {
    visitClassInner(declaration, data)
    super.visitClass(declaration, data)
  }

  private fun visitClassInner(declaration: IrClass, data: IrContributionData) {
    val metroContribution =
      declaration.findAnnotations(Symbols.ClassIds.metroContribution).singleOrNull()
    if (metroContribution != null) {
      val scope =
        metroContribution.scopeOrNull()
          ?: with(metroContext) {
            diagnosticReporter
              .at(declaration)
              .report(MetroDiagnostics.METRO_ERROR, "No scope found for @MetroContribution annotation")
            exitProcessing()
          }
      if (declaration.isAnnotatedWithAny(symbols.classIds.bindingContainerAnnotations)) {
        data.addBindingContainerContribution(scope, declaration)
      } else {
        data.addContribution(scope, declaration.defaultType)
      }
      return
    }

    // @BindingContainer handling
    if (declaration.isAnnotatedWithAny(symbols.classIds.bindingContainerAnnotations)) {
      for (contributesToAnno in
        declaration.annotationsIn(symbols.classIds.contributesToAnnotations)) {
        val scope =
          contributesToAnno.scopeOrNull()
            ?: with(metroContext) {
              diagnosticReporter
                .at(declaration)
                .report(
                  MetroDiagnostics.METRO_ERROR,
                  "No scope found for @${contributesToAnno.annotationClass.name} annotation",
                )
              exitProcessing()
            }
        data.addBindingContainerContribution(scope, declaration)
      }
      return
    }
  }
}
