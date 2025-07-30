// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.ClassFactory
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.scopeAnnotations
import dev.zacsweers.metro.compiler.ir.scopeOrNull
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.scopeHintFunctionName
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass

/**
 * A transformer that generates hint marker functions for _downstream_ compilations. This handles
 * both scoped @Inject classes and classes with contributing annotations. See [HintGenerator] for
 * more details about hint specifics.
 */
internal class ContributionHintIrTransformer(
  context: IrMetroContext,
  private val hintGenerator: HintGenerator,
  private val injectConstructorTransformer: InjectConstructorTransformer,
) : IrMetroContext by context {

  fun visitClass(declaration: IrClass) {
    // Don't generate hints for non-public APIs
    // Internal is allowed for friend paths
    if (
      !declaration.visibility.isPublicAPI &&
        declaration.visibility != DescriptorVisibilities.INTERNAL
    ) {
      return
    }

    val contributions =
      declaration.annotationsIn(symbols.classIds.allContributesAnnotations).toList()

    val contributionScopes = contributions.mapNotNullToSet { it.scopeOrNull() }

    for (contributionScope in contributionScopes) {
      hintGenerator.generateHint(
        sourceClass = declaration,
        hintName = contributionScope.scopeHintFunctionName(),
      )
    }

    if (options.enableScopedInjectClassHints && contributions.isEmpty()) {
      val classFactory =
        injectConstructorTransformer
          .getOrGenerateFactory(
            declaration = declaration,
            previouslyFoundConstructor = null,
            doNotErrorOnMissing = true,
          )
          ?.expectAs<ClassFactory.MetroFactory>() ?: return
      generateScopedInjectHints(declaration, classFactory)
    }
  }

  /**
   * Takes scoped @Inject classes without contributions and generates hints for them for us to later
   * use in making them available to the binding graph. These hints primarily support the ability
   * for graph extensions to access parent-scoped types that were unused/unreferenced in the parent.
   */
  private fun generateScopedInjectHints(
    declaration: IrClass,
    classFactory: ClassFactory.MetroFactory,
  ) {
    val scopes = classFactory.function.scopeAnnotations()

    if (scopes.isEmpty()) return

    for (scope in scopes) {
      val function =
        hintGenerator.generateHint(
          sourceClass = declaration,
          hintName = Symbols.CallableIds.scopedInjectClassHint(scope).callableName,
          hintAnnotations = listOf(scope),
        )

      // Now that the parent is set, link this function to the target class so that changes to that
      // class dirty this function
      trackFunctionCall(function, classFactory.function)
    }
  }
}
