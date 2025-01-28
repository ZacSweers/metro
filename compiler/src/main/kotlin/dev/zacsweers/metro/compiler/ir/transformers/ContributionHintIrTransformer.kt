package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.fir.hintCallableId
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.stubExpressionBody
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.builder.buildPackageDirective
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.builder.buildFile
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFile
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.defaultType

internal class ContributionHintIrTransformer(
  context: IrMetroContext,
  private val moduleFragment: IrModuleFragment,
) : IrMetroContext by context {
  fun visitClass(declaration: IrClass) {
    if (declaration.isAnnotatedWithAny(symbols.classIds.allContributesAnnotations)) {
      val hintCallableId = declaration.classIdOrFail.hintCallableId
      val function =
        pluginContext.irFactory
          .buildFun {
            name = hintCallableId.callableName
            origin = Origins.Default
            returnType = declaration.defaultType
          }
          .apply { body = stubExpressionBody(metroContext) }

      val hintFile =
        IrFileImpl(
            fileEntry = NaiveSourceBasedFileEntryImpl(hintCallableId.callableName.asString()),
            EmptyPackageFragmentDescriptor(
              moduleFragment.descriptor,
              Symbols.FqNames.metroHintsPackage,
            ),
            moduleFragment,
          )
          .also {
            moduleFragment.addFile(it)
            it.metadata =
              FirMetadataSource.File(
                buildFile {
                  moduleData = (declaration.metadata as FirMetadataSource.Class).fir.moduleData
                  origin = FirDeclarationOrigin.Synthetic.PluginFile
                  packageDirective = buildPackageDirective {
                    packageFqName = Symbols.FqNames.metroHintsPackage
                  }
                  name = hintCallableId.callableName.toString()
                }
              )
          }
      hintFile.addChild(function)
      pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)
    }
  }
}
