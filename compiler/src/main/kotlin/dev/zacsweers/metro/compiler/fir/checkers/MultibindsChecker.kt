// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.isOrImplements
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.isAnnotationClass
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.getBooleanArgument
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.classLikeLookupTagIfAny
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isArrayType
import org.jetbrains.kotlin.fir.types.isKClassType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.isPrimitive
import org.jetbrains.kotlin.fir.types.isString
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.StandardClassIds

internal object MultibindsChecker : FirCallableDeclarationChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirCallableDeclaration) {
    if (declaration is FirPropertyAccessor) return // Handled by FirProperty checks
    val source = declaration.source ?: return
    val session = context.session

    val annotations = declaration.symbol.metroAnnotations(session)
    val isMultibinds = annotations.isMultibinds
    val isElementsIntoSet = annotations.isElementsIntoSet
    val isIntoMap = annotations.isIntoMap
    val isIntoSet = annotations.isIntoSet

    // Must check this early
    if (!annotations.isIntoMap && annotations.mapKeys.isNotEmpty()) {
      reporter.reportOn(
        annotations.mapKeys.first().fir.source ?: source,
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
                if (keyType.isPrimitive || keyType.isString || keyType.isKClassType()) {
                  // ok
                } else if (keyType.isArrayType) {
                  // Arrays don't implement hashcode
                  reporter.reportOn(
                    declaration.returnTypeRef.source ?: source,
                    MetroDiagnostics.MULTIBINDS_ERROR,
                    "Multibinding map keys cannot be arrays.",
                  )
                } else {
                  keyType.toClassSymbol(session)?.let { keyClass ->
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
                          "Multibinding map key '${keyClass.classId.asFqNameString()}' is not annotated with @MapKey(unwrapValue = false).",
                        )
                      } else if (
                        mapKey.getBooleanArgument(Symbols.Names.unwrapValue, session) != false
                      ) {
                        reporter.reportOn(
                          declaration.returnTypeRef.source ?: source,
                          MetroDiagnostics.MULTIBINDS_ERROR,
                          "Multibinding map key '${keyClass.classId.asFqNameString()}' is annotated with @MapKey but does not set MapKey.unwrapValue to false. This is necessary to use wrapped values as multibinding map keys.",
                        )
                      }
                    } else {
                      reporter.reportOn(
                        declaration.returnTypeRef.source ?: source,
                        MetroDiagnostics.MULTIBINDS_ERROR,
                        "Multibinding map keys must be a primitive, String, KClass, or an annotation class.",
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
      declaration.returnTypeRef.coneTypeOrNull?.toClassSymbol(session)?.let { returnType ->
        if (!returnType.isOrImplements(StandardClassIds.Collection, session)) {
          reporter.reportOn(
            source,
            MetroDiagnostics.MULTIBINDS_ERROR,
            "`@ElementsIntoSet` must return a Collection.",
          )
          return
        }
      }
    }

    // Check for 1:1 `@IntoMap`+`@MapKey`
    if (annotations.mapKeys.size > 1) {
      for (key in annotations.mapKeys) {
        reporter.reportOn(
          key.fir.source,
          MetroDiagnostics.MULTIBINDS_ERROR,
          "Only one @MapKey should be be used on a given @IntoMap declaration.",
        )
      }
      return
    } else if (annotations.isIntoMap && annotations.mapKeys.isEmpty()) {
      reporter.reportOn(
        source,
        MetroDiagnostics.MULTIBINDS_ERROR,
        "`@IntoMap` declarations must define a @MapKey annotation.",
      )
      return
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
