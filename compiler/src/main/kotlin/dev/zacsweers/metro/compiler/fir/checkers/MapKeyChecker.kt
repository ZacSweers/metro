// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.MAP_KEY_ERROR
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics.MAP_KEY_TYPE_PARAM_ERROR
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.resolvedClassId
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.getBooleanArgument
import org.jetbrains.kotlin.fir.declarations.primaryConstructorIfAny
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.types.UnexpandedTypeCheck
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isArrayType
import org.jetbrains.kotlin.fir.types.isKClassType
import org.jetbrains.kotlin.name.StandardClassIds

internal object MapKeyChecker : FirClassChecker(MppCheckerKind.Common) {

  @OptIn(UnexpandedTypeCheck::class)
  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    val session = context.session
    val anno =
      declaration
        .annotationsIn(session, session.metroFirBuiltIns.classIds.mapKeyAnnotations)
        .firstOrNull() ?: return

    if (declaration.typeParameters.isNotEmpty()) {
      reporter.reportOn(
        declaration.source,
        MAP_KEY_TYPE_PARAM_ERROR,
        "Map key annotations cannot have type parameters.",
      )
    }

    // Must support FUNCTION targets (Metro copies map key annotations onto generated binds
    // functions).
    // If no @Target is declared, default targets include FUNCTION, so only check explicit ones.
    declaration.annotations
      .firstOrNull { it.toAnnotationClassIdSafe(session) == StandardClassIds.Annotations.Target }
      ?.let { targetAnno ->
        val hasFunctionTarget =
          targetAnno.argumentMapping.mapping.values
            .filterIsInstance<FirVarargArgumentsExpression>()
            .flatMap { it.arguments }
            .filterIsInstance<FirPropertyAccessExpression>()
            .any {
              it.calleeReference.toResolvedCallableSymbol()?.callableId?.callableName?.asString() ==
                "FUNCTION"
            }
        if (!hasFunctionTarget) {
          reporter.reportOn(
            targetAnno.source,
            MAP_KEY_ERROR,
            "Map key annotations must support at least FUNCTION targets.",
          )
        }
      }

    val ctor = declaration.primaryConstructorIfAny(session)
    if (ctor == null || ctor.valueParameterSymbols.isEmpty()) {
      reporter.reportOn(
        ctor?.source ?: declaration.source,
        MAP_KEY_ERROR,
        "Map key annotations must have a primary constructor with at least one parameter.",
      )
    } else {
      val unwrapValues = anno.getBooleanArgument(Symbols.Names.unwrapValue, session) ?: true
      if (unwrapValues) {
        when (ctor.valueParameterSymbols.size) {
          0 -> {
            // Handled above
          }
          1 -> {
            if (ctor.valueParameterSymbols[0].resolvedReturnTypeRef.isArrayType) {
              reporter.reportOn(
                ctor.valueParameterSymbols[0].resolvedReturnTypeRef.source ?: ctor.source,
                MAP_KEY_ERROR,
                "Map key annotations with unwrapValue set to true (the default) cannot have an array parameter.",
              )
            }
          }
          else -> {
            reporter.reportOn(
              ctor.source,
              MAP_KEY_ERROR,
              "Map key annotations with unwrapValue set to true (the default) can only have a single constructor parameter.",
            )
          }
        }
      }

      val implicitClassKey =
        anno.getBooleanArgument(Symbols.Names.implicitClassKey, session) == true
      if (implicitClassKey) {
        if (ctor.valueParameterSymbols.size != 1) {
          reporter.reportOn(
            ctor.source,
            MAP_KEY_ERROR,
            "Map key annotations with implicitClassKey must have exactly one parameter but found ${ctor.valueParameterSymbols.size}.",
          )
          return
        }

        val param = ctor.valueParameterSymbols[0]

        // Must be a KClass type
        val returnTypeRef = param.resolvedReturnTypeRef
        if (!returnTypeRef.coneType.isKClassType()) {
          reporter.reportOn(
            returnTypeRef.source ?: ctor.source,
            MAP_KEY_ERROR,
            "Map key annotations with implicitClassKey must have a single KClass parameter but found ${returnTypeRef.coneType.classId?.asFqNameString()}.",
          )
        }

        // Must have a default value
        val defaultValueExpression = param.resolvedDefaultValue
        if (defaultValueExpression == null) {
          reporter.reportOn(
            param.source ?: ctor.source,
            MAP_KEY_ERROR,
            "Map key annotations with implicitClassKey must have a default value of `Nothing::class` but found no default value.",
          )
        } else {
          val defaultClassId =
            defaultValueExpression.expectAsOrNull<FirGetClassCall>()?.resolvedClassId()
          if (defaultClassId != StandardClassIds.Nothing) {
            reporter.reportOn(
              defaultValueExpression.source ?: param.source ?: ctor.source,
              MAP_KEY_ERROR,
              "Map key annotations with implicitClassKey must have a default value of `Nothing::class` but found ${defaultClassId?.asFqNameString()}::class.",
            )
          }
        }
      }
    }
  }
}
