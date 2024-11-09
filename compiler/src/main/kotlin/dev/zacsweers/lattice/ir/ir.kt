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
package dev.zacsweers.lattice.ir

import dev.zacsweers.lattice.LatticeOrigin
import dev.zacsweers.lattice.LatticeSymbols
import java.util.Objects
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.backend.jvm.ir.parentClassId
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocationWithRange
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.utils.valueArguments
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.IrGeneratorContext
import org.jetbrains.kotlin.ir.builders.IrStatementsBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrMutableAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.addMember
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.createType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyValueParametersFrom
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Finds the line and column of [this] within its file. */
internal fun IrDeclaration.location(): CompilerMessageSourceLocation {
  return locationIn(file)
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
  return rawTypeOrNull() ?: error("Unrecognized type! $this")
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
  @Suppress("UNCHECKED_CAST")
  return (getValueArgument(position) as? IrConst<*>?)?.valueAs()
}

internal fun <T> IrConst<*>.valueAs(): T {
  @Suppress("UNCHECKED_CAST")
  return value as T
}

internal fun IrPluginContext.irType(
  classId: ClassId,
  nullable: Boolean = false,
  arguments: List<IrTypeArgument> = emptyList(),
): IrType = referenceClass(classId)!!.createType(hasQuestionMark = nullable, arguments = arguments)

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrPluginContext.createIrBuilder(symbol: IrSymbol): DeclarationIrBuilder {
  return DeclarationIrBuilder(this, symbol, symbol.owner.startOffset, symbol.owner.endOffset)
}

internal fun IrConstructor.irConstructorBody(
  context: IrGeneratorContext,
  blockBody: DeclarationIrBuilder.(MutableList<IrStatement>) -> Unit = {},
): IrBlockBody {
  val startOffset = UNDEFINED_OFFSET
  val endOffset = UNDEFINED_OFFSET
  val constructorIrBuilder =
    DeclarationIrBuilder(
      generatorContext = context,
      symbol = IrSimpleFunctionSymbolImpl(),
      startOffset = startOffset,
      endOffset = endOffset,
    )
  val ctorBody =
    context.irFactory.createBlockBody(startOffset = startOffset, endOffset = endOffset).apply {
      constructorIrBuilder.blockBody(statements)
    }
  body = ctorBody
  return ctorBody
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrBuilderWithScope.irInvoke(
  dispatchReceiver: IrExpression? = null,
  callee: IrFunctionSymbol,
  typeHint: IrType? = null,
  vararg args: IrExpression,
): IrMemberAccessExpression<*> {
  assert(callee.isBound) { "Symbol $callee expected to be bound" }
  val returnType = typeHint ?: callee.owner.returnType
  val call = irCall(callee, type = returnType)
  call.dispatchReceiver = dispatchReceiver
  args.forEachIndexed(call::putValueArgument)
  return call
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.addOverride(
  baseFunction: IrSimpleFunction,
  modality: Modality = Modality.FINAL,
): IrSimpleFunction =
  addOverride(
      baseFunction.kotlinFqName,
      baseFunction.name.asString(),
      baseFunction.returnType,
      modality,
    )
    .apply {
      dispatchReceiverParameter = this@addOverride.thisReceiver?.copyTo(this)
      copyValueParametersFrom(baseFunction)
    }

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.addOverride(
  baseFqName: FqName,
  name: String,
  returnType: IrType,
  modality: Modality = Modality.FINAL,
): IrSimpleFunction =
  addFunction(name, returnType, modality).apply {
    overriddenSymbols =
      superTypes
        .mapNotNull { superType ->
          superType.classOrNull?.owner?.takeIf { superClass ->
            superClass.isSubclassOfFqName(baseFqName.asString())
          }
        }
        .flatMap { superClass ->
          superClass.functions
            .filter { function ->
              function.name.asString() == name && function.overridesFunctionIn(baseFqName)
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

internal fun IrClass.isSubclassOfFqName(fqName: String): Boolean =
  fqNameWhenAvailable?.asString() == fqName ||
    superTypes.any { it.erasedUpperBound.isSubclassOfFqName(fqName) }

internal fun IrSimpleFunction.overridesFunctionIn(fqName: FqName): Boolean =
  parentClassOrNull?.fqNameWhenAvailable == fqName ||
    allOverridden().any { it.parentClassOrNull?.fqNameWhenAvailable == fqName }

/**
 * Computes a hash key for this annotation instance composed of its underlying type and value
 * arguments.
 */
internal fun IrConstructorCall.computeAnnotationHash(): Int {
  return Objects.hash(
    type.rawType().classIdOrFail,
    valueArguments.map { (it as IrConst<*>).value }.toTypedArray().contentDeepHashCode(),
  )
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrClass.allCallableMembers(
  excludeAnyFunctions: Boolean = true
): Sequence<IrSimpleFunction> {
  return functions
    .asSequence()
    .let {
      if (excludeAnyFunctions) {
        // TODO optimize this?
        it.filterNot { function ->
          function.overriddenSymbols.any { symbol ->
            symbol.owner.parentClassId == LatticeSymbols.ClassIds.AnyClass
          }
        }
      } else {
        it
      }
    }
    .plus(properties.asSequence().mapNotNull { property -> property.getter })
}

// From
// https://kotlinlang.slack.com/archives/C7L3JB43G/p1672258639333069?thread_ts=1672258597.659509&cid=C7L3JB43G
internal fun irLambda(
  context: IrPluginContext,
  parent: IrDeclarationParent,
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
        valueParameters.forEachIndexed { index, type -> addValueParameter("arg$index", type) }
        body =
          DeclarationIrBuilder(context, this.symbol, SYNTHETIC_OFFSET, SYNTHETIC_OFFSET)
            .irBlockBody { content(this@apply) }
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
