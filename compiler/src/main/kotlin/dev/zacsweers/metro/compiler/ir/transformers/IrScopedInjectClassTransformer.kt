// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.transformers.IrScopedInjectClassData
import dev.zacsweers.metro.compiler.ir.transformers.LookupHintGenerator
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.ClassId

/**
 * Takes scoped @Inject classes without contributions and generates hints for them for us to later
 * use in making them available to the binding graph. These hints primarily support the ability for
 * graph extensions to access parent-scoped types that were unused/unreferenced in the parent.
 */
internal class IrScopedInjectClassTransformer(
  context: IrMetroContext,
  private val hintGenerator: LookupHintGenerator,
) : IrElementTransformerVoid(), IrMetroContext by context {

  private val transformedClasses = mutableSetOf<ClassId>()

  val data: IrScopedInjectClassData = IrScopedInjectClassData(context)

  override fun visitClass(declaration: IrClass): IrStatement {
    if (
      options.enableScopedInjectClassHints &&
        declaration.isAnnotatedWithAny(symbols.classIds.injectAnnotations) &&
        !declaration.isAnnotatedWithAny(symbols.classIds.allContributesAnnotations)
    ) {
      generateScopeHints(declaration)
    }

    return super.visitClass(declaration)
  }

  private fun generateScopeHints(declaration: IrClass) {
    val scopes =
      declaration.annotationsAnnotatedWithAny(symbols.classIds.scopeAnnotations).map {
        IrAnnotation(it)
      }

    val classId = declaration.classIdOrFail
    if (classId !in transformedClasses) {
      // TODO what about generics?
      val typeKey = IrTypeKey(declaration)
      for (scope in scopes) {
        data.addContribution(scope, typeKey)

        hintGenerator.generateHint(
          sourceClass = declaration,
          hintName = Symbols.CallableIds.scopedInjectClassHint(scope).callableName,
          hintAnnotations = listOf(scope),
        )
      }
      declaration.dumpToMetroLog()
    }
    transformedClasses += classId
  }
}
