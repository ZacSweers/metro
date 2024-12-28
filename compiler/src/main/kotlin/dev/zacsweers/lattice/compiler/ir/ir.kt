/*
 * Copyright (C) 2024 Zac Sweers
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
package dev.zacsweers.lattice.compiler.ir

import dev.zacsweers.lattice.compiler.LatticeOrigin
import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.ir.parameters.Parameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameters
import dev.zacsweers.lattice.compiler.ir.parameters.wrapInLazy
import dev.zacsweers.lattice.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.lattice.compiler.letIf
import java.util.Objects
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.addExtensionReceiver
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.backend.jvm.ir.parentClassId
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocationWithRange
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.lazy.Fir2IrLazyClass
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.utils.valueArguments
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.IrGeneratorContext
import org.jetbrains.kotlin.ir.builders.IrStatementsBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrExternalPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrMutableAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.addMember
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrScriptSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.createType
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.util.copyValueParametersFrom
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.util.toIrConst
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Finds the line and column of [this] within its file. */
internal fun IrDeclaration.location(): CompilerMessageSourceLocation {
  return locationOrNull() ?: error("No location found for ${dumpKotlinLike()}!")
}

/** Finds the line and column of [this] within its file or returns null if this has no location. */
internal fun IrDeclaration.locationOrNull(): CompilerMessageSourceLocation? {
  return fileOrNull?.let(::locationIn)
}

/** Finds the line and column of [this] within this [file]. */
internal fun IrElement?.locationIn(file: IrFile): CompilerMessageSourceLocation {
  val sourceRangeInfo =
    file.fileEntry.getSourceRangeInfo(
      beginOffset = this?.startOffset ?: SYNTHETIC_OFFSET,
      endOffset = this?.endOffset ?: SYNTHETIC_OFFSET,
    )
  return CompilerMessageLocationWithRange.create(
    path = sourceRangeInfo.filePath,
    lineStart = sourceRangeInfo.startLineNumber + 1,
    columnStart = sourceRangeInfo.startColumnNumber + 1,
    lineEnd = sourceRangeInfo.endLineNumber + 1,
    columnEnd = sourceRangeInfo.endColumnNumber + 1,
    lineContent = null,
  )!!
}

/** Returns the raw [IrClass] of this [IrType] or throws. */
internal fun IrType.rawType(): IrClass {
  return rawTypeOrNull()
    ?: run { error("Unrecognized type! ${dumpKotlinLike()} (${classifierOrNull?.javaClass})") }
}

/** Returns the raw [IrClass] of this [IrType] or null. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrType.rawTypeOrNull(): IrClass? {
  return when (val classifier = classifierOrNull) {
    is IrClassSymbol -> classifier.owner
    is IrTypeParameterSymbol -> null
    else -> null
  }
}

internal fun IrAnnotationContainer.isAnnotatedWithAny(names: Collection<ClassId>): Boolean {
  return names.any { hasAnnotation(it) }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrAnnotationContainer.annotationsIn(names: Set<ClassId>): Sequence<IrConstructorCall> {
  return annotations.asSequence().filter { it.symbol.owner.parentAsClass.classId in names }
}

internal fun <T> IrConstructorCall.constArgumentOfTypeAt(position: Int): T? {
  return (getValueArgument(position) as? IrConst?)?.valueAs()
}

internal fun <T> IrConst.valueAs(): T {
  @Suppress("UNCHECKED_CAST")
  return value as T
}

internal fun IrPluginContext.irType(
  classId: ClassId,
  nullable: Boolean = false,
  arguments: List<IrTypeArgument> = emptyList(),
): IrType = referenceClass(classId)!!.createType(hasQuestionMark = nullable, arguments = arguments)

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrGeneratorContext.createIrBuilder(symbol: IrSymbol): DeclarationIrBuilder {
  return DeclarationIrBuilder(this, symbol, symbol.owner.startOffset, symbol.owner.endOffset)
}

internal fun IrPluginContext.buildBlockBody(
  blockBody: DeclarationIrBuilder.(MutableList<IrStatement>) -> Unit = {}
): IrBlockBody {
  val startOffset = UNDEFINED_OFFSET
  val endOffset = UNDEFINED_OFFSET
  val builder =
    DeclarationIrBuilder(
      generatorContext = this,
      symbol = IrSimpleFunctionSymbolImpl(),
      startOffset = startOffset,
      endOffset = endOffset,
    )
  val body =
    irFactory.createBlockBody(startOffset = startOffset, endOffset = endOffset).apply {
      builder.blockBody(statements)
    }
  return body
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrBuilderWithScope.irInvoke(
  dispatchReceiver: IrExpression? = null,
  extensionReceiver: IrExpression? = null,
  callee: IrFunctionSymbol,
  typeHint: IrType? = null,
  args: List<IrExpression?> = emptyList(),
): IrMemberAccessExpression<*> {
  assert(callee.isBound) { "Symbol $callee expected to be bound" }
  val returnType = typeHint ?: callee.owner.returnType
  val call = irCall(callee, type = returnType)
  call.extensionReceiver = extensionReceiver
  call.dispatchReceiver = dispatchReceiver
  args.forEachIndexed(call::putValueArgument)
  return call
}

internal fun IrClass.addOverride(
  baseFunction: IrSimpleFunction,
  modality: Modality = Modality.FINAL,
  overriddenSymbols: List<IrSimpleFunctionSymbol> = listOf(baseFunction.symbol),
): IrSimpleFunction =
  addOverride(
      baseFunction.kotlinFqName,
      baseFunction.name,
      baseFunction.returnType,
      modality,
      overriddenSymbols,
    )
    .apply {
      dispatchReceiverParameter = this@addOverride.thisReceiver?.copyTo(this)
      copyValueParametersFrom(baseFunction)
    }

internal fun IrClass.addOverride(
  baseFqName: FqName,
  simpleName: Name,
  returnType: IrType,
  modality: Modality = Modality.FINAL,
  overriddenSymbols: List<IrSimpleFunctionSymbol> = findOverridesOf(simpleName, baseFqName),
): IrSimpleFunction =
  addFunction(simpleName.asString(), returnType, modality).apply {
    this.overriddenSymbols = overriddenSymbols
  }

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.findOverridesOf(
  simpleName: Name,
  baseFqName: FqName,
): List<IrSimpleFunctionSymbol> {
  return superTypes
    .mapNotNull { superType ->
      superType.classOrNull?.owner?.takeIf { superClass ->
        superClass.isSubclassOfFqName(baseFqName)
      }
    }
    .flatMap { superClass ->
      superClass.functions
        .filter { function ->
          function.name == simpleName && function.overridesFunctionIn(baseFqName)
        }
        .map { it.symbol }
        .toList()
    }
}

internal fun IrStatementsBuilder<*>.irTemporary(
  value: IrExpression? = null,
  nameHint: String? = null,
  irType: IrType = value?.type!!, // either value or irType should be supplied at callsite
  isMutable: Boolean = false,
  origin: IrDeclarationOrigin = IrDeclarationOrigin.IR_TEMPORARY_VARIABLE,
): IrVariable {
  val temporary =
    scope.createTemporaryVariableDeclaration(
      irType,
      nameHint,
      isMutable,
      startOffset = startOffset,
      endOffset = endOffset,
      origin = origin,
    )
  value?.let { temporary.initializer = it }
  +temporary
  return temporary
}

internal fun IrMutableAnnotationContainer.addAnnotation(
  type: IrType,
  constructorSymbol: IrConstructorSymbol,
  body: IrConstructorCall.() -> Unit = {},
) {
  annotations += IrConstructorCallImpl.fromSymbolOwner(type, constructorSymbol).apply(body)
}

internal fun IrClass.isSubclassOfFqName(fqName: FqName): Boolean =
  fqNameWhenAvailable == fqName || superTypes.any { it.erasedUpperBound.isSubclassOfFqName(fqName) }

internal fun IrSimpleFunction.overridesFunctionIn(fqName: FqName): Boolean =
  parentClassOrNull?.fqNameWhenAvailable == fqName ||
    allOverridden().any { it.parentClassOrNull?.fqNameWhenAvailable == fqName }

/**
 * Computes a hash key for this annotation instance composed of its underlying type and value
 * arguments.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrConstructorCall.computeAnnotationHash(): Int {
  return Objects.hash(
    type.rawType().classIdOrFail,
    valueArguments
      .map {
        when (it) {
          is IrConst -> it.value
          is IrClassReference -> it.classType.classOrNull?.owner?.classId
          is IrGetEnumValue -> it.symbol.owner.fqNameWhenAvailable
          else -> {
            error("Unknown annotation argument type: ${it?.let { it::class.java }}")
          }
        }
      }
      .toTypedArray()
      .contentDeepHashCode(),
  )
}

// TODO create an instance of this that caches lookups?
internal fun IrClass.declaredCallableMembers(
  context: LatticeTransformerContext,
  functionFilter: (IrSimpleFunction) -> Boolean = { true },
  propertyFilter: (IrProperty) -> Boolean = { true },
): Sequence<LatticeSimpleFunction> =
  allCallableMembers(
    context,
    excludeAnyFunctions = true,
    excludeInheritedMembers = true,
    excludeCompanionObjectMembers = true,
    functionFilter = functionFilter,
    propertyFilter = propertyFilter,
  )

// TODO create an instance of this that caches lookups?
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.allCallableMembers(
  context: LatticeTransformerContext,
  excludeAnyFunctions: Boolean = true,
  excludeInheritedMembers: Boolean = false,
  excludeCompanionObjectMembers: Boolean = false,
  functionFilter: (IrSimpleFunction) -> Boolean = { true },
  propertyFilter: (IrProperty) -> Boolean = { true },
): Sequence<LatticeSimpleFunction> {
  return functions
    .letIf(excludeAnyFunctions) {
      // TODO optimize this?
      // TODO does this even work
      it.filterNot { function ->
        function.overriddenSymbols.any { symbol ->
          symbol.owner.parentClassId == LatticeSymbols.ClassIds.AnyClass
        }
      }
    }
    .filter(functionFilter)
    .plus(properties.filter(propertyFilter).mapNotNull { property -> property.getter })
    .letIf(excludeInheritedMembers) { it.filterNot { function -> function.isFakeOverride } }
    .let { parentClassCallables ->
      val asFunctions = parentClassCallables.map { context.latticeFunctionOf(it) }
      if (excludeCompanionObjectMembers) {
        asFunctions
      } else {
        companionObject()?.let { companionObject ->
          asFunctions +
            companionObject.allCallableMembers(
              context,
              excludeAnyFunctions,
              excludeInheritedMembers,
              excludeCompanionObjectMembers = false,
            )
        } ?: asFunctions
      }
    }
}

// From
// https://kotlinlang.slack.com/archives/C7L3JB43G/p1672258639333069?thread_ts=1672258597.659509&cid=C7L3JB43G
internal fun irLambda(
  context: IrPluginContext,
  parent: IrDeclarationParent,
  receiverParameter: IrType?,
  valueParameters: List<IrType>,
  returnType: IrType,
  suspend: Boolean = false,
  content: IrBlockBodyBuilder.(IrSimpleFunction) -> Unit,
): IrFunctionExpression {
  val lambda =
    context.irFactory
      .buildFun {
        startOffset = SYNTHETIC_OFFSET
        endOffset = SYNTHETIC_OFFSET
        origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
        name = Name.special("<anonymous>")
        visibility = DescriptorVisibilities.LOCAL
        isSuspend = suspend
        this.returnType = returnType
      }
      .apply {
        this.parent = parent
        receiverParameter?.let { addExtensionReceiver(it) }
        valueParameters.forEachIndexed { index, type -> addValueParameter("arg$index", type) }
        body = context.createIrBuilder(this.symbol).irBlockBody { content(this@apply) }
      }
  return IrFunctionExpressionImpl(
    startOffset = SYNTHETIC_OFFSET,
    endOffset = SYNTHETIC_OFFSET,
    type =
      run {
        when (suspend) {
          false -> context.irBuiltIns.functionN(valueParameters.size)
          else -> context.irBuiltIns.suspendFunctionN(valueParameters.size)
        }.typeWith(*valueParameters.toTypedArray(), returnType)
      },
    origin = IrStatementOrigin.LAMBDA,
    function = lambda,
  )
}

internal fun IrFactory.addCompanionObject(
  symbols: LatticeSymbols,
  parent: IrClass,
  name: Name = LatticeSymbols.Names.CompanionObject,
  body: IrClass.() -> Unit = {},
): IrClass {
  return buildClass {
      this.name = name
      this.modality = Modality.FINAL
      this.kind = ClassKind.OBJECT
      this.isCompanion = true
    }
    .apply {
      this.parent = parent
      parent.addMember(this)
      this.origin = LatticeOrigin
      this.createImplicitParameterDeclarationWithWrappedDescriptor()
      this.addSimpleDelegatingConstructor(
        symbols.anyConstructor,
        symbols.pluginContext.irBuiltIns,
        isPrimary = true,
        origin = LatticeOrigin,
      )
      body()
    }
}

internal val IrClass.isCompanionObject: Boolean
  get() = isObject && isCompanion

internal fun IrBuilderWithScope.irCallConstructorWithSameParameters(
  source: IrSimpleFunction,
  constructor: IrConstructorSymbol,
): IrConstructorCall {
  return irCall(constructor).apply {
    for (parameter in source.valueParameters) {
      putValueArgument(parameter.index, irGet(parameter))
    }
  }
}

internal fun IrBuilderWithScope.irCallWithSameParameters(
  source: IrSimpleFunction,
  function: IrFunctionSymbol,
): IrFunctionAccessExpression {
  return irCall(function).apply {
    for (parameter in source.valueParameters) {
      putValueArgument(parameter.index, irGet(parameter))
    }
  }
}

/**
 * For use with generated factory create() functions, converts parameters to Provider<T> types + any
 * bitmasks for default functions.
 */
internal fun IrBuilderWithScope.parametersAsProviderArguments(
  context: LatticeTransformerContext,
  parameters: Parameters<out Parameter>,
  receiver: IrValueParameter,
  parametersToFields: Map<Parameter, IrField>,
): List<IrExpression?> {
  return buildList {
    addAll(
      parameters.allParameters
        .filterNot { it.isAssisted }
        .map { parameter ->
          parameterAsProviderArgument(context, parameter, receiver, parametersToFields)
        }
    )
  }
}

/** For use with generated factory create() functions. */
internal fun IrBuilderWithScope.parameterAsProviderArgument(
  context: LatticeTransformerContext,
  parameter: Parameter,
  receiver: IrValueParameter,
  parametersToFields: Map<Parameter, IrField>,
): IrExpression {
  // When calling value getter on Provider<T>, make sure the dispatch
  // receiver is the Provider instance itself
  val providerInstance = irGetField(irGet(receiver), parametersToFields.getValue(parameter))
  val typeMetadata = parameter.contextualTypeKey
  return typeAsProviderArgument(
    context,
    typeMetadata,
    providerInstance,
    isAssisted = parameter.isAssisted,
    isGraphInstance = parameter.isGraphInstance,
  )
}

internal fun IrBuilderWithScope.typeAsProviderArgument(
  context: LatticeTransformerContext,
  type: ContextualTypeKey,
  bindingCode: IrExpression,
  isAssisted: Boolean,
  isGraphInstance: Boolean,
): IrExpression {
  val symbols = context.symbols
  if (!bindingCode.type.isLatticeProviderType(context)) {
    // Not a provider, nothing else to do here!
    return bindingCode
  }
  val providerInstance = bindingCode
  return when {
    type.isLazyWrappedInProvider -> {
      // ProviderOfLazy.create(provider)
      irInvoke(
        dispatchReceiver = irGetObject(symbols.providerOfLazyCompanionObject),
        callee = symbols.providerOfLazyCreate,
        args = listOf(providerInstance),
        typeHint = type.typeKey.type.wrapInLazy(symbols).wrapInProvider(symbols.latticeProvider),
      )
    }
    type.isWrappedInProvider -> providerInstance
    // Normally Dagger changes Lazy<Type> parameters to a Provider<Type>
    // (usually the container is a joined type), therefore we use
    // `.lazy(..)` to convert the Provider to a Lazy. Assisted
    // parameters behave differently and the Lazy type is not changed
    // to a Provider and we can simply use the parameter name in the
    // argument list.
    type.isWrappedInLazy && isAssisted -> providerInstance
    type.isWrappedInLazy -> {
      // DoubleCheck.lazy(...)
      irInvoke(
        dispatchReceiver = irGetObject(symbols.doubleCheckCompanionObject),
        callee = symbols.doubleCheckLazy,
        args = listOf(providerInstance),
        typeHint = type.typeKey.type.wrapInLazy(symbols),
      )
    }
    isAssisted || isGraphInstance -> {
      // provider
      providerInstance
    }
    else -> {
      // provider.invoke()
      irInvoke(
        dispatchReceiver = providerInstance,
        callee = symbols.providerInvoke,
        typeHint = type.typeKey.type,
      )
    }
  }
}

internal fun LatticeTransformerContext.assignConstructorParamsToFields(
  constructor: IrConstructor,
  clazz: IrClass,
  parameters: List<Parameter>,
): Map<Parameter, IrField> {
  // Add a constructor parameter + field for every parameter.
  // This should be the provider type unless it's a special instance component type
  val parametersToFields = mutableMapOf<Parameter, IrField>()
  for (parameter in parameters) {
    if (parameter.isAssisted) continue
    val irParameter =
      constructor.addValueParameter(parameter.name, parameter.providerType, LatticeOrigin)
    val irField =
      clazz.addField(irParameter.name, irParameter.type, DescriptorVisibilities.PRIVATE).apply {
        isFinal = true
        initializer = pluginContext.createIrBuilder(symbol).run { irExprBody(irGet(irParameter)) }
      }
    parametersToFields[parameter] = irField
  }
  return parametersToFields
}

/*
 * Implement a static `create()` function for a given target [generatedConstructor].
 *
 * ```kotlin
 * // Simple
 * @JvmStatic // JVM only
 * fun create(valueProvider: Provider<String>): Example_Factory = Example_Factory(valueProvider)
 *
 * // Generic
 * @JvmStatic // JVM only
 * fun <T> create(valueProvider: Provider<T>): Example_Factory<T> = Example_Factory<T>(valueProvider)
 * ```
 */
internal fun IrClass.addStaticCreateFunction(
  context: LatticeTransformerContext,
  targetClass: IrClass,
  targetClassParameterized: IrType,
  targetConstructor: IrConstructorSymbol,
  parameters: Parameters<out Parameter>,
  providerFunction: IrFunction?,
  patchCreationParams: Boolean = true,
): IrSimpleFunction {
  return addFunction("create", targetClassParameterized, isStatic = true).apply {
    val thisFunction = this
    dispatchReceiverParameter = this@addStaticCreateFunction.thisReceiver?.copyTo(this)
    this.copyTypeParameters(targetClass.typeParameters)
    this.origin = LatticeOrigin
    this.visibility = DescriptorVisibilities.PUBLIC
    with(context) { markJvmStatic() }

    val instanceParam =
      parameters.instance?.let { addValueParameter(it.name, it.providerType, LatticeOrigin) }
    parameters.extensionReceiver?.let { addValueParameter(it.name, it.providerType, LatticeOrigin) }
    val valueParamsToPatch =
      parameters.valueParameters
        .filterNot { it.isAssisted }
        .map {
          addValueParameter(it.name, it.providerType, LatticeOrigin).also { irParam ->
            it.typeKey.qualifier?.let {
              // Copy any qualifiers over so they're retrievable during dependency graph resolution
              irParam.annotations += it.ir
            }
          }
        }

    if (patchCreationParams) {
      context.copyParameterDefaultValues(
        providerFunction = providerFunction,
        sourceParameters = parameters.valueParameters.filterNot { it.isAssisted }.map { it.ir },
        targetParameters = valueParamsToPatch,
        targetGraphParameter = instanceParam,
        wrapInProvider = true,
      )
    }

    body =
      context.pluginContext.createIrBuilder(symbol).run {
        irBlockBody(
          symbol,
          if (targetClass.isObject) {
            irGetObject(targetClass.symbol)
          } else {
            irCallConstructorWithSameParameters(thisFunction, targetConstructor)
          },
        )
      }
  }
}

internal fun IrBuilderWithScope.checkNotNullCall(
  context: LatticeTransformerContext,
  parent: IrDeclarationParent,
  firstArg: IrExpression,
  message: String,
): IrExpression =
  irInvoke(
      callee = context.symbols.stdlibCheckNotNull,
      args =
        listOf(
          firstArg,
          irLambda(
            context.pluginContext,
            parent = parent, // TODO this is obvi wrong
            receiverParameter = null,
            valueParameters = emptyList(),
            returnType = context.pluginContext.irBuiltIns.stringType,
            suspend = false,
          ) {
            +irReturn(irString(message))
          },
        ),
    )
    .apply { putTypeArgument(0, firstArg.type) }

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.getAllSuperTypes(
  pluginContext: IrPluginContext,
  excludeSelf: Boolean = true,
  excludeAny: Boolean = true,
): Sequence<IrType> {
  val self = this
  // TODO are there ever cases where superTypes includes the current class?
  suspend fun SequenceScope<IrType>.allSuperInterfacesImpl(currentClass: IrClass) {
    for (superType in currentClass.superTypes) {
      if (excludeAny && superType == pluginContext.irBuiltIns.anyType) continue
      val clazz = superType.classifierOrFail.owner as IrClass
      if (excludeSelf && clazz == self) continue
      yield(superType)
      allSuperInterfacesImpl(clazz)
    }
  }

  return sequence {
    if (!excludeSelf) {
      yield(self.typeWith())
    }
    allSuperInterfacesImpl(self)
  }
}

internal fun IrExpression.doubleCheck(
  irBuilder: IrBuilderWithScope,
  symbols: LatticeSymbols,
): IrExpression =
  with(irBuilder) {
    irInvoke(
      dispatchReceiver = irGetObject(symbols.doubleCheckCompanionObject),
      callee = symbols.doubleCheckProvider,
      typeHint = null,
      args = listOf(this@doubleCheck),
    )
  }

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.allFunctions(pluginContext: IrPluginContext): Sequence<IrSimpleFunction> {
  return sequence {
    yieldAll(functions)
    yieldAll(
      getAllSuperTypes(pluginContext).mapNotNull(IrType::rawTypeOrNull).flatMap {
        it.allFunctions(pluginContext)
      }
    )
  }
}

internal fun IrClass.singleAbstractFunction(context: LatticeTransformerContext): IrSimpleFunction {
  return abstractFunctions(context).single()
}

internal fun IrSimpleFunction.isAbstractAndVisible(): Boolean {
  return modality == Modality.ABSTRACT &&
    body == null &&
    (visibility == DescriptorVisibilities.PUBLIC || visibility == DescriptorVisibilities.PROTECTED)
}

internal fun IrClass.abstractFunctions(context: LatticeTransformerContext): List<IrSimpleFunction> {
  return allFunctions(context.pluginContext)
    // Don't exclude fake overrides as we want the final materialized one
    // Merge inherited functions with matching signatures
    .groupBy {
      // Don't include the return type because overrides may have different ones
      it.computeJvmDescriptorIsh(context, includeReturnType = false)
    }
    .mapValues { (_, functions) ->
      val (abstract, implemented) = functions.partition { it.isAbstractAndVisible() }
      if (abstract.isEmpty()) {
        // All implemented, nothing to do
        null
      } else if (implemented.isNotEmpty()) {
        // If there's one implemented one, it's not abstract anymore in our materialized type
        null
      } else {
        // Only need one for the rest of this
        abstract[0]
      }
    }
    .values
    .filterNotNull()
}

internal fun IrClass.implements(pluginContext: IrPluginContext, superType: ClassId): Boolean {
  return implementsAny(pluginContext, setOf(superType))
}

internal fun IrClass.implementsAny(
  pluginContext: IrPluginContext,
  superTypes: Set<ClassId>,
): Boolean {
  return getAllSuperTypes(pluginContext, excludeSelf = false).any {
    it.rawTypeOrNull()?.classId in superTypes
  }
}

internal fun IrConstructorCall.getSingleConstStringArgumentOrNull(): String? =
  (getValueArgument(0) as IrConst?)?.value as String?

internal fun IrConstructorCall.getSingleConstBooleanArgumentOrNull(): Boolean? =
  (getValueArgument(0) as IrConst?)?.value as Boolean?

internal fun IrBuilderWithScope.irFloat(value: Float) =
  value.toIrConst(this.context.irBuiltIns.floatType)

internal fun IrBuilderWithScope.irDouble(value: Double) =
  value.toIrConst(this.context.irBuiltIns.doubleType)

internal fun IrBuilderWithScope.kClassReference(classType: IrType) =
  IrClassReferenceImpl(
    startOffset,
    endOffset,
    context.irBuiltIns.kClassClass.starProjectedType,
    context.irBuiltIns.kClassClass,
    classType,
  )

internal fun Collection<IrElement?>.joinToKotlinLike(separator: String = "\n"): String {
  return joinToString(separator = separator) { it?.dumpKotlinLike() ?: "<null element>" }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.getSuperClassNotAny(): IrClass? {
  val parentClass =
    superTypes
      .mapNotNull { it.classOrNull?.owner }
      .singleOrNull { it.kind == ClassKind.CLASS || it.kind == ClassKind.ENUM_CLASS } ?: return null
  return if (parentClass.defaultType.isAny()) null else parentClass
}

internal val IrDeclarationParent.isExternalParent: Boolean
  get() = this is Fir2IrLazyClass || this is IrExternalPackageFragment

internal fun IrFunction.isBindsAnnotated(symbols: LatticeSymbols): Boolean {
  return isAnnotatedWithAny(symbols.latticeClassIds.bindsAnnotations)
}

/**
 * An [irBlockBody] with a single [expression]. This is useful because [irExprBody] is not
 * serializable in IR and cannot be used in some places like function bodies. This replicates that
 * ease of use.
 */
internal fun IrBuilderWithScope.irBlockBody(symbol: IrSymbol, expression: IrExpression) =
  context.createIrBuilder(symbol).irBlockBody { +irReturn(expression) }

internal fun IrFunction.buildBlockBody(
  context: IrPluginContext,
  blockBody: IrBlockBodyBuilder.() -> Unit,
) {
  body = context.createIrBuilder(symbol).irBlockBody(body = blockBody)
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal val IrType.simpleName: String
  get() =
    when (val classifier = classifierOrNull) {
      is IrClassSymbol -> {
        classifier.owner.name.asString()
      }
      is IrScriptSymbol -> error("No simple name for script symbol: ${dumpKotlinLike()}")
      is IrTypeParameterSymbol -> {
        classifier.owner.name.asString()
      }
      null -> error("No classifier for ${dumpKotlinLike()}")
    }

internal val IrProperty.allAnnotations: List<IrConstructorCall>
  get() {
    return buildList {
        addAll(annotations)
        getter?.let { addAll(it.annotations) }
        setter?.let { addAll(it.annotations) }
        backingField?.let { addAll(it.annotations) }
      }
      .distinct()
  }

internal fun LatticeTransformerContext.latticeAnnotationsOf(ir: IrAnnotationContainer) =
  ir.latticeAnnotations(symbols.latticeClassIds)
