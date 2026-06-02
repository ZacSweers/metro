// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.proto.EnumEntryProto
import dev.zacsweers.metro.compiler.proto.InlinedProviderProto
import dev.zacsweers.metro.compiler.proto.InlinedValueProto
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal class IrInlinedProvider private constructor(private val value: Value) {

  fun toProto(): InlinedProviderProto = InlinedProviderProto(value_ = value.toProto())

  context(context: IrMetroContext, scope: IrBuilderWithScope)
  fun materialize(type: IrType): IrExpression = value.materialize(type)

  private sealed interface Value {
    fun toProto(): InlinedValueProto

    context(context: IrMetroContext, scope: IrBuilderWithScope)
    fun materialize(type: IrType): IrExpression
  }

  @JvmInline
  private value class IntValue(val value: Int) : Value {
    override fun toProto(): InlinedValueProto = InlinedValueProto(int_value = value)

    context(context: IrMetroContext, scope: IrBuilderWithScope)
    override fun materialize(type: IrType): IrExpression {
      return when (type.classOrNullClassId()) {
        "kotlin/Byte" ->
          IrConstImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, IrConstKind.Byte, value.toByte())
        "kotlin/Short" ->
          IrConstImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, IrConstKind.Short, value.toShort())
        else -> IrConstImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, IrConstKind.Int, value)
      }
    }
  }

  @JvmInline
  private value class LongValue(val value: Long) : Value {
    override fun toProto(): InlinedValueProto = InlinedValueProto(long_value = value)

    context(context: IrMetroContext, scope: IrBuilderWithScope)
    override fun materialize(type: IrType): IrExpression =
      IrConstImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, IrConstKind.Long, value)
  }

  @JvmInline
  private value class FloatValue(val value: Float) : Value {
    override fun toProto(): InlinedValueProto = InlinedValueProto(float_value = value)

    context(context: IrMetroContext, scope: IrBuilderWithScope)
    override fun materialize(type: IrType): IrExpression =
      IrConstImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, IrConstKind.Float, value)
  }

  @JvmInline
  private value class DoubleValue(val value: Double) : Value {
    override fun toProto(): InlinedValueProto = InlinedValueProto(double_value = value)

    context(context: IrMetroContext, scope: IrBuilderWithScope)
    override fun materialize(type: IrType): IrExpression =
      IrConstImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, IrConstKind.Double, value)
  }

  @JvmInline
  private value class BooleanValue(val value: Boolean) : Value {
    override fun toProto(): InlinedValueProto = InlinedValueProto(bool_value = value)

    context(context: IrMetroContext, scope: IrBuilderWithScope)
    override fun materialize(type: IrType): IrExpression =
      IrConstImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, IrConstKind.Boolean, value)
  }

  @JvmInline
  private value class StringValue(val value: String) : Value {
    override fun toProto(): InlinedValueProto = InlinedValueProto(string_value = value)

    context(context: IrMetroContext, scope: IrBuilderWithScope)
    override fun materialize(type: IrType): IrExpression =
      IrConstImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, IrConstKind.String, value)
  }

  @JvmInline
  private value class CharValue(val value: Char) : Value {
    override fun toProto(): InlinedValueProto = InlinedValueProto(char_value = value.code)

    context(context: IrMetroContext, scope: IrBuilderWithScope)
    override fun materialize(type: IrType): IrExpression =
      IrConstImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, IrConstKind.Char, value)
  }

  private data object NullValue : Value {
    override fun toProto(): InlinedValueProto = InlinedValueProto(is_null = true)

    context(context: IrMetroContext, scope: IrBuilderWithScope)
    override fun materialize(type: IrType): IrExpression =
      IrConstImpl.constNull(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type)
  }

  @JvmInline
  private value class ObjectValue(val classId: ClassId) : Value {
    override fun toProto(): InlinedValueProto =
      InlinedValueProto(object_class_id = classId.asString())

    context(context: IrMetroContext, scope: IrBuilderWithScope)
    override fun materialize(type: IrType): IrExpression {
      val symbol =
        context.pluginContext.referenceClass(classId)
          ?: reportCompilerBug("Could not resolve object class $classId")
      return scope.irGetObject(symbol)
    }
  }

  private data class EnumValue(val enumClassId: ClassId, val entryName: Name) : Value {
    override fun toProto(): InlinedValueProto =
      InlinedValueProto(
        enum_value =
          EnumEntryProto(enum_class_id = enumClassId.asString(), entry_name = entryName.asString())
      )

    context(context: IrMetroContext, scope: IrBuilderWithScope)
    override fun materialize(type: IrType): IrExpression {
      val enumClass =
        context.pluginContext.referenceClass(enumClassId)?.owner
          ?: reportCompilerBug("Could not resolve enum class $enumClassId")
      val enumEntry =
        enumClass.declarations.filterIsInstance<IrEnumEntry>().singleOrNull {
          it.name == entryName
        } ?: reportCompilerBug("Could not resolve enum entry $enumClassId.$entryName")
      return IrGetEnumValueImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, type, enumEntry.symbol)
    }
  }

  @JvmInline
  private value class ClassLiteralValue(val classId: ClassId) : Value {
    override fun toProto(): InlinedValueProto =
      InlinedValueProto(class_literal_class_id = classId.asString())

    context(context: IrMetroContext, scope: IrBuilderWithScope)
    override fun materialize(type: IrType): IrExpression {
      val symbol =
        context.pluginContext.referenceClass(classId)
          ?: reportCompilerBug("Could not resolve class literal $classId")
      return scope.kClassReference(symbol)
    }
  }

  companion object {
    fun fromProto(proto: InlinedProviderProto?): IrInlinedProvider? {
      val value = proto?.value_ ?: return null
      return IrInlinedProvider(value.toValue() ?: return null)
    }

    context(context: IrMetroContext)
    fun fromProviderFactory(
      annotations: MetroAnnotations<IrAnnotation>,
      parameters: Parameters,
      realDeclaration: IrDeclaration?,
      requiresDispatchReceiver: Boolean,
    ): IrInlinedProvider? {
      if (!isInlinableProvider(annotations, parameters, requiresDispatchReceiver)) return null
      val value = realDeclaration?.inlinedProviderValue() ?: return null
      if (annotations.isScoped) {
        context.reportCompat(
          realDeclaration,
          MetroDiagnostics.PROVIDES_ERROR,
          "Constant providers cannot be scoped; remove the scope annotation or change the body.",
        )
        return null
      }
      return IrInlinedProvider(value)
    }

    private fun isInlinableProvider(
      annotations: MetroAnnotations<IrAnnotation>,
      parameters: Parameters,
      requiresDispatchReceiver: Boolean,
    ): Boolean {
      return !annotations.isIntoMultibinding &&
        parameters.nonDispatchParameters.isEmpty() &&
        !requiresDispatchReceiver
    }

    private fun IrDeclaration.inlinedProviderValue(): Value? {
      return when (this) {
        is IrFunction ->
          when (val body = body) {
            is IrExpressionBody -> body.inlinedProviderValue()
            is IrBlockBody -> body.inlinedProviderValue()
            else -> null
          }
        is IrField -> initializer?.expression?.toInlinedProviderValue()
        else -> null
      }
    }

    private fun IrExpressionBody?.inlinedProviderValue(): Value? =
      this?.expression?.toInlinedProviderValue()

    private fun IrBlockBody?.inlinedProviderValue(): Value? {
      val returnExpression = this?.statements?.singleOrNull() as? IrReturn ?: return null
      return returnExpression.value.toInlinedProviderValue()
    }

    private fun IrExpression.toInlinedProviderValue(): Value? {
      return when (this) {
        is IrConst -> toInlinedProviderValue()
        is IrClassReference ->
          ClassLiteralValue(classType.classOrNull?.owner?.classIdOrFail ?: return null)
        is IrGetObjectValue -> ObjectValue(symbol.owner.classIdOrFail)
        is IrGetEnumValue -> EnumValue(symbol.owner.parentAsClass.classIdOrFail, symbol.owner.name)
        is IrGetField -> constPropertyInitializerValue()
        is IrCall -> constPropertyInitializerValue()
        else -> null
      }
    }

    private fun IrGetField.constPropertyInitializerValue(): Value? {
      val property = symbol.owner.correspondingPropertySymbol?.owner?.takeIf { it.isConst }
      return property?.backingField?.initializer?.expression?.toInlinedProviderValue()
    }

    private fun IrCall.constPropertyInitializerValue(): Value? {
      val property = symbol.owner.correspondingPropertySymbol?.owner?.takeIf { it.isConst }
      return property?.backingField?.initializer?.expression?.toInlinedProviderValue()
    }

    private fun IrConst.toInlinedProviderValue(): Value? {
      return when (kind) {
        IrConstKind.Byte -> IntValue((value as Byte).toInt())
        IrConstKind.Short -> IntValue((value as Short).toInt())
        IrConstKind.Int -> IntValue(value as Int)
        IrConstKind.Long -> LongValue(value as Long)
        IrConstKind.Float -> FloatValue(value as Float)
        IrConstKind.Double -> DoubleValue(value as Double)
        IrConstKind.Boolean -> BooleanValue(value as Boolean)
        IrConstKind.String -> StringValue(value as String)
        IrConstKind.Char -> CharValue(value as Char)
        IrConstKind.Null -> NullValue
      }
    }

    private fun InlinedValueProto.toValue(): Value? {
      int_value?.let(::IntValue)
      long_value?.let(::LongValue)
      float_value?.let(::FloatValue)
      double_value?.let(::DoubleValue)
      bool_value?.let(::BooleanValue)
      string_value?.let(::StringValue)
      char_value?.let {
        return CharValue(it.toChar())
      }
      if (is_null == true) return NullValue
      object_class_id?.let {
        return ObjectValue(ClassId.fromString(it))
      }
      enum_value?.let {
        return EnumValue(ClassId.fromString(it.enum_class_id), Name.identifier(it.entry_name))
      }
      class_literal_class_id?.let {
        return ClassLiteralValue(ClassId.fromString(it))
      }
      return null
    }

    private fun IrType.classOrNullClassId(): String? = classOrNull?.owner?.classIdOrFail?.asString()
  }
}
