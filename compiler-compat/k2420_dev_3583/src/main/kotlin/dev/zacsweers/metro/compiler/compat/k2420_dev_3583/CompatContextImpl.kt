// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k2420_dev_3583

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import dev.zacsweers.metro.compiler.compat.k240.CompatContextImpl as DelegateType
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.util.CustomKotlinLikeDumpStrategy
import org.jetbrains.kotlin.ir.util.KotlinLikeDumpOptions
import org.jetbrains.kotlin.ir.util.dumpKotlinLike

public class CompatContextImpl : CompatContext by DelegateType() {
  override fun createIrGeneratedDeclarationsRegistrar(
    pluginContext: IrPluginContext
  ): IrGeneratedDeclarationsRegistrarCompat {
    return IrAnnotationIrGeneratedDeclarationsRegistrarCompat(
      pluginContext.metadataDeclarationRegistrar
    )
  }

  override val pluginGeneratedSourceElementKind: KtFakeSourceElementKind
    get() = KtFakeSourceElementKind.PluginGenerated.Default

  override fun IrElement.dumpKotlinLikeCompat(
    options: KotlinLikeDumpOptions,
    classNameTransformer: (context: IrDeclaration?, declaration: IrDeclarationWithName) -> String,
    fallback: () -> String,
  ): String {
    val customDumpStrategy = options.customDumpStrategy
    return dumpKotlinLike(
      options =
        options.copy(
          customDumpStrategy =
            object : CustomKotlinLikeDumpStrategy by customDumpStrategy {
              override fun nameOf(
                container: IrDeclaration?,
                declaration: IrDeclarationWithName,
              ): String {
                return classNameTransformer(container, declaration)
              }
            }
        )
    )
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.4.20-dev-3583"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
