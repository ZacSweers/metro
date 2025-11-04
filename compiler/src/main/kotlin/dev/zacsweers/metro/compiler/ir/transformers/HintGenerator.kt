// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.joinSimpleNames
import dev.zacsweers.metro.compiler.symbols.Symbols
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
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFile
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fileEntry
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * A helper that generates hint marker functions for _downstream_ compilations. In-compilation
 * contributions are looked up directly. This works by generating hints into a synthetic
 * [IrFileImpl] in the [Symbols.FqNames.metroHintsPackage] package. The signature of the function is
 * simply a generated name and parameter type pointing at the contributing class. This class is then
 * looked up separately.
 *
 * Example of a generated synthetic function:
 * ```
 * fun com_example_AppScope(contributed: MyClass) = error("Stub!")
 * ```
 *
 * Note that the generated name may take other forms determined by the caller of [generateHint].
 *
 * Importantly, we also add these generated functions to metadata via
 * [IrGeneratedDeclarationsRegistrar.registerFunctionAsMetadataVisible], which ensures they are
 * visible to downstream compilations.
 *
 * File creation is on a little big of shaky ground, but necessary for this to work. More
 * explanation can be found below.
 */
internal class HintGenerator(context: IrMetroContext, val moduleFragment: IrModuleFragment) :
  IrMetroContext by context {

  fun generateHint(
    sourceClass: IrClass,
    hintName: Name,
    hintAnnotations: List<IrAnnotation> = emptyList(),
  ): IrSimpleFunction {
    val function =
      pluginContext.irFactory
        .buildFun {
          name = hintName
          origin = Origins.Default
          returnType = pluginContext.irBuiltIns.unitType
          visibility = sourceClass.visibility
        }
        .apply {
          parameters +=
            buildValueParameter(this) {
              name = Symbols.Names.contributed
              type = sourceClass.defaultType
              kind = IrParameterKind.Regular
            }
          body = stubExpressionBody()
          annotations += hintAnnotations.map { it.ir }
        }

    val fileName = hintFileName(sourceClass.classIdOrFail, hintName)

    val firFile = buildFile {
      val metadataSource = sourceClass.metadata as? FirMetadataSource.Class
      if (metadataSource == null) {
        reportCompat(
          sourceClass,
          MetroDiagnostics.METRO_ERROR,
          "Class ${sourceClass.classId} does not have a valid metadata source. Found ${sourceClass.metadata?.javaClass?.canonicalName}.",
        )
      }
      moduleData = (sourceClass.metadata as FirMetadataSource.Class).fir.moduleData
      origin = FirDeclarationOrigin.Synthetic.PluginFile
      packageDirective = buildPackageDirective { packageFqName = Symbols.FqNames.metroHintsPackage }
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
    val fakeNewPath = Path(sourceClass.fileEntry.name).parent.resolve(fileName)
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
    return function
  }

  companion object {
    fun hintFileName(sourceClassId: ClassId, hintName: Name): String {
      val fileNameWithoutExtension =
        sequence {
            yieldAll(sourceClassId.packageFqName.pathSegments())
            yield(sourceClassId.joinSimpleNames(separator = "", camelCase = true).shortClassName)
            yield(hintName)
          }
          .joinToString(separator = "") { it.asString().capitalizeUS() }
          .decapitalizeUS()
      return "$fileNameWithoutExtension.kt"
    }
  }
}
