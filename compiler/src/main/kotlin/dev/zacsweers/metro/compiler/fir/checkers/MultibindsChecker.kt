// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.MetroFirAnnotation
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.diagnosticString
import dev.zacsweers.metro.compiler.fir.hasImplicitClassKey
import dev.zacsweers.metro.compiler.fir.isOrImplements
import dev.zacsweers.metro.compiler.fir.mapKeyClassValueExpression
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.resolvedClassId
import dev.zacsweers.metro.compiler.fir.toClassSymbolCompat
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.trace
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.isAnnotationClass
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.fullyExpandedClassId
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.getBooleanArgument
import org.jetbrains.kotlin.fir.declarations.utils.isEnumClass
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isArrayType
import org.jetbrains.kotlin.fir.types.isKClassType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.isPrimitive
import org.jetbrains.kotlin.fir.types.isString
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds

internal object MultibindsChecker : FirCallableDeclarationChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    if (declaration is FirPropertyAccessor) return // Handled by FirProperty checks
    // Skip value params we only really care about member callables here
    // tbh not sure why these come through here
    if (declaration is FirValueParameter) return
    val source = declaration.source ?: return
    val session = context.session
    // Single-pass annotation extraction. checkImpl reuses this so we don't walk the annotation
    // list more than once per call.
    val annotations = declaration.symbol.metroAnnotations()
    val isRelevant =
      annotations.isMultibinds ||
        annotations.isElementsIntoSet ||
        annotations.isIntoMap ||
        annotations.isIntoSet ||
        annotations.mapKey != null
    if (!isRelevant) return
    session.trace(name = { "MultibindsChecker(${declaration.symbol.callableId})" }) {
      checkImpl(declaration, source, annotations)
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun checkImpl(
    declaration: FirCallableDeclaration,
    source: KtSourceElement,
    annotations: MetroAnnotations<MetroFirAnnotation>,
  ) {
    val session = context.session

    val isMultibinds = annotations.isMultibinds
    val isElementsIntoSet = annotations.isElementsIntoSet
    val isIntoMap = annotations.isIntoMap
    val isIntoSet = annotations.isIntoSet

    // Must check this early
    if (!annotations.isIntoMap && annotations.mapKey != null) {
      reporter.reportOn(
        annotations.mapKey.fir.source ?: source,
        MetroDiagnostics.MULTIBINDS_ERROR,
        "`@MapKey` annotations are only allowed on `@IntoMap` declarations.",
      )
      return
    }

    if (!isMultibinds && !isElementsIntoSet && !isIntoMap && !isIntoSet) {
      return
    }

    // Exactly one
    if (!(isMultibinds xor isElementsIntoSet xor isIntoMap xor isIntoSet)) {
      reporter.reportOn(
        source,
        MetroDiagnostics.MULTIBINDS_ERROR,
        "Only one of `@Multibinds`, `@ElementsIntoSet`, `@IntoMap`, or `@IntoSet` is allowed.",
      )
      return
    }

    // Multibinds cannot be overrides
    if (declaration.isOverride) {
      reporter.reportOn(
        source,
        MetroDiagnostics.MULTIBINDS_OVERRIDE_ERROR,
        "Multibinding contributors cannot be overrides.",
      )
      return
    }

    if (annotations.isMultibinds) {
      // Cannot also be Provides/Binds
      if (annotations.isProvides || annotations.isBinds) {
        reporter.reportOn(
          source,
          MetroDiagnostics.MULTIBINDS_ERROR,
          "`@Multibinds` declarations cannot also be annotated with `@Provides` or `@Binds` annotations.",
        )
        return
      }

      // No need to check for explicit return types as that's enforced implicitly by the abstract
      // check above

      val returnType = declaration.returnTypeRef.coneTypeOrNull
      val returnTypeClassId = returnType?.classLikeLookupTagIfAny?.classId!!

      // @Multibinds must return only Map or Set
      if (returnTypeClassId != StandardClassIds.Map && returnTypeClassId != StandardClassIds.Set) {
        reporter.reportOn(
          declaration.returnTypeRef.source ?: source,
          MetroDiagnostics.MULTIBINDS_ERROR,
          "`@Multibinds` declarations can only return a `Map` or `Set`.",
        )
        return
      } else if (returnTypeClassId == StandardClassIds.Map) {
        when (val keyTypeArg = returnType.typeArguments[0]) {
          ConeStarProjection -> {
            reporter.reportOn(
              declaration.returnTypeRef.source ?: source,
              MetroDiagnostics.MULTIBINDS_ERROR,
              "Multibinding Map keys cannot be star projections. Use a concrete type instead.",
            )
            return
          }
          else -> {
            if (keyTypeArg.type?.isMarkedNullable == true) {
              reporter.reportOn(
                declaration.returnTypeRef.source ?: source,
                MetroDiagnostics.MULTIBINDS_ERROR,
                "Multibinding map keys cannot be nullable. Use a non-nullable type instead.",
              )
            } else {
              // Keys can only be const-able or annotation classes
              keyTypeArg.type?.let { keyType ->
                val isJavaClassType =
                  session.metroFirBuiltIns.options.enableKClassToClassInterop &&
                    keyType.classLikeLookupTagIfAny?.classId == Symbols.ClassIds.JavaLangClass
                if (
                  keyType.isPrimitive ||
                    keyType.isString ||
                    keyType.isKClassType() ||
                    isJavaClassType ||
                    keyType.toClassSymbolCompat(session)?.isEnumClass == true
                ) {
                  // ok
                } else if (keyType.isArrayType) {
                  // Arrays don't implement hashcode
                  reporter.reportOn(
                    declaration.returnTypeRef.source ?: source,
                    MetroDiagnostics.MULTIBINDS_ERROR,
                    "Multibinding map keys cannot be arrays.",
                  )
                } else {
                  keyType.toClassSymbolCompat(session)?.let { keyClass ->
                    if (keyClass.classKind.isAnnotationClass) {
                      // Ensure this annotation is annotated with MapKey
                      val mapKey =
                        keyClass
                          .annotationsIn(session, session.classIds.mapKeyAnnotations)
                          .firstOrNull()
                      if (mapKey == null) {
                        reporter.reportOn(
                          declaration.returnTypeRef.source ?: source,
                          MetroDiagnostics.MULTIBINDS_ERROR,
                          "Multibinding map key '${keyClass.classId.diagnosticString}' is not annotated with @MapKey(unwrapValue = false).",
                        )
                      } else if (
                        mapKey.getBooleanArgument(Symbols.Names.unwrapValue, session) != false
                      ) {
                        reporter.reportOn(
                          declaration.returnTypeRef.source ?: source,
                          MetroDiagnostics.MULTIBINDS_ERROR,
                          "Multibinding map key '${keyClass.classId.diagnosticString}' is annotated with @MapKey but does not set MapKey.unwrapValue to false. This is necessary to use wrapped values as multibinding map keys.",
                        )
                      }
                    } else {
                      reporter.reportOn(
                        declaration.returnTypeRef.source ?: source,
                        MetroDiagnostics.MULTIBINDS_ERROR,
                        "Multibinding map keys must be a primitive, String, KClass, enum, or an annotation class.",
                      )
                    }
                  }
                }
              }
            }
          }
        }

        val isStar =
          returnType.typeArguments[1].isStarOrProviderOfStar(session, checkProviderOfLazy = true)
        if (isStar) {
          reporter.reportOn(
            declaration.returnTypeRef.source ?: source,
            MetroDiagnostics.MULTIBINDS_ERROR,
            "Multibinding Map values cannot be star projections. Use a concrete type instead.",
          )
          return
        }
      } else if (returnTypeClassId == StandardClassIds.Set) {
        val isStar =
          returnType.typeArguments[0].isStarOrProviderOfStar(session, checkProviderOfLazy = false)
        if (isStar) {
          reporter.reportOn(
            declaration.returnTypeRef.source ?: source,
            MetroDiagnostics.MULTIBINDS_ERROR,
            "Multibinding Set elements cannot be star projections. Use a concrete type instead.",
          )
          return
        }
      }
      return
    }

    // Check implicit class key usage on @IntoMap declarations
    if (isIntoMap && annotations.mapKey != null) {
      // For @Binds, the implicit type is the input type (receiver or value param)
      val implicitType =
        if (annotations.isBinds) {
          val inputTypeRef =
            declaration.receiverParameter?.typeRef
              ?: (declaration as? FirFunction)?.valueParameters?.firstOrNull()?.returnTypeRef
          inputTypeRef?.coneTypeOrNull?.fullyExpandedClassId(session)
        } else {
          null
        }
      checkImplicitClassKeyUsage(session, annotations.mapKey, implicitType, source)
    }

    // @IntoSet, @IntoMap, and @ElementsIntoSet must also be provides/binds
    if (!(annotations.isProvides || annotations.isBinds)) {
      reporter.reportOn(
        source,
        MetroDiagnostics.MULTIBINDS_ERROR,
        "`@IntoSet`, `@IntoMap`, and `@ElementsIntoSet` must be used in conjunction with `@Provides` or `@Binds` annotations.",
      )
      return
    }

    // @ElementsIntoSet must be a Collection
    if (annotations.isElementsIntoSet) {
      // Provides checker will check separately for an explicit return type
      declaration.returnTypeRef.coneTypeOrNull?.let { returnConeType ->
        returnConeType.toClassSymbolCompat(session)?.let { returnType ->
          if (!returnType.isOrImplements(StandardClassIds.Collection, session)) {
            reporter.reportOn(
              declaration.returnTypeRef.source ?: source,
              MetroDiagnostics.MULTIBINDS_ERROR,
              "`@ElementsIntoSet` must return a Collection.",
            )
          } else if (returnConeType.typeArguments.size != 1) {
            reporter.reportOn(
              declaration.returnTypeRef.source ?: source,
              MetroDiagnostics.MULTIBINDS_ERROR,
              "`@ElementsIntoSet` must return a Collection type with exactly one generic type argument.",
            )
          } else if (returnConeType.typeArguments[0] is ConeStarProjection) {
            reporter.reportOn(
              declaration.returnTypeRef.source ?: source,
              MetroDiagnostics.MULTIBINDS_ERROR,
              "`@ElementsIntoSet` cannot return a star projection type.",
            )
          }
        }
      }
    }
  }

  private fun ConeTypeProjection.isStarOrProviderOfStar(
    session: FirSession,
    checkProviderOfLazy: Boolean,
  ): Boolean {
    if (this == ConeStarProjection) return true

    type?.let {
      // Check if it's a Provider<*> or Provider<Lazy<*>>
      val isProvider =
        it.typeArguments.isNotEmpty() &&
          it.classLikeLookupTagIfAny?.classId in session.metroFirBuiltIns.classIds.providerTypes
      if (isProvider) {
        val arg = it.typeArguments[0]
        if (arg == ConeStarProjection) {
          return true
        } else if (checkProviderOfLazy) {
          // Check if it's a Lazy<*>
          val argType = arg.type ?: return false
          val isLazyStar =
            argType.typeArguments.isNotEmpty() &&
              argType.classLikeLookupTagIfAny?.classId in
                session.metroFirBuiltIns.classIds.lazyTypes &&
              argType.typeArguments[0] == ConeStarProjection
          if (isLazyStar) {
            return true
          }
        }
      }
    }

    return false
  }
}

/**
 * Checks implicit class key usage on a map key annotation. If the map key has `implicitClassKey =
 * true`:
 * - Reports an error if the value is explicitly `Nothing::class` (reserved sentinel).
 * - Reports a warning if [implicitType] is provided and the value matches it (redundant).
 */
context(context: CheckerContext, reporter: DiagnosticReporter)
internal fun checkImplicitClassKeyUsage(
  session: FirSession,
  mapKey: MetroFirAnnotation,
  implicitType: ClassId?,
  source: KtSourceElement?,
) {
  if (!mapKey.hasImplicitClassKey(session)) return

  val valueArg = mapKey.mapKeyClassValueExpression() ?: return
  val valueClassId = valueArg.resolvedClassId() ?: return
  val argSource = valueArg.source ?: mapKey.fir.source ?: source

  if (valueClassId == StandardClassIds.Nothing) {
    reporter.reportOn(
      argSource,
      MetroDiagnostics.MAP_KEY_IMPLICIT_CLASS_KEY_ERROR,
      "`Nothing::class` is a reserved \"unset\" sentinel value for implicit class keys and cannot be used as an explicit map key value.",
    )
  } else if (implicitType != null && valueClassId == implicitType) {
    reporter.reportOn(
      argSource,
      MetroDiagnostics.MAP_KEY_REDUNDANT_IMPLICIT_CLASS_KEY,
      "Explicit class key value '${valueClassId.diagnosticString}::class' is the same as the implicit class key and can be omitted.",
    )
  }
}
