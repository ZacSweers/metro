// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.Symbols.DaggerSymbols
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.computeMetroDefault
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.ifNotEmpty
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.wrapInLazy
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.isGraphImpl
import dev.zacsweers.metro.compiler.letIf
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.singleOrError
import dev.zacsweers.metro.compiler.toSafeIdentifier
import java.io.File
import java.util.Objects
import kotlin.io.path.name
import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.createExtensionReceiver
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.jvm.ir.isWithFlexibleNullability
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocationWithRange
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.isObject
import org.jetbrains.kotlin.fir.lazy.Fir2IrLazyClass
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.IrGeneratorContext
import org.jetbrains.kotlin.ir.builders.IrStatementsBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.DelicateIrParameterIndexSetter
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrExternalPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.moduleDescriptor
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrScriptSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrDynamicType
import org.jetbrains.kotlin.ir.types.IrErrorType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.createType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.impl.buildSimpleType
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.mergeNullability
import org.jetbrains.kotlin.ir.types.removeAnnotations
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.getValueArgument
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.hasShape
import org.jetbrains.kotlin.ir.util.isFromJava
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isStatic
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.ir.util.superClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.Variance

/** Finds the line and column of [this] within its file. */
internal fun IrDeclaration.location(): CompilerMessageSourceLocation {
  return locationOrNull() ?: reportCompilerBug("No location found for ${dumpKotlinLike()}!")
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

internal fun CompilerMessageSourceLocation.render(short: Boolean): String? {
  return buildString {
    // Just for use in testing
    val path = File(path).toPath()
    if (short) {
      append(path.name)
    } else {
      val fileUri = path.toUri()
      append(fileUri)
    }
    if (line > 0 && column > 0) {
      append(':')
      append(line)
      append(':')
      append(column)
    } else {
      // No line or column numbers makes this kind of useless so return null
      return null
    }
  }
}

/** Returns the raw [IrClass] of this [IrType] or throws. */
internal fun IrType.rawType(): IrClass {
  return rawTypeOrNull()
    ?: run {
      val message =
        when {
          this is IrErrorType -> "Error type encountered: ${dumpKotlinLike()}"
          classifierOrNull is IrTypeParameterSymbol ->
            "Unexpected type parameter encountered: ${dumpKotlinLike()}"
          else -> "Unrecognized type! ${dumpKotlinLike()} (${classifierOrNull?.javaClass})"
        }
      reportCompilerBug(message)
    }
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

internal fun IrAnnotationContainer.findAnnotations(classId: ClassId): Sequence<IrConstructorCall> {
  return annotations.asSequence().filter { it.symbol.owner.parentAsClass.classId == classId }
}

internal fun IrAnnotationContainer.annotationsAnnotatedWithAny(
  names: Set<ClassId>
): Sequence<IrConstructorCall> {
  return annotations.asSequence().filter { annotationCall ->
    annotationCall.isAnnotatedWithAny(names)
  }
}

internal fun IrConstructorCall.isAnnotatedWithAny(names: Set<ClassId>): Boolean {
  val annotationClass = this.symbol.owner.parentAsClass
  return annotationClass.annotationsIn(names).any()
}

internal fun <T> IrConstructorCall.constArgumentOfTypeAt(position: Int): T? {
  if (arguments.isEmpty()) return null
  return (arguments[position] as? IrConst?)?.valueAs()
}

internal fun <T> IrConst.valueAs(): T {
  @Suppress("UNCHECKED_CAST")
  return value as T
}

context(context: IrPluginContext)
internal fun irType(
  classId: ClassId,
  nullable: Boolean = false,
  arguments: List<IrTypeArgument> = emptyList(),
): IrType =
  context.referenceClass(classId)!!.createType(hasQuestionMark = nullable, arguments = arguments)

internal fun IrGeneratorContext.createIrBuilder(symbol: IrSymbol): DeclarationIrBuilder {
  return DeclarationIrBuilder(this, symbol, symbol.owner.startOffset, symbol.owner.endOffset)
}

internal fun IrBuilderWithScope.irInvoke(
  /**
   * If null, this will be secondarily analyzed to see if [callee] is static or a companion object
   */
  dispatchReceiver: IrExpression? = null,
  extensionReceiver: IrExpression? = null,
  callee: IrFunctionSymbol,
  typeHint: IrType? = null,
  typeArgs: List<IrType>? = null,
  contextArgs: List<IrExpression?>? = null,
  args: List<IrExpression?> = emptyList(),
): IrMemberAccessExpression<*> {
  assert(callee.isBound) { "Symbol $callee expected to be bound" }
  val finalReceiverExpression =
    when {
      dispatchReceiver != null -> dispatchReceiver
      callee.owner.isStatic -> null
      else -> {
        callee.owner.dispatchReceiverParameter?.type?.rawTypeOrNull()?.let {
          if (it.isObject) {
            irGetObject(it.symbol)
          } else {
            null
          }
        }
      }
    }

  val returnType = typeHint ?: callee.owner.returnType
  val call = irCall(callee, type = returnType)
  typeArgs?.let {
    for ((i, typeArg) in typeArgs.withIndex()) {
      if (i >= call.typeArguments.size) {
        reportCompilerBug(
          "Invalid type arg $typeArg at index $i for callee ${callee.owner.dumpKotlinLike()}"
        )
      }
      call.typeArguments[i] = typeArg
    }
  }

  var argSize = args.size
  if (finalReceiverExpression != null) argSize++
  if (!contextArgs.isNullOrEmpty()) argSize += contextArgs.size
  if (extensionReceiver != null) argSize++
  check(callee.owner.parameters.size == argSize) {
    "Expected ${callee.owner.parameters.size} arguments but got ${args.size} for function: ${callee.owner.kotlinFqName}"
  }

  var index = 0
  finalReceiverExpression?.let { call.arguments[index++] = it }
  contextArgs?.forEach { call.arguments[index++] = it }
  extensionReceiver?.let { call.arguments[index++] = it }
  args.forEach { call.arguments[index++] = it }
  return call
}

context(context: CompatContext)
internal fun IrStatementsBuilder<*>.irTemporary(
  value: IrExpression? = null,
  nameHint: String? = null,
  irType: IrType = value?.type!!, // either value or irType should be supplied at callsite
  isMutable: Boolean = false,
  origin: IrDeclarationOrigin = IrDeclarationOrigin.IR_TEMPORARY_VARIABLE,
): IrVariable =
  with(context) {
    val temporary =
      scope.createTemporaryVariableDeclarationCompat(
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
    arguments
      .filterNotNull()
      .mapIndexed { i, arg ->
        arg.computeHashSource()
          ?: reportCompilerBug(
            "Unknown annotation argument type: ${arg::class.java }. Annotation: ${dumpKotlinLike()}"
          )
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
context(context: IrMetroContext)
internal fun IrClass.declaredCallableMembers(
  functionFilter: (IrSimpleFunction) -> Boolean = { true },
  propertyFilter: (IrProperty) -> Boolean = { true },
): Sequence<MetroSimpleFunction> =
  allCallableMembers(
    excludeAnyFunctions = true,
    excludeInheritedMembers = true,
    excludeCompanionObjectMembers = true,
    functionFilter = functionFilter,
    propertyFilter = propertyFilter,
  )

// TODO create an instance of this that caches lookups?
context(context: IrMetroContext)
internal fun IrClass.allCallableMembers(
  excludeAnyFunctions: Boolean = true,
  excludeInheritedMembers: Boolean = false,
  excludeCompanionObjectMembers: Boolean = false,
  functionFilter: (IrSimpleFunction) -> Boolean = { true },
  propertyFilter: (IrProperty) -> Boolean = { true },
): Sequence<MetroSimpleFunction> {
  return functions
    .letIf(excludeAnyFunctions) {
      it.filterNot { function -> function.isInheritedFromAny(context.irBuiltIns) }
    }
    .filter(functionFilter)
    .plus(properties.filter(propertyFilter).mapNotNull { property -> property.getter })
    .letIf(excludeInheritedMembers) { it.filterNot { function -> function.isFakeOverride } }
    .let { parentClassCallables ->
      val asFunctions = parentClassCallables.map { metroFunctionOf(it) }
      if (excludeCompanionObjectMembers) {
        asFunctions
      } else {
        companionObject()?.let { companionObject ->
          asFunctions +
            companionObject.allCallableMembers(
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
context(context: IrPluginContext)
internal fun irLambda(
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
        receiverParameter?.let { setExtensionReceiver(createExtensionReceiver(it)) }
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
      for ((i, parameter) in source.nonDispatchParameters.withIndex()) {
        arguments[i] = irGet(parameter)
      }
    }
    .apply {
      for (typeParameter in source.typeParameters) {
        typeArguments[typeParameter.index] = typeParameter.defaultType
      }
    }
}

/** For use with generated factory creator functions, converts parameters to Provider<T> types. */
context(context: IrMetroContext)
internal fun IrBuilderWithScope.parametersAsProviderArguments(
  parameters: Parameters,
  receiver: IrValueParameter,
  parametersToFields: Map<Parameter, IrField>,
): List<IrExpression?> {
  return buildList {
    addAll(
      parameters.allParameters
        .filterNot { it.isAssisted }
        .map { parameter -> parameterAsProviderArgument(parameter, receiver, parametersToFields) }
    )
  }
}

/** For use with generated factory create() functions. */
context(context: IrMetroContext)
internal fun IrBuilderWithScope.parameterAsProviderArgument(
  parameter: Parameter,
  receiver: IrValueParameter,
  parametersToFields: Map<Parameter, IrField>,
): IrExpression {
  // When calling value getter on Provider<T>, make sure the dispatch
  // receiver is the Provider instance itself
  val providerInstance = irGetField(irGet(receiver), parametersToFields.getValue(parameter))
  val typeMetadata = parameter.contextualTypeKey
  return typeAsProviderArgument(
    typeMetadata,
    providerInstance,
    isAssisted = parameter.isAssisted,
    isGraphInstance = parameter.isGraphInstance,
  )
}

context(context: IrMetroContext)
internal fun IrBuilderWithScope.typeAsProviderArgument(
  contextKey: IrContextualTypeKey,
  bindingCode: IrExpression,
  isAssisted: Boolean,
  isGraphInstance: Boolean,
): IrExpression {
  val symbols = context.metroSymbols
  val irType = bindingCode.type
  if (!irType.implementsLazyType()) {
    val providerType = bindingCode.type.findProviderSupertype()
    if (providerType == null) {
      // Not a provider, nothing else to do here!
      return bindingCode
    }
  }

  // More readability
  val providerExpression = bindingCode

  val providerSymbols = symbols.providerSymbolsFor(contextKey)

  return when {
    contextKey.isLazyWrappedInProvider -> {
      // ProviderOfLazy.create(provider)
      irInvoke(
        dispatchReceiver = irGetObject(symbols.providerOfLazyCompanionObject),
        callee = symbols.providerOfLazyCreate,
        typeArgs = listOf(contextKey.typeKey.type),
        args = listOf(providerExpression),
        typeHint = contextKey.typeKey.type.wrapInLazy(symbols).wrapInProvider(symbols.metroProvider),
      )
    }

    contextKey.isWrappedInProvider -> {
      with(providerSymbols) { transformMetroProvider(providerExpression, contextKey) }
    }

    // Normally Dagger changes Lazy<Type> parameters to a Provider<Type>
    // (usually the container is a joined type), therefore we use
    // `.lazy(..)` to convert the Provider to a Lazy. Assisted
    // parameters behave differently and the Lazy type is not changed
    // to a Provider and we can simply use the parameter name in the
    // argument list.
    contextKey.isWrappedInLazy && isAssisted -> {
      with(providerSymbols) { transformMetroProvider(providerExpression, contextKey) }
    }

    contextKey.isWrappedInLazy -> {
      // DoubleCheck.lazy(...)
      with(providerSymbols) { invokeDoubleCheckLazy(contextKey, providerExpression) }
    }

    isAssisted || isGraphInstance -> {
      // provider
      with(providerSymbols) { transformMetroProvider(providerExpression, contextKey) }
    }

    else -> {
      // provider.invoke()
      val metroProviderExpression =
        with(providerSymbols) {
          transformToMetroProvider(providerExpression, contextKey.typeKey.type)
        }
      irInvoke(
        dispatchReceiver = metroProviderExpression,
        callee = symbols.providerInvoke,
        typeHint = contextKey.typeKey.type,
      )
    }
  }
}

// TODO eventually just return a Map<TypeKey, IrField>
context(context: IrMetroContext)
internal fun assignConstructorParamsToFields(
  constructor: IrConstructor,
  clazz: IrClass,
): Map<IrValueParameter, IrField> {
  return buildMap {
    for (irParameter in constructor.regularParameters) {
      val irField =
        clazz
          .addField(
            toSafeIdentifier(irParameter.name.asString()),
            irParameter.type,
            DescriptorVisibilities.PRIVATE,
          )
          .apply {
            isFinal = true
            initializer = context.createIrBuilder(symbol).run { irExprBody(irGet(irParameter)) }
          }
      put(irParameter, irField)
    }
  }
}

context(context: IrMetroContext)
internal fun assignConstructorParamsToFields(
  parameters: Parameters,
  clazz: IrClass,
): Map<Parameter, IrField> {
  return buildMap {
    for (irParameter in parameters.regularParameters) {
      val irField =
        clazz
          .addField(
            irParameter.name,
            irParameter.contextualTypeKey.toIrType(),
            DescriptorVisibilities.PRIVATE,
          )
          .apply {
            isFinal = true
            initializer =
              context.createIrBuilder(symbol).run {
                irExprBody(irGet(irParameter.asValueParameter))
              }
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
  get() = this.thisReceiver ?: reportCompilerBug("No thisReceiver for $classId")

context(pluginContext: IrPluginContext)
internal fun IrClass.getAllSuperTypes(
  excludeSelf: Boolean = true,
  excludeAny: Boolean = true,
): Sequence<IrType> {
  val self = this
  // Cover for cases where a subtype explicitly redeclares an inherited supertype
  val visitedClasses = mutableSetOf<ClassId>()

  suspend fun SequenceScope<IrType>.allSuperInterfacesImpl(currentClass: IrClass) {
    for (superType in currentClass.superTypes) {
      if (excludeAny && superType == pluginContext.irBuiltIns.anyType) continue
      val clazz = superType.classifierOrFail.owner as IrClass
      if (excludeSelf && clazz == self) continue
      if (visitedClasses.add(clazz.classIdOrFail)) {
        yield(superType)
        allSuperInterfacesImpl(clazz)
      }
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
  typeKey: IrTypeKey,
): IrExpression =
  with(irBuilder) {
    val providerType = typeKey.type.wrapInProvider(symbols.metroProvider)
    irInvoke(
      dispatchReceiver = irGetObject(symbols.doubleCheckCompanionObject),
      callee = symbols.doubleCheckProvider,
      typeHint = providerType,
      typeArgs = listOf(providerType, typeKey.type),
      args = listOf(this@doubleCheck),
    )
  }

context(context: IrMetroContext)
internal fun IrClass.singleAbstractFunction(): IrSimpleFunction {
  return abstractFunctions().toList().singleOrError {
    buildString {
      append("Required a single abstract function for ")
      append(kotlinFqName)
      if (isEmpty()) {
        appendLine(" but found none.")
      } else {
        appendLine(" but found multiple:")
        append(
          joinTo(this, "\n") { function ->
            "- " +
              function.kotlinFqName.asString() +
              "\n  - " +
              function.computeJvmDescriptorIsh(includeReturnType = false)
          }
        )
      }
    }
  }
}

internal fun IrSimpleFunction.isAbstractAndVisible(): Boolean {
  return modality == Modality.ABSTRACT &&
    body == null &&
    (visibility == DescriptorVisibilities.PUBLIC || visibility == DescriptorVisibilities.PROTECTED)
}

internal fun IrClass.abstractFunctions(): Sequence<IrSimpleFunction> {
  return functions.filter { it.isAbstractAndVisible() }
}

context(context: IrPluginContext)
internal fun IrClass.implements(superType: ClassId): Boolean {
  return implementsAny(setOf(superType))
}

context(context: IrPluginContext)
internal fun IrClass.implementsAny(superTypes: Set<ClassId>): Boolean {
  return getAllSuperTypes(excludeSelf = false).any { it.rawTypeOrNull()?.classId in superTypes }
}

/**
 * Returns the single const boolean argument of this constructor call or null if...
 * - The number of arguments is not 1
 * - The argument is not a const boolean
 */
internal fun IrConstructorCall.getSingleConstBooleanArgumentOrNull(): Boolean? {
  return constArgumentOfTypeAt<Boolean>(0)
}

internal fun IrConstructorCall.getConstBooleanArgumentOrNull(name: Name): Boolean? =
  (getValueArgument(name) as IrConst?)?.value as Boolean?

internal fun IrConstructorCall.replacesArgument() =
  getValueArgument(Symbols.Names.replaces)?.expectAsOrNull<IrVararg>()

internal fun IrConstructorCall.replacedClasses(): Set<IrClassReference> {
  return replacesArgument().toClassReferences()
}

internal fun IrConstructorCall.subcomponentsArgument() =
  getValueArgument(Symbols.Names.subcomponents)?.expectAsOrNull<IrVararg>()

internal fun IrConstructorCall.excludesArgument() =
  getValueArgument(Symbols.Names.excludes)?.expectAsOrNull<IrVararg>()

internal fun IrConstructorCall.additionalScopesArgument() =
  getValueArgument(Symbols.Names.additionalScopes)?.expectAsOrNull<IrVararg>()

internal fun IrConstructorCall.excludedClasses(): Set<IrClassReference> {
  return excludesArgument().toClassReferences()
}

internal fun Set<IrClassReference>.mapToClassIds(): Set<ClassId> {
  return mapToSet { it.classType.rawType().classIdOrFail }
}

internal fun IrConstructorCall.additionalScopes(): Set<IrClassReference> {
  return additionalScopesArgument().toClassReferences()
}

internal fun IrConstructorCall.includesArgument() =
  getValueArgument(Symbols.Names.includes)?.expectAsOrNull<IrVararg>()

internal fun IrConstructorCall.includedClasses(): Set<IrClassReference> {
  return includesArgument().toClassReferences()
}

internal fun IrConstructorCall.bindingContainersArgument() =
  getValueArgument(Symbols.Names.bindingContainers)?.expectAsOrNull<IrVararg>()

internal fun IrConstructorCall.modulesArgument() =
  getValueArgument(Symbols.Names.modules)?.expectAsOrNull<IrVararg>()

internal fun IrConstructorCall.bindingContainerClasses(
  includeModulesArg: Boolean
): Set<IrClassReference> {
  // Check both
  val argument =
    bindingContainersArgument() ?: if (includeModulesArg) modulesArgument() else return emptySet()
  return argument.toClassReferences()
}

internal fun IrVararg?.toClassReferences(): Set<IrClassReference> {
  return this?.elements?.expectAsOrNull<List<IrClassReference>>()?.toSet() ?: return emptySet()
}

internal fun IrConstructorCall.requireScope(): ClassId {
  return scopeOrNull() ?: reportCompilerBug("No scope found for ${dumpKotlinLike()}")
}

internal fun IrConstructorCall.scopeOrNull(): ClassId? {
  return scopeClassOrNull()?.classIdOrFail
}

internal fun IrConstructorCall.scopeClassOrNull(): IrClass? {
  return getValueArgument(Symbols.Names.scope)
    ?.expectAsOrNull<IrClassReference>()
    ?.classType
    ?.rawTypeOrNull()
}

context(context: IrMetroContext)
internal fun IrClass.originClassId(): ClassId? {
  return annotationsIn(context.metroSymbols.classIds.originAnnotations)
    .firstOrNull()
    ?.originOrNull()
}

internal fun IrConstructorCall.requireOrigin(): ClassId {
  return originOrNull() ?: reportCompilerBug("No origin found for ${dumpKotlinLike()}")
}

internal fun IrConstructorCall.originOrNull(): ClassId? {
  return originClassOrNull()?.classIdOrFail
}

internal fun IrConstructorCall.originClassOrNull(): IrClass? {
  return getValueArgument(StandardNames.DEFAULT_VALUE_PARAMETER)
    ?.expectAsOrNull<IrClassReference>()
    ?.classType
    ?.rawTypeOrNull()
}

internal fun IrBuilderWithScope.kClassReference(symbol: IrClassSymbol): IrClassReference {
  return IrClassReferenceImpl(
    startOffset,
    endOffset,
    // KClass<T>
    context.irBuiltIns.kClassClass.typeWith(symbol.defaultType),
    symbol,
    // T
    symbol.defaultType,
  )
}

internal fun Collection<IrElement?>.joinToKotlinLike(separator: String = "\n"): String {
  return joinToString(separator = separator) { it?.dumpKotlinLike() ?: "<null element>" }
}

internal fun Sequence<IrElement?>.joinToKotlinLike(separator: String = "\n"): String {
  return joinToString(separator = separator) { it?.dumpKotlinLike() ?: "<null element>" }
}

internal val IrDeclarationParent.isExternalParent: Boolean
  get() = this is Fir2IrLazyClass || this is IrExternalPackageFragment

/**
 * An [irBlockBody] with a single [expression]. This is useful because [irExprBody] is not
 * serializable in IR and cannot be used in some places like function bodies. This replicates that
 * ease of use.
 */
internal fun IrBuilderWithScope.irExprBodySafe(
  expression: IrExpression,
  symbol: IrSymbol = scope.scopeOwnerSymbol,
) = context.createIrBuilder(symbol).irBlockBody { +irReturn(expression) }

context(context: IrPluginContext)
internal fun IrFunction.buildBlockBody(blockBody: IrBlockBodyBuilder.() -> Unit) {
  body = context.createIrBuilder(symbol).irBlockBody(body = blockBody)
}

internal fun IrType.render(short: Boolean, includeAnnotations: Boolean = false): String {
  return buildString { renderTo(this, short, includeAnnotations) }
}

internal fun IrTypeArgument.render(short: Boolean, includeAnnotations: Boolean = false): String {
  return buildString {
    when (this@render) {
      is IrStarProjection -> append("*")
      is IrTypeProjection -> type.renderTo(this, short, includeAnnotations)
    }
  }
}

internal fun IrType.renderTo(
  appendable: Appendable,
  short: Boolean,
  includeAnnotations: Boolean = false,
) {
  val type = this
  if (includeAnnotations && type.annotations.isNotEmpty()) {
    type.annotations.joinTo(appendable, separator = " ", postfix = " ") {
      IrAnnotation(it).render(short)
    }
  }
  when (type) {
    is IrDynamicType -> appendable.append("dynamic")
    is IrErrorType -> {
      // TODO IrErrorType.symbol?
      appendable.append("<error>")
    }
    is IrSimpleType -> {
      val name =
        when (val classifier = type.classifier) {
          is IrClassSymbol ->
            if (short) {
              classifier.owner.name.asString()
            } else {
              classifier.owner.kotlinFqName.asString()
            }
          is IrScriptSymbol ->
            reportCompilerBug("No simple name for script symbol: ${type.dumpKotlinLike()}")
          is IrTypeParameterSymbol -> {
            classifier.owner.name.asString()
          }
        }
      appendable.append(name)
      if (type.arguments.isNotEmpty()) {
        var first = true
        appendable.append('<')
        for (typeArg in type.arguments) {
          if (first) {
            first = false
          } else {
            appendable.append(", ")
          }
          when (typeArg) {
            is IrStarProjection -> appendable.append("*")
            is IrTypeProjection -> {
              when (typeArg.variance) {
                Variance.INVARIANT -> {
                  // do nothing
                }
                Variance.IN_VARIANCE -> appendable.append("in ")
                Variance.OUT_VARIANCE -> appendable.append("out ")
              }
              typeArg.type.renderTo(appendable, short)
            }
          }
        }
        appendable.append('>')
      }
    }
  }
  if (type.isMarkedNullable()) {
    appendable.append("?")
  }
}

/**
 * Canonicalizes an [IrType].
 * - If it's seen as a flexible nullable type from java, assume not null here
 * - If it's a flexible mutable type from java, patch to immutable if [patchMutableCollections] is
 *   true
 * - Remove annotations
 */
internal fun IrType.canonicalize(
  patchMutableCollections: Boolean,
  context: IrMetroContext?,
): IrType {
  return type
    .letIf(type.isWithFlexibleNullability()) {
      // Java types may be "Flexible" nullable types, assume not null here
      type.makeNotNull()
    }
    .letIf(patchMutableCollections) {
      context?.let { metroContext ->
        context(metroContext) { (it as? IrSimpleType)?.patchMutableCollections() }
      } ?: it
    }
    .removeAnnotations()
    .let {
      if (it is IrSimpleType && it.arguments.isNotEmpty()) {
        // Canonicalize the args too
        it.classifier
          .typeWithArguments(
            it.arguments.map { arg ->
              when (arg) {
                is IrStarProjection -> arg
                is IrTypeProjection -> {
                  makeTypeProjection(
                    arg.typeOrFail.canonicalize(patchMutableCollections, context),
                    arg.variance,
                  )
                }
              }
            }
          )
          // Preserve nullability
          .mergeNullability(it)
      } else {
        it
      }
    }
}

/**
 * Sources from Java will have FlexibleMutability up to `MutableSet` or `MutableMap` types, so we
 * patch them here.
 */
context(context: IrMetroContext)
private fun IrSimpleType.patchMutableCollections(): IrSimpleType {
  return if (type.hasAnnotation(StandardClassIds.Annotations.FlexibleMutability)) {
    val classifier = type.classifierOrNull
    val fixedType =
      when (classifier) {
        context.irBuiltIns.mutableSetClass -> context.irBuiltIns.setClass
        context.irBuiltIns.mutableMapClass -> context.irBuiltIns.mapClass
        else ->
          reportCompilerBug(
            "Unexpected multibinds collection type: ${type.render(short = false, includeAnnotations = true)}"
          )
      }
    fixedType.typeWithArguments(type.requireSimpleType().arguments)
  } else {
    this
  }
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

context(context: IrMetroContext)
internal fun metroAnnotationsOf(
  ir: IrAnnotationContainer,
  kinds: Set<MetroAnnotations.Kind> = MetroAnnotations.ALL_KINDS,
) = ir.metroAnnotations(context.metroSymbols.classIds, kinds)

internal fun IrClass.requireSimpleFunction(name: String) =
  getSimpleFunction(name)
    ?: reportCompilerBug(
      "No function $name in class $classId. Available: ${functions.joinToString { it.name.asString() }}"
    )

internal fun IrClassSymbol.requireSimpleFunction(name: String) =
  getSimpleFunction(name)
    ?: reportCompilerBug(
      "No function $name in class ${owner.classId}. Available: ${functions.joinToString { it.owner.name.asString() }}"
    )

internal fun IrClass.requireNestedClass(name: Name): IrClass {
  return nestedClassOrNull(name)
    ?: reportCompilerBug(
      "No nested class $name in $classId. Found ${nestedClasses.map { it.name }}"
    )
}

internal fun IrClass.nestedClassOrNull(name: Name): IrClass? {
  return nestedClasses.firstOrNull { it.name == name }
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
  origin = IrDeclarationOrigin.DEFINED
  modality = Modality.FINAL
  if (this is IrSimpleFunction) {
    setDispatchReceiver(
      dispatchReceiverParameter.copyTo(this, type = dispatchReceiverParameter.type)
    )
  } else if (this is IrProperty) {
    this.getter?.finalizeFakeOverride(dispatchReceiverParameter)
    this.setter?.finalizeFakeOverride(dispatchReceiverParameter)
  }
}

// TODO is there a faster way to do this use case?
internal fun <S> IrOverridableDeclaration<S>.overriddenSymbolsSequence(): Sequence<S>
  where S : IrSymbol {
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

context(context: IrMetroContext)
internal fun IrFunction.stubExpressionBody(message: String = "Never called"): IrBlockBody {
  return context.createIrBuilder(symbol).run { irExprBodySafe(stubExpression(message)) }
}

context(context: IrMetroContext)
internal fun IrBuilderWithScope.stubExpression(
  message: String = "Never called"
): IrMemberAccessExpression<*> {
  return irInvoke(
    callee = context.metroSymbols.stdlibErrorFunction,
    args = listOf(irString(message)),
  )
}

context(context: IrPluginContext)
internal fun buildAnnotation(
  symbol: IrSymbol,
  callee: IrConstructorSymbol,
  body: IrBuilderWithScope.(IrConstructorCall) -> Unit = {},
): IrConstructorCall {
  return context.createIrBuilder(symbol).run {
    irCallConstructor(callee = callee, typeArguments = emptyList()).also { body(it) }
  }
}

internal val IrClass.metroGraphOrFail: IrClass
  get() = metroGraphOrNull ?: reportCompilerBug("No generated MetroGraph found: $classId")

internal val IrClass.metroGraphOrNull: IrClass?
  get() = if (origin.isGraphImpl) this else nestedClassOrNull(Symbols.Names.MetroGraph)

internal val IrClass.sourceGraphIfMetroGraph: IrClass
  get() {
    val isGeneratedGraph = name == Symbols.Names.MetroGraph || origin.isGraphImpl
    return if (isGeneratedGraph) {
      superTypes.firstOrNull()?.rawTypeOrNull()
        ?: reportCompilerBug("No super type found for $kotlinFqName")
    } else {
      this
    }
  }

// Adapted from compose-compiler
// https://github.com/JetBrains/kotlin/blob/d36a97bb4b935c719c44b76dc8de952579404f91/plugins/compose/compiler-hosted/src/main/java/androidx/compose/compiler/plugins/kotlin/lower/AbstractComposeLowering.kt#L1608
context(context: IrMetroContext)
internal fun hiddenDeprecated(
  message: String = "This synthesized declaration should not be used directly"
): IrConstructorCall {
  return IrConstructorCallImpl.fromSymbolOwner(
      type = context.metroSymbols.deprecated.defaultType,
      constructorSymbol = context.metroSymbols.deprecatedAnnotationConstructor,
    )
    .also {
      it.arguments[0] =
        IrConstImpl.string(
          SYNTHETIC_OFFSET,
          SYNTHETIC_OFFSET,
          context.irBuiltIns.stringType,
          message,
        )
      it.arguments[2] =
        IrGetEnumValueImpl(
          SYNTHETIC_OFFSET,
          SYNTHETIC_OFFSET,
          context.metroSymbols.deprecationLevel.defaultType,
          context.metroSymbols.hiddenDeprecationLevel,
        )
    }
}

internal val IrFunction.extensionReceiverParameterCompat: IrValueParameter?
  get() {
    return parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
  }

internal fun IrFunction.setExtensionReceiver(value: IrValueParameter?) {
  setReceiverParameter(IrParameterKind.ExtensionReceiver, value)
}

internal fun IrFunction.setDispatchReceiver(value: IrValueParameter?) {
  setReceiverParameter(IrParameterKind.DispatchReceiver, value)
}

@OptIn(DelicateIrParameterIndexSetter::class, DeprecatedForRemovalCompilerApi::class)
private fun IrFunction.setReceiverParameter(kind: IrParameterKind, value: IrValueParameter?) {
  val parameters = parameters.toMutableList()

  var index = parameters.indexOfFirst { it.kind == kind }
  var reindexSubsequent = false
  if (index >= 0) {
    val old = parameters[index]
    old.indexInOldValueParameters = -1
    old.indexInParameters = -1

    if (value != null) {
      parameters[index] = value
    } else {
      parameters.removeAt(index)
      reindexSubsequent = true
    }
  } else {
    if (value != null) {
      index = parameters.indexOfLast { it.kind < kind } + 1
      parameters.add(index, value)
      reindexSubsequent = true
    } else {
      // nothing
    }
  }

  if (value != null) {
    value.indexInOldValueParameters = -1
    value.indexInParameters = index
    value.kind = kind
  }

  if (reindexSubsequent) {
    for (i in index..<parameters.size) {
      parameters[i].indexInParameters = i
    }
  }
  this.parameters = parameters
}

internal val IrFunction.contextParameters: List<IrValueParameter>
  get() {
    return parameters.filter { it.kind == IrParameterKind.Context }
  }

internal val IrFunction.regularParameters: List<IrValueParameter>
  get() {
    return parameters.filter { it.kind == IrParameterKind.Regular }
  }

internal fun IrFunction.isInheritedFromAny(irBuiltIns: IrBuiltIns): Boolean {
  return isEqualsOnAny(irBuiltIns) || isHashCodeOnAny() || isToStringOnAny()
}

internal fun IrFunction.isEqualsOnAny(irBuiltIns: IrBuiltIns): Boolean {
  return name == StandardNames.EQUALS_NAME &&
    hasShape(
      dispatchReceiver = true,
      regularParameters = 1,
      parameterTypes = listOf(null, irBuiltIns.anyNType),
    )
}

internal fun IrFunction.isHashCodeOnAny(): Boolean {
  return name == StandardNames.HASHCODE_NAME &&
    hasShape(dispatchReceiver = true, regularParameters = 0)
}

internal fun IrFunction.isToStringOnAny(): Boolean {
  return name == StandardNames.TO_STRING_NAME &&
    hasShape(dispatchReceiver = true, regularParameters = 0)
}

internal val NOOP_TYPE_REMAPPER =
  object : TypeRemapper {
    override fun enterScope(irTypeParametersContainer: IrTypeParametersContainer) {}

    override fun leaveScope() {}

    override fun remapType(type: IrType): IrType {
      return type
    }
  }

internal fun IrTypeParametersContainer.buildSubstitutionMapFor(
  type: IrType
): Map<IrTypeParameterSymbol, IrType> {
  return if (type is IrSimpleType && type.arguments.isNotEmpty()) {
    buildMap {
      typeParameters.zip(type.arguments).forEach { (param, arg) ->
        when (arg) {
          is IrTypeProjection -> put(param.symbol, arg.type)
          else -> null
        }
      }
    }
  } else {
    emptyMap()
  }
}

context(context: IrMetroContext)
internal fun IrTypeParametersContainer.typeRemapperFor(type: IrType): TypeRemapper {
  return if (this is IrClass) {
    deepRemapperFor(type)
  } else {
    // TODO can we consolidate function logic?
    val substitutionMap = buildSubstitutionMapFor(type)
    typeRemapperFor(substitutionMap)
  }
}

internal fun typeRemapperFor(substitutionMap: Map<IrTypeParameterSymbol, IrType>): TypeRemapper {
  val remapper =
    object : TypeRemapper {
      override fun enterScope(irTypeParametersContainer: IrTypeParametersContainer) {}

      override fun leaveScope() {}

      override fun remapType(type: IrType): IrType {
        return when (type) {
          is IrSimpleType -> {
            val classifier = type.classifier
            if (classifier is IrTypeParameterSymbol) {
              substitutionMap[classifier]?.let { substitutedType ->
                // Preserve nullability
                when (val remapped = remapType(substitutedType)) {
                  // Java type args always come with @FlexibleNullability, which we choose to
                  // interpret as strictly not null
                  is IrSimpleType if (!type.isWithFlexibleNullability()) -> {
                    remapped.mergeNullability(type)
                  }
                  else -> remapped
                }
              } ?: type
            } else if (type.arguments.isEmpty()) {
              type
            } else {
              val newArguments =
                type.arguments.map { arg ->
                  when (arg) {
                    is IrTypeProjection -> makeTypeProjection(remapType(arg.type), arg.variance)
                    else -> arg
                  }
                }
              // TODO impl use
              type.buildSimpleType { arguments = newArguments }
            }
          }
          else -> type
        }
      }
    }

  return remapper
}

/**
 * Returns a (possibly new) [IrSimpleFunction] with any generic parameters and return type
 * substituted appropriately as they would be materialized in the [subtype].
 */
context(context: IrMetroContext)
internal fun IrSimpleFunction.asMemberOf(subtype: IrType): IrSimpleFunction {
  // Should be caught in FIR
  check(typeParameters.isEmpty()) { "Generic functions are not supported: ${dumpKotlinLike()}" }

  val containingClass =
    parent as? IrClass ?: throw IllegalArgumentException("Function must be declared in a class")

  // If the containingClass has no type parameters, nothing to substitute
  if (containingClass.typeParameters.isEmpty()) {
    return this
  }

  val remapper = containingClass.deepRemapperFor(subtype)

  // Apply transformation if needed
  return if (remapper === NOOP_TYPE_REMAPPER) {
    this
  } else {
    deepCopyWithSymbols(initialParent = parent).apply {
      this.parent = this@asMemberOf.parent
      remapTypes(remapper)
    }
  }
}

context(context: IrMetroContext)
internal fun IrClass.deepRemapperFor(subtype: IrType): TypeRemapper {
  // Check cache for existing substitutor
  val cacheKey = classIdOrFail to subtype
  return context.typeRemapperCache.getOrPut(cacheKey) {
    // Build deep substitution map
    val substitutionMap = buildDeepSubstitutionMap(this, subtype)
    if (substitutionMap.isEmpty()) {
      NOOP_TYPE_REMAPPER
    } else {
      DeepTypeSubstitutor(substitutionMap)
    }
  }
}

private fun buildDeepSubstitutionMap(
  targetClass: IrClass,
  concreteType: IrType,
): Map<IrTypeParameterSymbol, IrType> {
  val result = mutableMapOf<IrTypeParameterSymbol, IrType>()

  fun collectSubstitutions(currentClass: IrClass, currentType: IrType) {
    if (currentType !is IrSimpleType) return

    // Add substitutions for current class's type parameters
    currentClass.typeParameters.zip(currentType.arguments).forEach { (param, arg) ->
      if (arg is IrTypeProjection) {
        result[param.symbol] = arg.type
      }
    }

    // Walk up the hierarchy
    currentClass.superTypes.forEach { superType ->
      val superClass = superType.classOrNull?.owner ?: return@forEach

      // Apply current substitutions to the supertype
      val substitutedSuperType = superType.substitute(result)

      // Recursively collect from supertypes
      collectSubstitutions(superClass, substitutedSuperType)
    }
  }

  collectSubstitutions(targetClass, concreteType)
  return result
}

private class DeepTypeSubstitutor(private val substitutionMap: Map<IrTypeParameterSymbol, IrType>) :
  TypeRemapper {
  private val mapByName = substitutionMap.mapKeys { it.key.owner.name.identifier }
  private val cache = mutableMapOf<IrType, IrType>()

  override fun enterScope(irTypeParametersContainer: IrTypeParametersContainer) {}

  override fun leaveScope() {}

  override fun remapType(type: IrType): IrType {
    return cache.getOrPut(type) {
      when (type) {
        is IrSimpleType -> {
          val classifier = type.classifier
          if (classifier is IrTypeParameterSymbol) {
            substitutionMap[classifier]?.let { remapType(it) } ?: type
          } else {
            val newArgs =
              type.arguments.map { arg ->
                when (arg) {
                  is IrTypeProjection -> makeTypeProjection(remapType(arg.type), arg.variance)
                  else -> arg
                }
              }
            if (newArgs == type.arguments) type else type.buildSimpleType { arguments = newArgs }
          }
        }
        else -> type
      }
    }
  }
}

// Extension to substitute types in an IrType
private fun IrType.substitute(substitutions: Map<IrTypeParameterSymbol, IrType>): IrType {
  if (substitutions.isEmpty()) return this
  val remapper = DeepTypeSubstitutor(substitutions)
  return remapper.remapType(this)
}

internal fun IrConstructorCall.rankValue(): Long {
  // Although the parameter is defined as an Int, the value we receive here may end up being
  // an Int or a Long so we need to handle both
  return getValueArgument(Symbols.Names.rank)?.let { arg ->
    when (arg) {
      is IrConst -> {
        when (val value = arg.value) {
          is Long -> value
          is Int -> value.toLong()
          else -> Long.MIN_VALUE
        }
      }
      else -> Long.MIN_VALUE
    }
  } ?: Long.MIN_VALUE
}

context(context: IrMetroContext)
internal fun IrProperty?.qualifierAnnotation(): IrAnnotation? {
  if (this == null) return null
  return allAnnotations
    .annotationsAnnotatedWith(context.metroSymbols.qualifierAnnotations)
    .singleOrNull()
    ?.let(::IrAnnotation)
}

context(context: IrMetroContext)
internal fun IrAnnotationContainer?.qualifierAnnotation() =
  annotationsAnnotatedWith(context.metroSymbols.qualifierAnnotations)
    .singleOrNull()
    ?.let(::IrAnnotation)

context(context: IrMetroContext)
internal fun IrAnnotationContainer?.scopeAnnotations() =
  annotationsAnnotatedWith(context.metroSymbols.scopeAnnotations).mapToSet(::IrAnnotation)

/** Returns the `@MapKey` annotation itself, not any annotations annotated _with_ `@MapKey`. */
context(context: IrMetroContext)
internal fun IrAnnotationContainer.explicitMapKeyAnnotation() =
  annotationsIn(context.metroSymbols.mapKeyAnnotations).singleOrNull()?.let(::IrAnnotation)

context(context: IrMetroContext)
internal fun IrAnnotationContainer.mapKeyAnnotation() =
  annotationsAnnotatedWith(context.metroSymbols.mapKeyAnnotations)
    .singleOrNull()
    ?.let(::IrAnnotation)

private fun IrAnnotationContainer?.annotationsAnnotatedWith(
  annotationsToLookFor: Collection<ClassId>
): Set<IrConstructorCall> {
  if (this == null) return emptySet()
  return annotations.annotationsAnnotatedWith(annotationsToLookFor)
}

private fun List<IrConstructorCall>?.annotationsAnnotatedWith(
  annotationsToLookFor: Collection<ClassId>
): Set<IrConstructorCall> {
  if (this == null) return emptySet()
  return filterTo(LinkedHashSet()) {
    it.type.classOrNull?.owner?.isAnnotatedWithAny(annotationsToLookFor) == true
  }
}

context(context: IrMetroContext)
internal fun IrClass.findInjectableConstructor(onlyUsePrimaryConstructor: Boolean): IrConstructor? {
  if (kind.isObject) {
    // No constructor for this one but can be annotated with Contributes*
    return null
  }
  return findInjectableConstructor(
    onlyUsePrimaryConstructor,
    if (onlyUsePrimaryConstructor) {
      context.metroSymbols.classIds.allInjectAnnotations
    } else {
      context.metroSymbols.classIds.injectLikeAnnotations
    },
  )
}

internal fun IrClass.findInjectableConstructor(
  onlyUsePrimaryConstructor: Boolean,
  injectAnnotations: Set<ClassId>,
): IrConstructor? {
  return if (onlyUsePrimaryConstructor || isAnnotatedWithAny(injectAnnotations)) {
    primaryConstructor
  } else {
    constructors.singleOrNull { constructor -> constructor.isAnnotatedWithAny(injectAnnotations) }
  }
}

// InstanceFactory(...)
context(context: IrMetroContext)
internal fun IrBuilderWithScope.instanceFactory(type: IrType, arg: IrExpression): IrExpression {
  return irInvoke(
    irGetObject(context.metroSymbols.instanceFactoryCompanionObject),
    callee = context.metroSymbols.instanceFactoryInvoke,
    typeArgs = listOf(type),
    args = listOf(arg),
  )
}

context(context: IrMetroContext)
internal fun IrAnnotation.allowEmpty(): Boolean {
  ir.getSingleConstBooleanArgumentOrNull()?.let {
    // Explicit, return it
    return it
  }
  // Retain Dagger's behavior in interop if using their annotation
  val assumeAllowEmpty =
    context.options.enableDaggerRuntimeInterop &&
      ir.annotationClass.classId == DaggerSymbols.ClassIds.DAGGER_MULTIBINDS
  return assumeAllowEmpty
}

context(scope: IrBuilderWithScope)
internal fun Collection<IrClassReference>.copyToIrVararg() = ifNotEmpty {
  scope.irVararg(first().type, map { value -> value.deepCopyWithSymbols() })
}

context(scope: IrBuilderWithScope)
internal fun Collection<IrClass>.toIrVararg() = ifNotEmpty {
  scope.irVararg(first().defaultType, map { value -> scope.kClassReference(value.symbol) })
}

context(context: IrPluginContext)
internal fun IrClass.implicitBoundTypeOrNull(): IrType? {
  return superTypes
    .filterNot { it.rawType().classId == context.irBuiltIns.anyClass.owner.classId }
    .singleOrNull()
}

// Also check ignoreQualifier for interop after entering interop block to prevent unnecessary
// checks for non-interop
context(context: IrPluginContext)
internal fun IrConstructorCall.bindingTypeOrNull(): Pair<IrType?, Boolean> {
  return bindingTypeArgument()?.let { type ->
    // Return a binding defined using Metro's API
    type to false
  }
    ?:
    // Return a boundType defined using anvil KClass
    (anvilKClassBoundTypeArgument() to anvilIgnoreQualifier())
}

context(context: IrPluginContext)
internal fun IrConstructorCall.bindingTypeArgument(): IrType? {
  return getValueArgument(Symbols.Names.binding)?.expectAsOrNull<IrConstructorCall>()?.let {
    bindingType ->
    bindingType.typeArguments.getOrNull(0)?.takeUnless { it == context.irBuiltIns.nothingType }
  }
}

internal fun IrConstructorCall.anvilKClassBoundTypeArgument(): IrType? {
  return getValueArgument(Symbols.Names.boundType)?.expectAsOrNull<IrClassReference>()?.classType
}

internal fun IrConstructorCall.anvilIgnoreQualifier(): Boolean {
  return getConstBooleanArgumentOrNull(Symbols.Names.ignoreQualifier) ?: false
}

context(context: IrPluginContext)
internal fun IrConstructor.generateDefaultConstructorBody(
  body: IrBlockBodyBuilder.() -> Unit = {}
): IrBody? {
  val returnType = returnType as? IrSimpleType ?: return null
  val parentClass = parent as? IrClass ?: return null
  val superClassConstructor =
    parentClass.superClass?.primaryConstructor
      ?: context.irBuiltIns.anyClass.owner.primaryConstructor
      ?: return null

  return context.createIrBuilder(symbol).irBlockBody {
    // Call the super constructor
    +irDelegatingConstructorCall(superClassConstructor)
    // Initialize the instance
    +IrInstanceInitializerCallImpl(
      UNDEFINED_OFFSET,
      UNDEFINED_OFFSET,
      parentClass.symbol,
      returnType,
    )
    body()
  }
}

// Copied from CheckerUtils.kt
internal fun IrDeclarationWithVisibility.isVisibleAsInternal(file: IrFile): Boolean {
  val referencedDeclarationPackageFragment = getPackageFragment()
  val module = file.module
  return module.descriptor.shouldSeeInternalsOf(
    referencedDeclarationPackageFragment.moduleDescriptor
  )
}

context(context: IrMetroContext)
internal fun IrType.requireSimpleType(declaration: IrDeclaration? = null): IrSimpleType {
  return requireSimpleType(declaration, context)
}

internal fun IrType.requireSimpleType(
  declaration: IrDeclaration? = null,
  context: IrMetroContext? = null,
): IrSimpleType {
  if (this is IrSimpleType) return this

  if (this is IrErrorType) {
    val message =
      "Unexpected IR error type. Make sure you don't have any missing dependencies or imports."
    if (declaration != null && context != null) {
      context.reportCompat(declaration, MetroDiagnostics.METRO_ERROR, message)
      exitProcessing()
    } else {
      error(message)
    }
  } else {
    reportCompilerBug(
      "Expected $this to be an ${IrSimpleType::class.qualifiedName} but was ${this::class.qualifiedName}"
    )
  }
}

context(context: IrMetroContext)
internal fun IrValueParameter.hasMetroDefault(): Boolean {
  return computeMetroDefault(
    behavior = context.options.optionalBindingBehavior,
    isAnnotatedOptionalDep = {
      isAnnotatedWithAny(context.metroSymbols.classIds.optionalBindingAnnotations)
    },
    hasDefaultValue = { defaultValue != null },
  )
}

internal fun <T : Any> IrPluginContext.withIrBuilder(
  symbol: IrSymbol,
  block: DeclarationIrBuilder.() -> T,
): T {
  return createIrBuilder(symbol).run(block)
}

internal val IrProperty.reportableDeclaration: IrDeclarationParent?
  get() = backingField ?: getter

internal fun IrBuilderWithScope.irGetProperty(
  receiver: IrExpression,
  property: IrProperty,
): IrExpression {
  property.backingField?.let {
    return irGetField(receiver, it)
  }
  property.getter?.let {
    return irInvoke(dispatchReceiver = receiver, callee = it.symbol)
  }
  reportCompilerBug("No backing field or getter for property ${property.dumpKotlinLike()}")
}

internal val IrConstructorCall.annotationClass: IrClass
  get() = symbol.owner.parentAsClass

/**
 * Returns the container that can hold static-ish declarations.
 * - If Java -> this
 * - If Kotlin ->
 *     - If isObject -> this
 *     - Companion object -> it
 *     - else -> error
 */
internal fun IrClass.requireStaticIshDeclarationContainer(): IrClass {
  return staticIshDeclarationContainerOrNull()
    ?: reportCompilerBug(
      "No contain present that can hold static-ish declarations in ${classId?.asFqNameString()}!"
    )
}

/**
 * Returns the container that can hold static-ish declarations.
 * - If Java -> this
 * - If Kotlin ->
 *     - If isObject -> this
 *     - Companion object -> it
 *     - else null
 */
internal fun IrClass.staticIshDeclarationContainerOrNull(): IrClass? {
  return when {
    isFromJava() -> this
    kind.isObject -> this
    else -> companionObject()
  }
}

/**
 * In cases where we read Anvil-generated kotlin code, "static" may be a function in a companion
 * object, where [IrFunction.dispatchReceiverParameter] isn't quite enough.
 */
internal val IrFunction.isStaticIsh: Boolean
  get() = parent is IrClass && (dispatchReceiverParameter == null || parentAsClass.isObject)
