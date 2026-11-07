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
import dev.zacsweers.metro.compiler.ir.effectiveVisibility
import dev.zacsweers.metro.compiler.ir.linkDeclarationsInCompilation
import dev.zacsweers.metro.compiler.ir.lookupClass
import dev.zacsweers.metro.compiler.ir.originClassId
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.scopeOrNull
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.ir.usesContributionProviderPath
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.scopeHintFunctionName
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.useJvmIrContributionHints
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fileEntry
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.ir.util.parentDeclarationsWithSelf
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.isJvm

/**
 * A transformer that generates hint marker functions for _downstream_ compilations. This handles
 * both scoped @Inject classes and classes with contributing annotations. See [HintGenerator] for
 * more details about hint specifics.
 */
// This transformer does not generate hints for top-level providers.
@Inject
@SingleIn(IrScope::class)
internal class ContributionHintIrTransformer(
  context: IrMetroContext,
  private val hintGenerator: HintGenerator,
) : IrMetroContext by context {

  private val sourceOwnedJvmHints =
    options.useJvmIrContributionHints(pluginContext.platform, pluginContext.languageVersionSettings)

  private val existingHints by lazy {
    val hints = mutableMapOf<Pair<ClassId, Name>, MutableList<IrSimpleFunction>>()
    for (file in hintGenerator.moduleFragment.files) {
      if (file.packageFqName != Symbols.FqNames.metroHintsPackage) {
        continue
      }
      for (function in file.declarations.filterIsInstance<IrSimpleFunction>()) {
        if (function.returnType != pluginContext.irBuiltIns.unitType) {
          continue
        }
        val parameter = function.regularParameters.singleOrNull() ?: continue
        val classId = parameter.type.classOrNull?.owner?.classId ?: continue
        hints.getOrPut(classId to function.name) { mutableListOf() }.add(function)
      }
    }
    hints
  }

  // Only executed if generateContributionHintsInFir is enabled
  // Implements the FIR-generated declarations with empty bodies
  fun visitFunction(declaration: IrSimpleFunction) {
    if (declaration.origin == Origins.ContributionHint) {
      declaration.apply { body = stubExpressionBody() }
      writeContributionProviderContainer(declaration)
      prepareHintFile(declaration)
    }
  }

  /**
   * Links FIR-generated hints to their contributing source for incremental compilation. JVM hints
   * use the source path and retain their unique facade names. Clearing the plugin-file marker lets
   * JVM output tracking use that source as the owner. KLIB hints use unique sibling paths so
   * Native's file caches remain distinct. Hints for synthetic sources keep their FIR file entries.
   * The original FIR file keeps its metadata and symbols. This works around
   * https://youtrack.jetbrains.com/issue/KT-90002.
   */
  private fun prepareHintFile(hint: IrSimpleFunction) {
    val file = hint.file
    val contributingClass = hint.regularParameters.single().type.classOrFail.owner
    val originClassId = contributingClass.originClassId()
    val sourceClass =
      if (originClassId != null) {
        hint.lookupClass(originClassId)?.owner ?: contributingClass
      } else {
        contributingClass
      }

    val sourcePath = Path(sourceClass.fileEntry.name)
    if (!sourcePath.isAbsolute) {
      return
    }

    val hintFileName = Path(file.fileEntry.name).fileName.toString()
    val isJvm = pluginContext.platform.isJvm()
    val hintPath =
      if (isJvm) {
        sourceClass.fileEntry.name
      } else {
        sourcePath.parent.resolve(hintFileName).absolutePathString()
      }

    if (isJvm) {
      hintGenerator.prepareJvmHintFile(file, hintPath, hintFileName)
    } else {
      file.fileEntry = NaiveSourceBasedFileEntryImpl(hintPath)
      trackClassLookup(hint, sourceClass)
      linkDeclarationsInCompilation(callingFile = file, sourceClass)
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
    if (contributionScopes.isEmpty()) {
      return
    }
    val metadataSourceClass =
      if (sourceOwnedJvmHints) {
        declaration.parentDeclarationsWithSelf.filterIsInstance<IrClass>().firstOrNull {
          it.origin !is IrDeclarationOrigin.GeneratedByPlugin &&
            it.metadata is FirMetadataSource.Class
        } ?: declaration
      } else {
        declaration
      }
    val useContributionProviderPath =
      declaration.usesContributionProviderPath(options, metroSymbols.classIds)

    for (contributionScope in contributionScopes) {
      val hintName = contributionScope.scopeHintFunctionName()
      if (sourceOwnedJvmHints) {
        val hints = existingHints[declaration.classIdOrFail to hintName].orEmpty()
        // A hint owned by another source can disappear without recompiling this contribution.
        val alreadyAdvertised = hints.any {
          val sameSourceAndVisibility =
            it.fileEntry.name == metadataSourceClass.fileEntry.name &&
              it.visibility == declaration.effectiveVisibility()
          it.origin == Origins.ContributionHint || sameSourceAndVisibility
        }
        if (alreadyAdvertised) {
          continue
        }
      }
      // Contribution-provider mode advertises the generated binding container, not the original
      // contributing class. The original class is still passed separately for hint file metadata.
      val sourceClass =
        if (useContributionProviderPath) {
          declaration.contributionProviderContainer(contributionScope) ?: continue
        } else {
          declaration
        }
      val hint =
        hintGenerator.generateHint(
          sourceClass = sourceClass,
          hintName = hintName,
          metadataSourceClass = metadataSourceClass,
          sourceOwnedJvmHint = sourceOwnedJvmHints,
        )
      if (sourceOwnedJvmHints) {
        existingHints.getOrPut(sourceClass.classIdOrFail to hintName) { mutableListOf() }.add(hint)
      }
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
