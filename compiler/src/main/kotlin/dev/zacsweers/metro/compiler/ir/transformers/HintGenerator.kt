// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrScope
import dev.zacsweers.metro.compiler.ir.addDeprecatedHiddenAnnotation
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.builtinsFinderCompat
import dev.zacsweers.metro.compiler.ir.effectiveVisibility
import dev.zacsweers.metro.compiler.ir.linkDeclarationsInCompilation
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import dev.zacsweers.metro.compiler.ir.trackClassLookup
import dev.zacsweers.metro.compiler.joinSimpleNames
import dev.zacsweers.metro.compiler.symbols.Symbols
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import okio.ByteString.Companion.encodeUtf8
import org.jetbrains.kotlin.backend.common.extensions.IrGeneratedDeclarationsRegistrar
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.builder.buildPackageDirective
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.builder.buildFile
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
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
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.JvmStandardClassIds
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
@Inject
@SingleIn(IrScope::class)
internal class HintGenerator(context: IrMetroContext, val moduleFragment: IrModuleFragment) :
  IrMetroContext by context {

  @IgnorableReturnValue
  fun generateHint(
    sourceClass: IrClass,
    hintName: Name,
    // IR-only contribution-provider containers are generated after FIR, so they do not have a
    // usable FirMetadataSource. Use the original source class for synthetic file metadata while the
    // hint parameter still points at the generated class that downstream lookups should load.
    metadataSourceClass: IrClass = sourceClass,
    sourceOwnedJvmHint: Boolean = false,
  ): IrSimpleFunction {
    val function =
      pluginContext.irFactory
        .buildFun {
          name = hintName
          origin = Origins.Default
          returnType = pluginContext.irBuiltIns.unitType
          visibility = sourceClass.effectiveVisibility()
        }
        .apply {
          parameters +=
            buildValueParameter(this) {
              name = Symbols.Names.contributed
              type = sourceClass.defaultType
              kind = IrParameterKind.Regular
            }
          body = stubExpressionBody()
          if (sourceOwnedJvmHint) {
            addDeprecatedHiddenAnnotation()
          }
        }

    val fileName =
      if (sourceOwnedJvmHint) {
        // Keep the full digest so truncation and Java hash collisions cannot merge hint files.
        val identity =
          "jvm-contribution-hint:${sourceClass.classIdOrFail.asString()}#${hintName.asString()}"
        // The 71-byte JVM facade fits the minimum supported generated-class name limit (96).
        "Hint_${identity.encodeUtf8().sha256().hex()}.kt"
      } else {
        hintFileName(sourceClass.classIdOrFail, hintName)
      }
    val firFile = buildFile {
      val metadataSource = metadataSourceClass.metadata as? FirMetadataSource.Class
      if (metadataSource == null) {
        reportCompat(
          metadataSourceClass,
          MetroDiagnostics.METRO_ERROR,
          "Class ${metadataSourceClass.classId} does not have a valid metadata source. Found ${metadataSourceClass.metadata?.javaClass?.canonicalName}.",
        )
      }
      moduleData = (metadataSourceClass.metadata as FirMetadataSource.Class).fir.moduleData
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
    val fakeNewPath = Path(metadataSourceClass.fileEntry.name).parent.resolve(fileName)
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
        .also {
          it.metadata = FirMetadataSource.File(firFile)
          moduleFragment.addFile(it)
        }
    hintFile.addChild(function)
    if (sourceOwnedJvmHint) {
      val sourcePath = metadataSourceClass.fileEntry.name
      check(Path(sourcePath).isAbsolute) {
        "JVM contribution hints require an absolute source path for ${sourceClass.classId}"
      }
      prepareJvmHintFile(hintFile, sourcePath, fileName)
    }
    metadataDeclarationRegistrarCompat.registerFunctionAsMetadataVisible(function)
    if (!sourceOwnedJvmHint) {
      // Legacy synthetic paths need explicit links to invalidate their hints.
      // https://github.com/ZacSweers/metro/pull/1349
      trackClassLookup(function, sourceClass)
      // Scope changes and annotation removal also need to invalidate the previous hint file.
      // https://github.com/ZacSweers/metro/pull/1637
      // https://github.com/ZacSweers/metro/issues/1393
      linkDeclarationsInCompilation(callingFile = hintFile, metadataSourceClass)
    }
    hintFile.dumpToMetroLog(fileName)
    return function
  }

  /**
   * Lets JVM output tracking delete each hint when its originating source changes or disappears.
   */
  fun prepareJvmHintFile(file: IrFile, sourcePath: String, hintFileName: String) {
    file.fileEntry = NaiveSourceBasedFileEntryImpl(sourcePath)
    file.clearTopLevelPluginFileMarkerCompat()
    val jvmNameConstructor =
      builtinsFinderCompat()
        .findClass(ClassId.topLevel(JvmStandardClassIds.JVM_NAME))!!
        .owner
        .primaryConstructor!!
    val jvmNameAnnotation =
      with(pluginContext) {
        buildAnnotation(file.symbol, jvmNameConstructor.symbol) { annotation ->
          annotation.arguments[0] =
            irString(PackagePartClassUtils.getFilePartShortName(hintFileName))
        }
      }
    file.addAnnotationCompat(jvmNameAnnotation)
  }

  companion object {
    fun hintFileName(sourceClassId: ClassId, hintName: Name): String {
      val fileNameWithoutExtension = sequence {
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
