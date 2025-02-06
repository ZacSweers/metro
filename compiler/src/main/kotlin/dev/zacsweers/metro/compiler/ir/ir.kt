// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInLazy
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.metroAnnotations
import java.util.Objects
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.addExtensionReceiver
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocationWithRange
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.lazy.Fir2IrLazyClass
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.utils.valueArguments
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.IrGeneratorContext
import org.jetbrains.kotlin.ir.builders.IrStatementsBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
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
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrScriptSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.createType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isFakeOverriddenFromAny
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.ClassId
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

internal fun IrAnnotationContainer.annotationsIn(names: Set<ClassId>): Sequence<IrConstructorCall> {
  return annotations.asSequence().filter { it.symbol.owner.parentAsClass.classId in names }
}

internal fun <T> IrConstructorCall.constArgumentOfTypeAt(position: Int): T? {
  if (valueArgumentsCount == 0) return null
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

internal fun IrGeneratorContext.createIrBuilder(symbol: IrSymbol): DeclarationIrBuilder {
  return DeclarationIrBuilder(this, symbol, symbol.owner.startOffset, symbol.owner.endOffset)
}

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

/**
 * Computes a hash key for this annotation instance composed of its underlying type and value
 * arguments.
 */
internal fun IrConstructorCall.computeAnnotationHash(): Int {
  return Objects.hash(
    type.rawType().classIdOrFail,
    valueArguments
      .map {
        it?.computeHashSource()
          ?: error("Unknown annotation argument type: ${it?.let { it::class.java }}")
      }
      .toTypedArray()
      .contentDeepHashCode(),
  )
}

private fun IrExpression.computeHashSource(): Any? {
  return when (this) {
    is IrConst -> value
    is IrClassReference -> classType.classOrNull?.owner?.classId
    is IrGetEnumValue -> symbol.owner.fqNameWhenAvailable
    is IrConstructorCall -> computeAnnotationHash()
    is IrVararg -> {
      elements.map {
        when (it) {
          is IrExpression -> it.computeHashSource()
          else -> it
        }
      }
    }
    else -> null
  }
}

// TODO create an instance of this that caches lookups?
internal fun IrClass.declaredCallableMembers(
  context: IrMetroContext,
  functionFilter: (IrSimpleFunction) -> Boolean = { true },
  propertyFilter: (IrProperty) -> Boolean = { true },
): Sequence<MetroSimpleFunction> =
  allCallableMembers(
    context,
    excludeAnyFunctions = true,
    excludeInheritedMembers = true,
    excludeCompanionObjectMembers = true,
    functionFilter = functionFilter,
    propertyFilter = propertyFilter,
  )

// TODO create an instance of this that caches lookups?
internal fun IrClass.allCallableMembers(
  context: IrMetroContext,
  excludeAnyFunctions: Boolean = true,
  excludeInheritedMembers: Boolean = false,
  excludeCompanionObjectMembers: Boolean = false,
  functionFilter: (IrSimpleFunction) -> Boolean = { true },
  propertyFilter: (IrProperty) -> Boolean = { true },
): Sequence<MetroSimpleFunction> {
  return functions
    .letIf(excludeAnyFunctions) { it.filterNot { function -> function.isFakeOverriddenFromAny() } }
    .filter(functionFilter)
    .plus(properties.filter(propertyFilter).mapNotNull { property -> property.getter })
    .letIf(excludeInheritedMembers) { it.filterNot { function -> function.isFakeOverride } }
    .let { parentClassCallables ->
      val asFunctions = parentClassCallables.map { context.metroFunctionOf(it) }
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

internal val IrClass.isCompanionObject: Boolean
  get() = isObject && isCompanion

internal fun IrBuilderWithScope.irCallConstructorWithSameParameters(
  source: IrSimpleFunction,
  constructor: IrConstructorSymbol,
): IrConstructorCall {
  return irCall(constructor)
    .apply {
      for (parameter in source.valueParameters) {
        putValueArgument(parameter.index, irGet(parameter))
      }
    }
    .apply {
      for (typeParameter in source.typeParameters) {
        putTypeArgument(typeParameter.index, typeParameter.defaultType)
      }
    }
}

/** For use with generated factory creator functions, converts parameters to Provider<T> types. */
internal fun IrBuilderWithScope.parametersAsProviderArguments(
  context: IrMetroContext,
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
  context: IrMetroContext,
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
  context: IrMetroContext,
  contextKey: ContextualTypeKey,
  bindingCode: IrExpression,
  isAssisted: Boolean,
  isGraphInstance: Boolean,
): IrExpression {
  val symbols = context.symbols
  if (!bindingCode.type.isMetroProviderType(context)) {
    // Not a provider, nothing else to do here!
    return bindingCode
  }
  return when {
    contextKey.isLazyWrappedInProvider -> {
      // ProviderOfLazy.create(provider)
      irInvoke(
        dispatchReceiver = irGetObject(symbols.providerOfLazyCompanionObject),
        callee = symbols.providerOfLazyCreate,
        args = listOf(bindingCode),
        typeHint = contextKey.typeKey.type.wrapInLazy(symbols).wrapInProvider(symbols.metroProvider),
      )
    }

    contextKey.isWrappedInProvider -> bindingCode
    // Normally Dagger changes Lazy<Type> parameters to a Provider<Type>
    // (usually the container is a joined type), therefore we use
    // `.lazy(..)` to convert the Provider to a Lazy. Assisted
    // parameters behave differently and the Lazy type is not changed
    // to a Provider and we can simply use the parameter name in the
    // argument list.
    contextKey.isWrappedInLazy && isAssisted -> bindingCode
    contextKey.isWrappedInLazy -> {
      // DoubleCheck.lazy(...)
      irInvoke(
        dispatchReceiver = irGetObject(symbols.doubleCheckCompanionObject),
        callee = symbols.doubleCheckLazy,
        args = listOf(bindingCode),
        typeHint = contextKey.typeKey.type.wrapInLazy(symbols),
      )
    }

    isAssisted || isGraphInstance -> {
      // provider
      bindingCode
    }

    else -> {
      // provider.invoke()
      irInvoke(
        dispatchReceiver = bindingCode,
        callee = symbols.providerInvoke,
        typeHint = contextKey.typeKey.type,
      )
    }
  }
}

// TODO eventually just return a Map<TypeKey, IrField>
internal fun IrMetroContext.assignConstructorParamsToFields(
  constructor: IrConstructor,
  clazz: IrClass,
): Map<IrValueParameter, IrField> {
  return buildMap {
    for (irParameter in constructor.valueParameters) {
      val irField =
        clazz.addField(irParameter.name, irParameter.type, DescriptorVisibilities.PRIVATE).apply {
          isFinal = true
          initializer = pluginContext.createIrBuilder(symbol).run { irExprBody(irGet(irParameter)) }
        }
      put(irParameter, irField)
    }
  }
}

internal fun IrMetroContext.assignConstructorParamsToFields(
  parameters: Parameters<out Parameter>,
  clazz: IrClass,
): Map<Parameter, IrField> {
  return buildMap {
    for (irParameter in parameters.valueParameters) {
      val irField =
        clazz
          .addField(
            irParameter.name,
            irParameter.contextualTypeKey.toIrType(this@assignConstructorParamsToFields),
            DescriptorVisibilities.PRIVATE,
          )
          .apply {
            isFinal = true
            initializer =
              pluginContext.createIrBuilder(symbol).run { irExprBody(irGet(irParameter.ir)) }
          }
      put(irParameter, irField)
    }
  }
}

internal fun IrBuilderWithScope.dispatchReceiverFor(function: IrFunction): IrExpression {
  val parent = function.parentAsClass
  return if (parent.isObject) {
    irGetObject(parent.symbol)
  } else {
    irGet(parent.thisReceiverOrFail)
  }
}

internal val IrClass.thisReceiverOrFail: IrValueParameter
  get() = this.thisReceiver ?: error("No thisReceiver for $classId")

internal fun IrBuilderWithScope.checkNotNullCall(
  context: IrMetroContext,
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
  symbols: Symbols,
  typeKey: TypeKey,
): IrExpression =
  with(irBuilder) {
    val providerType = typeKey.type.wrapInProvider(symbols.metroProvider)
    irInvoke(
        dispatchReceiver = irGetObject(symbols.doubleCheckCompanionObject),
        callee = symbols.doubleCheckProvider,
        typeHint = providerType,
        args = listOf(this@doubleCheck),
      )
      .apply {
        putTypeArgument(0, providerType)
        putTypeArgument(1, typeKey.type)
      }
  }

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

internal fun IrClass.singleAbstractFunction(context: IrMetroContext): IrSimpleFunction {
  return abstractFunctions(context).single()
}

internal fun IrSimpleFunction.isAbstractAndVisible(): Boolean {
  return modality == Modality.ABSTRACT &&
    body == null &&
    (visibility == DescriptorVisibilities.PUBLIC || visibility == DescriptorVisibilities.PROTECTED)
}

internal fun IrClass.abstractFunctions(context: IrMetroContext): List<IrSimpleFunction> {
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

internal fun IrConstructorCall.getSingleConstBooleanArgumentOrNull(): Boolean? =
  (getValueArgument(0) as IrConst?)?.value as Boolean?

internal fun Collection<IrElement?>.joinToKotlinLike(separator: String = "\n"): String {
  return joinToString(separator = separator) { it?.dumpKotlinLike() ?: "<null element>" }
}

internal val IrDeclarationParent.isExternalParent: Boolean
  get() = this is Fir2IrLazyClass || this is IrExternalPackageFragment

/**
 * An [irBlockBody] with a single [expression]. This is useful because [irExprBody] is not
 * serializable in IR and cannot be used in some places like function bodies. This replicates that
 * ease of use.
 */
internal fun IrBuilderWithScope.irExprBodySafe(symbol: IrSymbol, expression: IrExpression) =
  context.createIrBuilder(symbol).irBlockBody { +irReturn(expression) }

internal fun IrFunction.buildBlockBody(
  context: IrPluginContext,
  blockBody: IrBlockBodyBuilder.() -> Unit,
) {
  body = context.createIrBuilder(symbol).irBlockBody(body = blockBody)
}

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

internal fun IrMetroContext.metroAnnotationsOf(ir: IrAnnotationContainer) =
  ir.metroAnnotations(symbols.classIds)

internal fun IrClass.requireSimpleFunction(name: String) =
  getSimpleFunction(name)
    ?: error(
      "No function $name in class $classId. Available: ${functions.joinToString { it.name.asString() }}"
    )

internal fun IrClassSymbol.requireSimpleFunction(name: String) =
  getSimpleFunction(name)
    ?: error(
      "No function $name in class ${owner.classId}. Available: ${functions.joinToString { it.owner.name.asString() }}"
    )

internal fun IrClass.requireNestedClass(name: Name): IrClass {
  return nestedClasses.firstOrNull { it.name == name }
    ?: error("No nested class $name in $classId. Found ${nestedClasses.map { it.name }}")
}

internal fun <T : IrOverridableDeclaration<*>> T.resolveOverriddenTypeIfAny(): T {
  @Suppress("UNCHECKED_CAST")
  return overriddenSymbols.singleOrNull()?.owner as? T? ?: this
}

internal val IrClass.isMetroGenerated: Boolean
  get() {
    return name in Symbols.Names.metroNames
  }

internal fun IrOverridableDeclaration<*>.finalizeFakeOverride(
  dispatchReceiverParameter: IrValueParameter
) {
  check(isFakeOverride) { "Function $name is not a fake override!" }
  isFakeOverride = false
  modality = Modality.FINAL
  if (this is IrSimpleFunction) {
    this.dispatchReceiverParameter =
      dispatchReceiverParameter.copyTo(this, type = dispatchReceiverParameter.type)
  } else if (this is IrProperty) {
    this.getter?.finalizeFakeOverride(dispatchReceiverParameter)
    this.setter?.finalizeFakeOverride(dispatchReceiverParameter)
  }
}

// TODO is there a faster way to do this use case?
internal fun <S> IrOverridableDeclaration<S>.overriddenSymbolsSequence(): Sequence<S> where
S : IrSymbol {
  return overriddenSymbolsSequence(mutableSetOf())
}

private fun <S> IrOverridableDeclaration<S>.overriddenSymbolsSequence(
  visited: MutableSet<S>
): Sequence<S> where S : IrSymbol {
  return sequence {
    for (overridden in overriddenSymbols) {
      if (overridden in visited) continue
      yield(overridden)
      visited += overridden
      val owner = overridden.owner
      if (owner is IrOverridableDeclaration<*>) {
        @Suppress("UNCHECKED_CAST")
        yieldAll((owner as IrOverridableDeclaration<S>).overriddenSymbolsSequence())
      }
    }
  }
}

internal fun IrFunction.stubExpressionBody(context: IrMetroContext) =
  context.pluginContext.createIrBuilder(symbol).run {
    irExprBodySafe(
      symbol,
      irInvoke(
        callee = context.symbols.stdlibErrorFunction,
        args = listOf(irString("Never called")),
      ),
    )
  }
