// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.joinSimpleNames
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.name
import org.jetbrains.kotlin.backend.common.extensions.IrGeneratedDeclarationsRegistrar
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.builder.buildPackageDirective
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.builder.buildFile
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFile
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fileEntry
import org.jetbrains.kotlin.ir.util.getValueArgument

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
  private val moduleFragment: IrModuleFragment,
) : IrMetroContext by context {
  fun visitClass(declaration: IrClass) {
    val contributionScopes =
      declaration.annotationsIn(symbols.classIds.allContributesAnnotations).mapNotNull {
        it
          .getValueArgument(Symbols.Names.scope)
          ?.expectAsOrNull<IrClassReference>()
          ?.classType
          ?.rawTypeOrNull()
          ?.classIdOrFail
      }
    for (contributionScope in contributionScopes) {
      val callableName = contributionScope.joinSimpleNames().shortClassName

      val function =
        pluginContext.irFactory
          .buildFun {
            name = callableName
            origin = Origins.Default
            returnType = pluginContext.irBuiltIns.unitType
          }
          .apply {
            parameters +=
              buildValueParameter(this) {
                name = Symbols.Names.contributed
                type = declaration.defaultType
                kind = IrParameterKind.Regular
              }
            body = stubExpressionBody(metroContext)
          }

      val fileNameWithoutExtension =
        sequence {
            yieldAll(Symbols.FqNames.metroHintsPackage.pathSegments())
            yieldAll(declaration.classIdOrFail.relativeClassName.pathSegments())
            yield(callableName)
          }
          .joinToString(separator = "") { it.asString().capitalizeUS() }
          .decapitalizeUS()

      val fileName = "${fileNameWithoutExtension}.kt"
      val firFile = buildFile {
        moduleData = (declaration.metadata as FirMetadataSource.Class).fir.moduleData
        origin = FirDeclarationOrigin.Synthetic.PluginFile
        packageDirective = buildPackageDirective {
          packageFqName = Symbols.FqNames.metroHintsPackage
        }
        name = fileName
      }

      /*
      This is weird! In short, kotlinc's incremental compilation support _wants_ this to be an
      absolute path. We obviously don't have a real path to offer it here though since this is a
      synthetic file. However, if we just... make up a file path (in this case — a deterministic
      synthetic sibling file in the same directory as the source file), it seems to work fine.

      Is this good? Heeeeeell no. Will it probably some day break? Maybe. But for now, this works
      and we can keep an eye on https://youtrack.jetbrains.com/issue/KT-74778 for a better long term
      solution.
      */
      val fakeNewPath = Path(declaration.fileEntry.name).parent.resolve(fileName)
      val hintFile =
        IrFileImpl(
            fileEntry = NaiveSourceBasedFileEntryImpl(fakeNewPath.absolutePathString()),
            packageFragmentDescriptor =
              EmptyPackageFragmentDescriptor(
                moduleFragment.descriptor,
                Symbols.FqNames.metroHintsPackage,
              ),
            module = moduleFragment,
          )
          .also { it.metadata = FirMetadataSource.File(firFile) }
      moduleFragment.addFile(hintFile)
      hintFile.addChild(function)
      pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)
      hintFile.dumpToMetroLog(fakeNewPath.name)
    }
  }
}
