// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.scopeOrNull
import dev.zacsweers.metro.compiler.joinSimpleNames
import org.jetbrains.kotlin.backend.common.extensions.IrGeneratedDeclarationsRegistrar
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl

/**
 * A transformer that generates hint marker functions for _downstream_ compilations. In-compilation
 * contributions are looked up directly. This works by generating hints into a synthetic
 * [IrFileImpl] in the [Symbols.FqNames.metroHintsPackage] package. The signature of the function is
 * simply a generated name (the snake_cased name of the scope) and return type pointing at the
 * contributing class. This class is then looked up separately.
 *
 * Example of a generated synthetic function:
 * ```
 * fun com_example_AppScope(contributed: MyClass) = error("Stub!")
 * ```
 *
 * Importantly, this transformer also adds these generated functions to metadata via
 * [IrGeneratedDeclarationsRegistrar.registerFunctionAsMetadataVisible], which ensures they are
 * visible to downstream compilations.
 *
 * File creation is on a little big of shaky ground, but necessary for this to work. More
 * explanation can be found below.
 */
internal class ContributionHintIrTransformer(
  context: IrMetroContext,
  private val hintGenerator: LookupHintGenerator,
) : IrMetroContext by context {

  fun visitClass(declaration: IrClass) {
    // Don't generate hints for non-public APIs
    if (!declaration.visibility.isPublicAPI) return

    val contributionScopes =
      declaration.annotationsIn(symbols.classIds.allContributesAnnotations).mapNotNullTo(
        mutableSetOf()
      ) {
        it.scopeOrNull()
      }
    for (contributionScope in contributionScopes) {
      hintGenerator.generateHint(
        sourceClass = declaration,
        hintName = contributionScope.joinSimpleNames().shortClassName,
      )
    }
  }
}
