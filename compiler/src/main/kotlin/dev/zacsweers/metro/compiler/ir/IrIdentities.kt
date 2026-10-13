// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.graph.identity.AnnotationIdentity
import dev.zacsweers.metro.compiler.graph.identity.AnnotationReader
import dev.zacsweers.metro.compiler.graph.identity.AnnotationValueNode
import dev.zacsweers.metro.compiler.graph.identity.ConstantKind
import dev.zacsweers.metro.compiler.graph.identity.Nullability
import dev.zacsweers.metro.compiler.graph.identity.ProjectionVariance
import dev.zacsweers.metro.compiler.graph.identity.TypeArgumentNode
import dev.zacsweers.metro.compiler.graph.identity.TypeIdentity
import dev.zacsweers.metro.compiler.graph.identity.TypeNode
import dev.zacsweers.metro.compiler.graph.identity.TypeReader
import dev.zacsweers.metro.compiler.graph.identity.annotationIdentity
import dev.zacsweers.metro.compiler.graph.identity.typeIdentity
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrErrorExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.types.Variance

internal object IrTypeReader : TypeReader<IrType> {
  override fun read(type: IrType): TypeNode<IrType> {
    if (type !is IrSimpleType) {
      return TypeNode.Unresolved(type.render())
    }
    val isMarkedNullable = type.nullability == SimpleTypeNullability.MARKED_NULLABLE
    val nullability =
      if (isMarkedNullable) {
        Nullability.Nullable
      } else {
        Nullability.NotNull
      }
    return when (val classifier = type.classifier) {
      is IrClassSymbol -> {
        val arguments =
          type.arguments.map { argument ->
            when (argument) {
              is IrStarProjection -> TypeArgumentNode.Star
              is IrTypeProjection ->
                TypeArgumentNode.Projection(argument.variance.toProjectionVariance(), argument.type)
            }
          }
        TypeNode.Class(classifier.owner.identityId(), arguments, nullability)
      }
      is IrTypeParameterSymbol -> {
        val typeParameter = classifier.owner
        TypeNode.TypeParameter(
          ownerId = typeParameter.parent.identityId(),
          index = typeParameter.index,
          name = typeParameter.name.asString(),
          // Only type parameters distinguish a definitely-not-null use from a plain one.
          nullability =
            if (type.nullability == SimpleTypeNullability.DEFINITELY_NOT_NULL) {
              Nullability.DefinitelyNotNull
            } else {
              nullability
            },
        )
      }
      else -> TypeNode.Unresolved(type.render())
    }
  }
}

internal fun IrType.typeIdentity(): TypeIdentity = IrTypeReader.typeIdentity(this)

/** Local classes have no class ID, so they fall back to their FQ name. */
private fun IrDeclarationParent.identityId(): String =
  when (this) {
    is IrClass -> classId?.asString() ?: kotlinFqName.asString()
    else -> kotlinFqName.asString()
  }

private fun Variance.toProjectionVariance(): ProjectionVariance =
  when (this) {
    Variance.INVARIANT -> ProjectionVariance.Invariant
    Variance.IN_VARIANCE -> ProjectionVariance.In
    Variance.OUT_VARIANCE -> ProjectionVariance.Out
  }

private class IrAnnotationReader(override val isJvm: Boolean) :
  AnnotationReader<IrConstructorCall, IrElement, IrType> {
  override val typeReader = IrTypeReader

  override fun classId(annotation: IrConstructorCall): String =
    annotation.annotationClass.classIdOrFail.asString()

  override fun arguments(annotation: IrConstructorCall): List<IrElement?> {
    val parameters = annotation.symbol.owner.parameters
    return annotation.arguments.mapIndexed { index, argument ->
      // Available defaults give explicit and omitted arguments the same identity.
      argument ?: annotation.annotationDefaultValue(parameters[index])
    }
  }

  override fun value(value: IrElement): AnnotationValueNode<IrConstructorCall, IrElement, IrType> =
    when (value) {
      is IrConst ->
        AnnotationValueNode.Constant(
          ConstantKind.valueOf(value.kind.asString),
          value.value.toString(),
        )
      is IrConstructorCall -> AnnotationValueNode.Nested(value)
      is IrVararg -> AnnotationValueNode.Array(value.elements)
      is IrClassReference -> AnnotationValueNode.ClassLiteral(value.classType)
      is IrGetEnumValue ->
        AnnotationValueNode.EnumEntry(
          value.symbol.owner.parentAsClass.classIdOrFail.asString(),
          value.symbol.owner.name.asString(),
        )
      else ->
        reportCompilerBug(
          "Unrecognized annotation argument type: $value (type ${value::class.java})",
        )
    }

  companion object {
    val jvm = IrAnnotationReader(isJvm = true)
    val nonJvm = IrAnnotationReader(isJvm = false)
  }
}

internal fun IrConstructorCall.annotationIdentity(isJvm: Boolean): AnnotationIdentity {
  val reader =
    if (isJvm) {
      IrAnnotationReader.jvm
    } else {
      IrAnnotationReader.nonJvm
    }
  return reader.annotationIdentity(this)
}

/** Reads constructor or property defaults when the compiler makes them available. */
private fun IrConstructorCall.annotationDefaultValue(parameter: IrValueParameter): IrExpression? {
  val parameterDefault = parameter.defaultValue?.expression
  if (parameterDefault != null && parameterDefault !is IrErrorExpression) {
    return parameterDefault
  }

  val property = annotationClass.properties.firstOrNull { it.name == parameter.name }
  val initializer = property?.backingField?.initializer?.expression
  val propertyDefault =
    if (initializer is IrGetValue) {
      val sourceParameter = initializer.symbol.owner as? IrValueParameter
      sourceParameter?.defaultValue?.expression
    } else {
      initializer
    }
  if (propertyDefault != null && propertyDefault !is IrErrorExpression) {
    return propertyDefault
  }

  // Unavailable Kotlin 2.3 KLIB defaults keep their omitted-value identity.
  return null
}
