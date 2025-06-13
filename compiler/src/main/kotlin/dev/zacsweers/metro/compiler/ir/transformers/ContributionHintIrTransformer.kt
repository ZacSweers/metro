// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.annotationsAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.scopeOrNull
import dev.zacsweers.metro.compiler.joinSimpleNames
import org.jetbrains.kotlin.ir.declarations.IrClass

/**
 * A transformer that generates hint marker functions for _downstream_ compilations. This handles
 * both scoped @Inject classes and classes with contributing annotations. See [HintGenerator] for
 * more details about hint specifics.
 */
internal class ContributionHintIrTransformer(
  context: IrMetroContext,
  private val hintGenerator: HintGenerator,
) : IrMetroContext by context {

  fun visitClass(declaration: IrClass) {
    // Don't generate hints for non-public APIs
    if (!declaration.visibility.isPublicAPI) return

    val contributions =
      declaration.annotationsIn(symbols.classIds.allContributesAnnotations).toList()
    val contributionScopes = contributions.mapNotNullTo(mutableSetOf()) { it.scopeOrNull() }
    for (contributionScope in contributionScopes) {
      hintGenerator.generateHint(
        sourceClass = declaration,
        hintName = contributionScope.joinSimpleNames().shortClassName,
      )
    }

    if (
      options.enableScopedInjectClassHints &&
        contributions.isEmpty() &&
        declaration.isAnnotatedWithAny(symbols.classIds.injectAnnotations)
    ) {
      generateScopedInjectHints(declaration)
    }
  }

  /**
   * Takes scoped @Inject classes without contributions and generates hints for them for us to later
   * use in making them available to the binding graph. These hints primarily support the ability
   * for graph extensions to access parent-scoped types that were unused/unreferenced in the parent.
   */
  private fun generateScopedInjectHints(declaration: IrClass) {
    val scopes =
      declaration.annotationsAnnotatedWithAny(symbols.classIds.scopeAnnotations).map {
        IrAnnotation(it)
      }

    for (scope in scopes) {
      hintGenerator.generateHint(
        sourceClass = declaration,
        hintName = Symbols.CallableIds.scopedInjectClassHint(scope).callableName,
      )
    }
  }
}
