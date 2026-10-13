// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

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
import org.jetbrains.kotlin.fir.extensions.FirSupertypeGenerationExtension.TypeResolveService
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeDefinitelyNotNullType
import org.jetbrains.kotlin.fir.types.ConeErrorType
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.ConeIntersectionType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ProjectionKind
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.types.ConstantValueKind

/**
 * Reads FIR types. It doesn't expand type aliases because FIR type equality doesn't either. That
 * keeps key ordering in line with key equality.
 */
internal object FirTypeReader : TypeReader<ConeKotlinType> {
  override fun read(type: ConeKotlinType): TypeNode<ConeKotlinType> =
    when (type) {
      is ConeErrorType -> TypeNode.Unresolved(type.renderReadableWithFqNames())
      is ConeClassLikeType -> {
        val arguments =
          type.typeArguments.map { argument ->
            when (argument) {
              is ConeKotlinTypeProjection ->
                TypeArgumentNode.Projection(argument.kind.toProjectionVariance(), argument.type)
              else -> TypeArgumentNode.Star
            }
          }
        TypeNode.Class(type.lookupTag.classId.asString(), arguments, type.nullability())
      }
      is ConeTypeParameterType -> typeParameterNode(type, type.nullability())
      is ConeDefinitelyNotNullType -> {
        val original = type.original
        if (original is ConeTypeParameterType) {
          typeParameterNode(original, Nullability.DefinitelyNotNull)
        } else {
          TypeNode.Unresolved(type.renderReadableWithFqNames())
        }
      }
      is ConeFlexibleType -> TypeNode.Flexible(type.lowerBound, type.upperBound)
      is ConeIntersectionType -> TypeNode.Intersection(type.intersectedTypes.toList())
      else -> TypeNode.Unresolved(type.renderReadableWithFqNames())
    }

  private fun typeParameterNode(
    type: ConeTypeParameterType,
    nullability: Nullability,
  ): TypeNode<ConeKotlinType> {
    val symbol = type.lookupTag.typeParameterSymbol
    val (ownerId, typeParameters) =
      when (val owner = symbol.containingDeclarationSymbol) {
        is FirClassLikeSymbol<*> -> owner.classId.asString() to owner.typeParameterSymbols
        is FirCallableSymbol<*> -> owner.callableId.toString() to owner.typeParameterSymbols
        else -> owner.toString() to emptyList()
      }
    return TypeNode.TypeParameter(
      ownerId = ownerId,
      index = typeParameters.indexOf(symbol),
      name = symbol.name.asString(),
      nullability = nullability,
    )
  }

  private fun ConeKotlinType.nullability(): Nullability =
    if (isMarkedNullable) {
      Nullability.Nullable
    } else {
      Nullability.NotNull
    }

  private fun ProjectionKind.toProjectionVariance(): ProjectionVariance =
    when (this) {
      ProjectionKind.IN -> ProjectionVariance.In
      ProjectionKind.OUT -> ProjectionVariance.Out
      else -> ProjectionVariance.Invariant
    }
}

internal fun ConeKotlinType.typeIdentity(): TypeIdentity = FirTypeReader.typeIdentity(this)

/**
 * Reads FIR annotations. This runs in the IDE, so arguments that don't resolve become unresolved
 * values instead of throwing.
 */
private class FirAnnotationReader(
  private val session: FirSession,
  private val typeResolver: TypeResolveService?,
) : AnnotationReader<FirAnnotation, FirExpression, ConeKotlinType> {
  override val typeReader = FirTypeReader
  override val isJvm: Boolean = session.moduleData.platform.isJvm()

  override fun classId(annotation: FirAnnotation): String =
    annotation.toAnnotationClassIdSafe(session)?.asString()
      ?: annotation.resolvedType.renderReadableWithFqNames()

  override fun arguments(annotation: FirAnnotation): List<FirExpression?> {
    val annotationClass = annotation.toAnnotationClassLikeSymbol(session) as? FirRegularClassSymbol
    val parameters =
      annotationClass?.primaryConstructorIfAny(session)?.valueParameterSymbols.orEmpty()
    if (parameters.isEmpty()) {
      // Without the constructor, keep the written arguments in their written order.
      return if (annotation is FirAnnotationCall) {
        annotation.arguments
      } else {
        annotation.argumentMapping.mapping.values.toList()
      }
    }
    return parameters.mapIndexed { index, parameter ->
      annotation.argumentAsOrNull<FirExpression>(session, parameter.name, index)
        ?: parameter.defaultValueOrNull()
    }
  }

  override fun value(
    value: FirExpression,
  ): AnnotationValueNode<FirAnnotation, FirExpression, ConeKotlinType> {
    when (value) {
      is FirNamedArgumentExpression -> return value(value.expression)
      is FirSpreadArgumentExpression -> return value(value.expression)
      is FirLiteralExpression -> return value.constantNode()
      is FirGetClassCall -> return value.classLiteralNode()
      is FirAnnotation -> return AnnotationValueNode.Nested(value)
      is FirVarargArgumentsExpression -> return AnnotationValueNode.Array(value.arguments)
    }

    // Resolve const references and constant expressions before falling back to their declarations.
    val evaluated =
      with(session.compatContext) { value.evaluateAsCompat(session, FirElement::class) }
    val wasEvaluated = evaluated !== value
    if (wasEvaluated && evaluated.isDirectValue()) {
      return value(evaluated as FirExpression)
    }

    if (value is FirPropertyAccessExpression) {
      val symbol = value.calleeReference.toResolvedCallableSymbol()
      val enumClassId = symbol?.callableId?.classId
      if (symbol is FirEnumEntrySymbol && enumClassId != null) {
        return AnnotationValueNode.EnumEntry(enumClassId.asString(), symbol.name.asString())
      }
    }
    // Array literals and arrayOf() calls. Constant expressions were evaluated above.
    val isArrayLiteral = value is FirCall && value !is FirFunctionCall
    if (isArrayLiteral || value.isArrayOfCall()) {
      return AnnotationValueNode.Array((value as FirCall).arguments)
    }
    return AnnotationValueNode.Unresolved(value.render())
  }

  private fun FirLiteralExpression.constantNode(): AnnotationValueNode<Nothing, Nothing, Nothing> {
    val constantKind =
      when (kind) {
        ConstantValueKind.Null -> ConstantKind.Null
        ConstantValueKind.Boolean -> ConstantKind.Boolean
        ConstantValueKind.Char -> ConstantKind.Char
        ConstantValueKind.Byte -> ConstantKind.Byte
        ConstantValueKind.Short -> ConstantKind.Short
        ConstantValueKind.Int -> ConstantKind.Int
        ConstantValueKind.Long -> ConstantKind.Long
        ConstantValueKind.Float -> ConstantKind.Float
        ConstantValueKind.Double -> ConstantKind.Double
        ConstantValueKind.String -> ConstantKind.String
        ConstantValueKind.UnsignedByte -> ConstantKind.UByte
        ConstantValueKind.UnsignedShort -> ConstantKind.UShort
        ConstantValueKind.UnsignedInt -> ConstantKind.UInt
        ConstantValueKind.UnsignedLong -> ConstantKind.ULong
        ConstantValueKind.IntegerLiteral -> ConstantKind.Int
        ConstantValueKind.UnsignedIntegerLiteral -> ConstantKind.UInt
        else -> return AnnotationValueNode.Unresolved(render())
      }
    return AnnotationValueNode.Constant(constantKind, value.toString())
  }

  private fun FirGetClassCall.classLiteralNode():
    AnnotationValueNode<Nothing, Nothing, ConeKotlinType> {
    val resolvedType = coneTypeIfResolved()
    if (resolvedType != null && resolvedType !is ConeErrorType) {
      return AnnotationValueNode.ClassLiteral(resolvedType)
    }
    val classId =
      resolveClassIdForAnnotationValue(session, typeResolver)
        ?: return AnnotationValueNode.Unresolved(render())
    return AnnotationValueNode.ClassLiteral(classId.constructClassLikeType())
  }

  private fun FirExpression.isArrayOfCall(): Boolean {
    if (this !is FirFunctionCall) return false
    val callableId = calleeReference.toResolvedCallableSymbol()?.callableId ?: return false
    val isKotlinPackage = callableId.packageName.asString() == "kotlin"
    val isArrayFactory = callableId.callableName.asString().endsWith("arrayOf", ignoreCase = true)
    return isKotlinPackage && isArrayFactory
  }

  /** Only resolved default values take part. Unavailable defaults keep their omitted identity. */
  private fun FirValueParameterSymbol.defaultValueOrNull(): FirExpression? {
    if (!hasDefaultValue) return null
    val defaultValue = resolvedDefaultValue ?: return null
    val isUsableDefault =
      when (defaultValue) {
        is FirLiteralExpression,
        is FirGetClassCall,
        is FirAnnotation,
        is FirPropertyAccessExpression,
        is FirCall -> true
        else -> false
      }
    return if (isUsableDefault) {
      defaultValue
    } else {
      null
    }
  }
}

private fun FirElement?.isDirectValue(): Boolean =
  when (this) {
    is FirLiteralExpression,
    is FirGetClassCall,
    is FirAnnotation,
    is FirVarargArgumentsExpression -> true
    else -> false
  }

internal fun FirAnnotation.annotationIdentity(
  session: FirSession,
  typeResolver: TypeResolveService?,
): AnnotationIdentity = FirAnnotationReader(session, typeResolver).annotationIdentity(this)
