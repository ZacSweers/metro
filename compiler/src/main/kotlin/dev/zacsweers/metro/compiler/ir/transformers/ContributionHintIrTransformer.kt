// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrScope
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.scopeOrNull
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.usesContributionProviderPath
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.scopeHintFunctionName
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.name.ClassId

/**
 * A transformer that generates hint marker functions for _downstream_ compilations. This handles
 * both scoped @Inject classes and classes with contributing annotations. See [HintGenerator] for
 * more details about hint specifics.
 */
// NOTE this doesn't have logic for generating hints for top-level providers since this IR impl will
// go away
@Inject
@SingleIn(IrScope::class)
internal class ContributionHintIrTransformer(
  context: IrMetroContext,
  private val hintGenerator: HintGenerator,
) : IrMetroContext by context {

  // Only executed if generateContributionHintsInFir is enabled
  // Implements the FIR-generated declarations with empty bodies
  fun visitFunction(declaration: IrSimpleFunction) {
    if (declaration.origin == Origins.ContributionHint) {
      declaration.apply { body = stubExpressionBody() }
    }
  }

  fun visitClass(declaration: IrClass) {
    if (
      declaration.origin == Origins.ContributionProviderHolderDeclaration ||
        declaration.origin == Origins.MetroContributionClassDeclaration
    ) {
      return
    }

    // Don't generate hints for non-public APIs
    // Internal is allowed for friend paths
    if (
      !declaration.visibility.isPublicAPI &&
        declaration.visibility != DescriptorVisibilities.INTERNAL
    ) {
      return
    }

    val contributions =
      declaration.annotationsIn(metroSymbols.classIds.allContributesAnnotations).toList()

    val contributionScopes = contributions.mapNotNullToSet { it.scopeOrNull() }
    val useContributionProviderPath =
      declaration.usesContributionProviderPath(options, metroSymbols.classIds)

    for (contributionScope in contributionScopes) {
      // Contribution-provider mode advertises the generated binding container, not the original
      // contributing class. The original class is still passed separately for hint file metadata.
      val sourceClass =
        if (useContributionProviderPath) {
          declaration.contributionProviderContainer(contributionScope) ?: continue
        } else {
          declaration
        }
      hintGenerator.generateHint(
        sourceClass = sourceClass,
        hintName = contributionScope.scopeHintFunctionName(),
        metadataSourceClass = declaration,
      )
    }
  }

  private fun IrClass.contributionProviderContainer(scope: ClassId): IrClass? {
    val holderClassId = MetroContributions.holderClassId(classIdOrFail)
    val containerClassId = MetroContributions.containerObjectClassId(classIdOrFail, scope)
    val holder =
      file.declarations.filterIsInstance<IrClass>().firstOrNull {
        it.classIdOrFail == holderClassId
      } ?: return null
    return holder.nestedClasses.firstOrNull { it.classIdOrFail == containerClassId }
  }
}
