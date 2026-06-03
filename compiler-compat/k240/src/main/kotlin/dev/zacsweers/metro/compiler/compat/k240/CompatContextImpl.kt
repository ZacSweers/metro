// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k240

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import dev.zacsweers.metro.compiler.compat.k2320.CompatContextImpl as DelegateType
import kotlin.reflect.KClass
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirEvaluatorResult
import org.jetbrains.kotlin.fir.FirEvaluatorResult.CompileTimeException
import org.jetbrains.kotlin.fir.FirEvaluatorResult.Evaluated
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DeprecationsProvider
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.builder.FirValueParameterBuilder
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameterCopy
import org.jetbrains.kotlin.fir.declarations.getDeprecationsProvider
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirExpressionEvaluator
import org.jetbrains.kotlin.fir.expressions.PrivateConstantEvaluatorAPI
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.IrBuilder
import org.jetbrains.kotlin.ir.builders.irAnnotation
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.types.IrType

public class CompatContextImpl : CompatContext by DelegateType() {
  context(_: CompilerPluginRegistrar)
  override fun CompilerPluginRegistrar.ExtensionStorage.registerFirExtensionCompat(
    extension: FirExtensionRegistrar
  ) {
    FirExtensionRegistrarAdapter.registerExtension(extension)
  }

  context(_: CompilerPluginRegistrar)
  override fun CompilerPluginRegistrar.ExtensionStorage.registerIrExtensionCompat(
    extension: IrGenerationExtension
  ) {
    IrGenerationExtension.registerExtension(extension)
  }

  override fun createIrGeneratedDeclarationsRegistrar(
    pluginContext: IrPluginContext
  ): IrGeneratedDeclarationsRegistrarCompat {
    return IrAnnotationIrGeneratedDeclarationsRegistrarCompat(
      pluginContext.metadataDeclarationRegistrar
    )
  }

  override fun IrBuilder.irAnnotationCompat(
    callee: IrConstructorSymbol,
    typeArguments: List<IrType>,
  ): IrConstructorCall {
    return irAnnotation(callee, typeArguments)
  }

  override fun <T : FirElement> FirExpression.evaluateAsCompat(
    session: FirSession,
    tKlass: KClass<T>,
  ): T? {
    @OptIn(PrivateConstantEvaluatorAPI::class)
    return FirExpressionEvaluator.evaluateExpression(this, session)?.unwrapOr {}
  }

  override fun <A : Any> IrDiagnosticReporter.reportAt(
    declaration: IrDeclaration,
    factory: KtDiagnosticFactory1<A>,
    a: A,
  ) {
    at(declaration).report(factory, a)
  }

  override fun <A : Any> IrDiagnosticReporter.reportAt(
    element: IrElement,
    file: IrFile,
    factory: KtDiagnosticFactory1<A>,
    a: A,
  ) {
    at(element, file).report(factory, a)
  }

  override fun FirAnnotationContainer.getDeprecationsProviderCompat(
    session: FirSession
  ): DeprecationsProvider? {
    return when (this) {
      is FirCallableDeclaration -> getDeprecationsProvider(session)
      is FirClassLikeDeclaration -> getDeprecationsProvider(session)
      else -> null
    }
  }

  override fun buildValueParameterCopyCompat(
    original: FirValueParameter,
    init: FirValueParameterBuilder.() -> Unit,
  ): FirValueParameter {
    return buildValueParameterCopy(original, init)
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.4.0"

    override fun create(): CompatContext = CompatContextImpl()
  }
}

public fun <T : FirElement> FirEvaluatorResult.unwrapOr(
  action: (CompileTimeException) -> Unit
): T? {
  @Suppress("UNCHECKED_CAST")
  when (this) {
    is CompileTimeException -> action(this)
    is Evaluated -> return this.result as? T
    else -> return null
  }
  return null
}
