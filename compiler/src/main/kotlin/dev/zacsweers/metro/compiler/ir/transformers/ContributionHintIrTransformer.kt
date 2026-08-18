// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.PLUGIN_ID
import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrScope
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.scopeOrNull
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.usesContributionProviderPath
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.scopeHintFunctionName
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.classOrFail
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
      writeContributionProviderContainer(declaration)
    }
  }

  private fun writeContributionProviderContainer(hint: IrSimpleFunction) {
    if (!options.generateClassesInIr) return

    val contributingClass = hint.regularParameters.single().type.classOrFail.owner
    if (!contributingClass.usesContributionProviderPath(options, metroSymbols.classIds)) return

    // FIR cannot name an IR-only container in the hint's parameter. Record the producer's exact
    // ID on that hint instead. Only binding scopes get containers; a pure @ContributesTo scope does
    // not, even when another annotation on this class uses contribution providers.
    val scope =
      contributingClass
        .annotationsIn(metroSymbols.classIds.contributesBindingLikeAnnotationsWithContainers)
        .mapNotNull { it.scopeOrNull() }
        .distinct()
        .singleOrNull { it.scopeHintFunctionName() == hint.name } ?: return
    val containerClassId =
      MetroContributions.containerObjectClassId(
        contributingClass.classIdOrFail,
        scope,
        options.maxGeneratedClassNameLength,
      )
    metadataDeclarationRegistrarCompat.addCustomMetadataExtension(
      hint,
      PLUGIN_ID,
      containerClassId.asString().encodeToByteArray(),
    )
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
    val containerClassId =
      MetroContributions.containerObjectClassId(
        classIdOrFail,
        scope,
        options.maxGeneratedClassNameLength,
      )
    val holderClassId = containerClassId.outerClassId ?: return null
    val holder =
      file.declarations.filterIsInstance<IrClass>().firstOrNull {
        it.classIdOrFail == holderClassId
      } ?: return null
    return holder.nestedClasses.firstOrNull { it.classIdOrFail == containerClassId }
  }
}
