/*
 * Copyright (C) 2025 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
                  name = "${hintCallableId.callableName}.kt"
                }
              )
          }
      hintFile.addChild(function)
      pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)
    }
  }
}
