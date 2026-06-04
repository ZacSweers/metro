// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k240

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import dev.zacsweers.metro.compiler.compat.k240_dev_2124.CompatContextImpl as DelegateType
import dev.zacsweers.metro.compiler.compat.k240_dev_2124.unwrapOr
import kotlin.reflect.KClass
import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DeprecationsProvider
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.builder.FirValueParameterBuilder
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameterCopy
import org.jetbrains.kotlin.fir.declarations.getBooleanArgument
import org.jetbrains.kotlin.fir.declarations.getDeprecationsProvider
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirExpressionEvaluator
import org.jetbrains.kotlin.fir.expressions.PrivateConstantEvaluatorAPI
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.IrBuilder
import org.jetbrains.kotlin.ir.builders.irAnnotation
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrMutableAnnotationContainer
import org.jetbrains.kotlin.ir.expressions.IrAnnotation as KotlinIrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

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

  override fun IrAnnotationContainer.addAnnotationCompat(annotation: IrConstructorCall) {
    replaceAnnotationsCompat(annotationsCompat() + annotation)
  }

  override fun IrAnnotationContainer.annotationsCompat(): List<IrConstructorCall> {
    return (annotations as List<*>).map { it as IrConstructorCall }
  }

  override fun IrAnnotationContainer.addAnnotationsCompat(annotations: List<IrConstructorCall>) {
    replaceAnnotationsCompat(annotationsCompat() + annotations)
  }

  override fun IrAnnotationContainer.replaceAnnotationsCompat(
    annotations: List<IrConstructorCall>
  ) {
    (this as IrMutableAnnotationContainer).annotations = annotations.map {
      it.toKotlinIrAnnotation()
    }
  }

  override fun IrPluginContext.finderForBuiltinsCompat(): CompatContext.DeclarationFinderCompat {
    return finderForBuiltins().asCompat()
  }

  override fun IrPluginContext.finderForSourceCompat(
    fromFile: IrFile
  ): CompatContext.DeclarationFinderCompat {
    return finderForSource(fromFile).asCompat()
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

  override fun FirAnnotation.getBooleanArgumentCompat(
    name: Name,
    session: FirSession,
  ): Boolean? {
    return getBooleanArgument(name)
  }

  override fun FirAnnotation.getStringArgumentCompat(
    name: Name,
    session: FirSession,
  ): String? {
    return getStringArgument(name)
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

private fun IrConstructorCall.toKotlinIrAnnotation(): KotlinIrAnnotation {
  if (this is KotlinIrAnnotation) return this
  val call = this
  return IrAnnotationImpl(
      startOffset = startOffset,
      endOffset = endOffset,
      type = type,
      symbol = symbol,
      typeArgumentsCount = typeArguments.size,
      constructorTypeArgumentsCount = constructorTypeArgumentsCount,
      origin = origin,
      source = source,
    )
    .apply {
      for (param in call.symbol.owner.parameters) {
        arguments[param.indexInParameters] = call.arguments[param.indexInParameters]
      }
    }
}

private fun DeclarationFinder.asCompat(): CompatContext.DeclarationFinderCompat {
  val finder = this
  return object : CompatContext.DeclarationFinderCompat {
    override fun findClass(classId: ClassId): IrClassSymbol? {
      return finder.findClass(classId)
    }

    override fun findClassifier(classId: ClassId): IrSymbol? {
      return finder.findClassifier(classId)
    }

    override fun findConstructors(classId: ClassId): Collection<IrConstructorSymbol> {
      return finder.findConstructors(classId)
    }

    override fun findFunctions(callableId: CallableId): Collection<IrSimpleFunctionSymbol> {
      return finder.findFunctions(callableId)
    }

    override fun findProperties(callableId: CallableId): Collection<IrPropertySymbol> {
      return finder.findProperties(callableId)
    }
  }
}
