// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.k230

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import kotlin.reflect.KClass
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceElementOffsetStrategy
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticWithoutSource
import org.jetbrains.kotlin.diagnostics.KtSourcelessDiagnosticFactory
import org.jetbrains.kotlin.fakeElement as fakeElementNative
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DeprecationsProvider
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.builder.FirSimpleFunctionBuilder
import org.jetbrains.kotlin.fir.declarations.builder.FirValueParameterBuilder
import org.jetbrains.kotlin.fir.declarations.builder.buildSimpleFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameterCopy
import org.jetbrains.kotlin.fir.declarations.getBooleanArgument
import org.jetbrains.kotlin.fir.declarations.getDeprecationsProvider
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.declarations.result
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirExpressionEvaluator
import org.jetbrains.kotlin.fir.expressions.PrivateConstantEvaluatorAPI
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.plugin.SimpleFunctionBuildingContext
import org.jetbrains.kotlin.fir.plugin.createMemberFunction as createMemberFunctionNative
import org.jetbrains.kotlin.fir.plugin.createTopLevelFunction as createTopLevelFunctionNative
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.toEffectiveVisibility
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.IrBuilder
import org.jetbrains.kotlin.ir.builders.declarations.IrFieldBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrMutableAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.util.PrivateForInline

public class CompatContextImpl : CompatContext {

  override fun KtSourcelessDiagnosticFactory.createCompat(
    message: String,
    location: CompilerMessageSourceLocation?,
    languageVersionSettings: LanguageVersionSettings,
  ): KtDiagnosticWithoutSource? {
    return create(message, languageVersionSettings)
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

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun FirExtension.createTopLevelFunction(
    key: GeneratedDeclarationKey,
    callableId: CallableId,
    returnType: ConeKotlinType,
    containingFileName: String?,
    config: SimpleFunctionBuildingContext.() -> Unit,
  ): FirFunction {
    return createTopLevelFunctionNative(key, callableId, returnType, containingFileName, config)
  }

  @ExperimentalTopLevelDeclarationsGenerationApi
  override fun FirExtension.createTopLevelFunction(
    key: GeneratedDeclarationKey,
    callableId: CallableId,
    returnTypeProvider: (List<FirTypeParameter>) -> ConeKotlinType,
    containingFileName: String?,
    config: SimpleFunctionBuildingContext.() -> Unit,
  ): FirFunction {
    return createTopLevelFunctionNative(
      key,
      callableId,
      returnTypeProvider,
      containingFileName,
      config,
    )
  }

  override fun FirExtension.createMemberFunction(
    owner: FirClassSymbol<*>,
    key: GeneratedDeclarationKey,
    name: Name,
    returnType: ConeKotlinType,
    config: SimpleFunctionBuildingContext.() -> Unit,
  ): FirFunction {
    return createMemberFunctionNative(owner, key, name, returnType, config)
  }

  override fun FirExtension.createMemberFunction(
    owner: FirClassSymbol<*>,
    key: GeneratedDeclarationKey,
    name: Name,
    returnTypeProvider: (List<FirTypeParameter>) -> ConeKotlinType,
    config: SimpleFunctionBuildingContext.() -> Unit,
  ): FirFunction {
    return createMemberFunctionNative(owner, key, name, returnTypeProvider, config)
  }

  override fun KtSourceElement.fakeElement(
    newKind: KtFakeSourceElementKind,
    startOffset: Int,
    endOffset: Int,
  ): KtSourceElement {
    return fakeElementNative(
      newKind,
      KtSourceElementOffsetStrategy.Custom.Initialized(startOffset, endOffset),
    )
  }

  override fun FirFunction.isNamedFunction(): Boolean {
    return this is FirSimpleFunction
  }

  override fun FirDeclarationGenerationExtension.buildMemberFunction(
    owner: FirClassLikeSymbol<*>,
    returnTypeProvider: (List<FirTypeParameterRef>) -> ConeKotlinType,
    callableId: CallableId,
    origin: FirDeclarationOrigin,
    visibility: Visibility,
    modality: Modality,
    body: CompatContext.FunctionBuilderScope.() -> Unit,
  ): FirFunction {
    return buildSimpleFunction {
      resolvePhase = FirResolvePhase.BODY_RESOLVE
      moduleData = session.moduleData
      this.origin = origin

      source = owner.source?.fakeElement(KtFakeSourceElementKind.PluginGenerated)

      val functionSymbol = FirNamedFunctionSymbol(callableId)
      symbol = functionSymbol
      name = callableId.callableName

      status =
        FirResolvedDeclarationStatusImpl(
          visibility,
          modality,
          Visibilities.Public.toEffectiveVisibility(owner, forClass = true),
        )

      dispatchReceiverType = owner.constructType()

      FunctionBuilderScopeImpl(this).body()

      // Must go after body() because type parameters are added there
      this.returnTypeRef = returnTypeProvider(typeParameters).toFirResolvedTypeRef()
    }
  }

  private class FunctionBuilderScopeImpl(private val builder: FirSimpleFunctionBuilder) :
    CompatContext.FunctionBuilderScope {
    override val symbol: FirNamedFunctionSymbol
      get() = builder.symbol

    override val typeParameters: MutableList<FirTypeParameter>
      get() = builder.typeParameters

    override val valueParameters: MutableList<FirValueParameter>
      get() = builder.valueParameters
  }

  override fun IrProperty.addBackingFieldCompat(builder: IrFieldBuilder.() -> Unit): IrField {
    return addBackingField(builder)
  }

  override fun createIrGeneratedDeclarationsRegistrar(
    pluginContext: IrPluginContext
  ): IrGeneratedDeclarationsRegistrarCompat {
    return IrConstructorCallIrGeneratedDeclarationsRegistrarCompat(
      pluginContext.metadataDeclarationRegistrar
    )
  }

  override fun IrBuilder.irAnnotationCompat(
    callee: IrConstructorSymbol,
    typeArguments: List<IrType>,
  ): IrConstructorCall {
    return irCallConstructor(callee, typeArguments)
  }

  override fun IrAnnotationContainer.addAnnotationCompat(annotation: IrConstructorCall) {
    replaceAnnotationsCompat(annotationsCompat() + annotation)
  }

  override fun IrAnnotationContainer.addAnnotationsCompat(annotations: List<IrConstructorCall>) {
    replaceAnnotationsCompat(annotationsCompat() + annotations)
  }

  override fun IrAnnotationContainer.annotationsCompat(): List<IrConstructorCall> {
    return annotations
  }

  override fun IrAnnotationContainer.replaceAnnotationsCompat(
    annotations: List<IrConstructorCall>
  ) {
    (this as IrMutableAnnotationContainer).annotations = annotations
  }

  override fun IrPluginContext.finderForBuiltinsCompat(): CompatContext.DeclarationFinderCompat {
    return ReferenceApiDeclarationFinderCompat(this)
  }

  override fun IrPluginContext.finderForSourceCompat(
    fromFile: IrFile
  ): CompatContext.DeclarationFinderCompat {
    return ReferenceApiDeclarationFinderCompat(this)
  }

  override fun <T : FirElement> FirExpression.evaluateAsCompat(
    session: FirSession,
    tKlass: KClass<T>,
  ): T? {
    @Suppress("UNCHECKED_CAST") @OptIn(PrivateConstantEvaluatorAPI::class, PrivateForInline::class)
    return FirExpressionEvaluator.evaluateExpression(this, session)?.result as? T
  }

  override fun FirAnnotationContainer.getDeprecationsProviderCompat(
    session: FirSession
  ): DeprecationsProvider? {
    return getDeprecationsProvider(session)
  }

  override fun FirAnnotation.getBooleanArgumentCompat(
    name: Name,
    session: FirSession,
  ): Boolean? {
    return getBooleanArgument(name, session)
  }

  override fun FirAnnotation.getStringArgumentCompat(
    name: Name,
    session: FirSession,
  ): String? {
    return getStringArgument(name, session)
  }

  override fun buildValueParameterCopyCompat(
    original: FirValueParameter,
    init: FirValueParameterBuilder.() -> Unit,
  ): FirValueParameter {
    return buildValueParameterCopy(original, init)
  }

  override fun CompilerConfiguration.messageCollectorCompat(): MessageCollector {
    return get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY)
      ?: PrintingMessageCollector(System.err, MessageRenderer.PLAIN_FULL_PATHS, false)
  }

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "2.3.0"

    override fun create(): CompatContext = CompatContextImpl()
  }

  private class ReferenceApiDeclarationFinderCompat(private val context: IrPluginContext) :
    CompatContext.DeclarationFinderCompat {
    override fun findClass(classId: ClassId): IrClassSymbol? {
      return context.referenceClass(classId)
    }

    override fun findClassifier(classId: ClassId): IrSymbol? {
      return context.referenceClass(classId)
    }

    override fun findConstructors(classId: ClassId): Collection<IrConstructorSymbol> {
      return context.referenceConstructors(classId)
    }

    override fun findFunctions(callableId: CallableId): Collection<IrSimpleFunctionSymbol> {
      return context.referenceFunctions(callableId)
    }

    override fun findProperties(callableId: CallableId): Collection<IrPropertySymbol> {
      return context.referenceProperties(callableId)
    }
  }
}
