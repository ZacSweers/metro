// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.fir.MetroFirAnnotation
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.compatContext
import dev.zacsweers.metro.compiler.fir.render as renderFirType
import dev.zacsweers.metro.compiler.fir.resolveClassIdForAnnotationValue
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.getAnnotationArgument
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.render as renderIrType
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassLikeSymbol
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.types.Variance

/** Frontend adapters for the same semantic identity; never use a frontend object's hashCode. */
internal fun ConeKotlinType.contributionProviderTypeIdentity(): String =
  generatedTypeNameIdentity(
    classifier = classId?.asString() ?: renderFirType(short = false, includeAbbreviation = false),
    nullable = isMarkedNullable,
    arguments =
      typeArguments.map { argument ->
        if (argument == ConeStarProjection) {
          "*"
        } else {
          generatedNameIdentity(
            argument.kind.toString().lowercase(),
            argument.type?.contributionProviderTypeIdentity(),
          )
        }
      },
  )

internal fun IrType.contributionProviderTypeIdentity(): String =
  generatedTypeNameIdentity(
    classifier = classOrNull?.owner?.classId?.asString() ?: renderIrType(short = false),
    nullable = isMarkedNullable(),
    arguments =
      (this as? IrSimpleType)?.arguments.orEmpty().map { argument ->
        when (argument) {
          is IrStarProjection -> "*"
          is IrTypeProjection ->
            generatedNameIdentity(
              when (argument.variance) {
                Variance.INVARIANT -> "invariant"
                Variance.IN_VARIANCE -> "in"
                Variance.OUT_VARIANCE -> "out"
              },
              argument.type.contributionProviderTypeIdentity(),
            )
        }
      },
  )

internal fun MetroFirAnnotation.contributionProviderAnnotationIdentity(
  session: FirSession,
  implicitClassId: ClassId? = null,
): String = fir.contributionProviderAnnotationIdentity(session, implicitClassId)

private fun FirAnnotation.contributionProviderAnnotationIdentity(
  session: FirSession,
  implicitClassId: ClassId? = null,
): String {
  val classId =
    toAnnotationClassIdSafe(session)
      ?: return generatedNameIdentity("unresolved-annotation", render())
  val annotationClass = toAnnotationClassLikeSymbol(session) as? FirRegularClassSymbol
  val parameters =
    annotationClass?.primaryConstructorIfAny(session)?.valueParameterSymbols.orEmpty()
  val arguments =
    if (implicitClassId != null) {
      listOf(
        (parameters.firstOrNull()?.name?.asString() ?: "value") to
          generatedNameIdentity("class", implicitClassId.asString())
      )
    } else {
      parameters.mapIndexedNotNull { index, parameter ->
        val argument =
          argumentAsOrNull<FirExpression>(session, parameter.name, index)
            ?: return@mapIndexedNotNull null
        parameter.name.asString() to argument.contributionProviderArgumentIdentity(session)
      }
    }
  return generatedAnnotationNameIdentity(classId, arguments)
}

private fun FirExpression.contributionProviderArgumentIdentity(session: FirSession): String {
  when (this) {
    is FirNamedArgumentExpression -> return expression.contributionProviderArgumentIdentity(session)
    is FirSpreadArgumentExpression ->
      return expression.contributionProviderArgumentIdentity(session)
    is FirLiteralExpression -> return generatedConstantNameIdentity(value)
    is FirGetClassCall ->
      return generatedNameIdentity(
        "class",
        resolveClassIdForAnnotationValue(session, typeResolver = null)?.asString(),
      )
    is FirAnnotationCall -> return contributionProviderAnnotationIdentity(session)
    is FirVarargArgumentsExpression ->
      return generatedNameIdentity(
        "array",
        *arguments.map { it.contributionProviderArgumentIdentity(session) }.toTypedArray(),
      )
  }

  // Resolve const references and constant expressions before falling back to their declarations.
  val evaluated = with(session.compatContext) { evaluateAsCompat(session, FirElement::class) }
  if (evaluated !== this) {
    when (evaluated) {
      is FirLiteralExpression,
      is FirGetClassCall,
      is FirAnnotationCall,
      is FirVarargArgumentsExpression ->
        return (evaluated as FirExpression).contributionProviderArgumentIdentity(session)
    }
  }
  if (this is FirPropertyAccessExpression) {
    return generatedNameIdentity(
      "enum",
      calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString(),
    )
  }
  if (this is FirFunctionCall) {
    val constructor = calleeReference.toResolvedCallableSymbol() as? FirConstructorSymbol
    val annotationClassId = constructor?.callableId?.classId
    if (constructor != null && annotationClassId != null) {
      val parameters = constructor.valueParameterSymbols
      val values = arguments.mapIndexed { index, argument ->
        val name =
          (argument as? FirNamedArgumentExpression)?.name ?: parameters.getOrNull(index)?.name
        (name?.asString() ?: index.toString()) to
          argument.contributionProviderArgumentIdentity(session)
      }
      return generatedAnnotationNameIdentity(annotationClassId, values)
    }
  }
  if (this is FirCall) {
    return generatedNameIdentity(
      "array",
      *arguments.map { it.contributionProviderArgumentIdentity(session) }.toTypedArray(),
    )
  }
  return generatedNameIdentity("unresolved-argument", render())
}

internal fun IrAnnotation.contributionProviderAnnotationIdentity(
  implicitClassId: ClassId? = null
): String = ir.contributionProviderAnnotationIdentity(implicitClassId)

private fun IrConstructorCall.contributionProviderAnnotationIdentity(
  implicitClassId: ClassId? = null
): String {
  val classId = symbol.owner.parentAsClass.classIdOrFail
  val parameters = symbol.owner.regularParameters
  val arguments =
    if (implicitClassId != null) {
      listOf(
        (parameters.firstOrNull()?.name?.asString() ?: "value") to
          generatedNameIdentity("class", implicitClassId.asString())
      )
    } else {
      parameters.mapNotNull { parameter ->
        val argument = getAnnotationArgument(parameter.name) ?: return@mapNotNull null
        parameter.name.asString() to argument.contributionProviderArgumentIdentity()
      }
    }
  return generatedAnnotationNameIdentity(classId, arguments)
}

private fun IrExpression.contributionProviderArgumentIdentity(): String =
  when (this) {
    is IrConst -> generatedConstantNameIdentity(value)
    is IrClassReference ->
      generatedNameIdentity("class", classType.classOrNull?.owner?.classId?.asString())
    is IrGetEnumValue -> generatedNameIdentity("enum", symbol.owner.fqNameWhenAvailable?.asString())
    is IrConstructorCall -> contributionProviderAnnotationIdentity()
    is IrVararg ->
      generatedNameIdentity(
        "array",
        *elements
          .map { element ->
            when (element) {
              is IrExpression -> element.contributionProviderArgumentIdentity()
              is IrSpreadElement -> element.expression.contributionProviderArgumentIdentity()
              else ->
                reportCompilerBug(
                  "Unsupported generated-name annotation array element: ${element::class.java.name}"
                )
            }
          }
          .toTypedArray(),
      )
    else ->
      reportCompilerBug("Unsupported generated-name annotation argument: ${this::class.java.name}")
  }
