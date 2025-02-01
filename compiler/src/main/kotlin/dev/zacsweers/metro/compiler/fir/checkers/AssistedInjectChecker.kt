// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.fir.FirMetroErrors.ASSISTED_INJECTION_ERROR
import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.checkers.AssistedInjectChecker.FirAssistedParameterKey.Companion.toAssistedParameterKey
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.findInjectConstructor
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.singleAbstractFunction
import dev.zacsweers.metro.compiler.fir.validateApiDeclaration
import dev.zacsweers.metro.compiler.mapToSetWithDupes
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.getStringArgument
import org.jetbrains.kotlin.fir.resolve.firClassLike
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol

internal object AssistedInjectChecker : FirClassChecker(MppCheckerKind.Common) {
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    declaration.source ?: return
    val session = context.session
    val classIds = session.classIds

    // Check if this is an assisted factory
    val isAssistedFactory =
      declaration.isAnnotatedWithAny(session, classIds.assistedFactoryAnnotations)

    if (!isAssistedFactory) return

    declaration.validateApiDeclaration(context, reporter, "Assisted factory") {
      return
    }

    // Get single abstract function
    val function =
      declaration.singleAbstractFunction(session, context, reporter, "@AssistedFactory") {
        return
      }

    // TODO dagger doesn't allow type params on these, but seems like we could?
    if (function.typeParameterSymbols.isNotEmpty()) {
      reporter.reportOn(
        function.source,
        ASSISTED_INJECTION_ERROR,
        "`@AssistedFactory` functions cannot have type parameters.",
        context,
      )
      return
    }

    // Ensure target type has an inject constructor
    val targetType = function.resolvedReturnTypeRef.firClassLike(session) as? FirClass? ?: return
    val injectConstructor =
      targetType.symbol.findInjectConstructor(session, context, reporter, checkClass = true) {
        return
      }
    if (injectConstructor == null) {
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION_ERROR,
        "`@AssistedFactory` target classes must have a single `@Inject`-annotated constructor or be annotated `@Inject` with only a primary constructor.",
        context,
      )
      return
    }

    // check for scopes? Scopes not allowed, dagger ignores them
    // TODO error + test

    val functionParams = function.valueParameterSymbols
    val constructorAssistedParams =
      injectConstructor.valueParameterSymbols.filter {
        it.isAnnotatedWithAny(session, classIds.assistedAnnotations)
      }

    // ensure assisted params match
    if (functionParams.size != constructorAssistedParams.size) {
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION_ERROR,
        "Assisted parameter mismatch. Expected ${functionParams.size} assisted parameters but found ${constructorAssistedParams.size}.",
        context,
      )
      return
    }

    val (factoryKeys, dupeFactoryKeys) =
      functionParams.mapToSetWithDupes {
        it.toAssistedParameterKey(session, FirTypeKey.from(session, it))
      }

    if (dupeFactoryKeys.isNotEmpty()) {
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION_ERROR,
        "Assisted factory parameters must be unique. Found duplicates: ${dupeFactoryKeys.joinToString(", ")}",
        context,
      )
      return
    }

    val (constructorKeys, dupeConstructorKeys) =
      constructorAssistedParams.mapToSetWithDupes {
        it.toAssistedParameterKey(session, FirTypeKey.from(session, it))
      }

    if (dupeConstructorKeys.isNotEmpty()) {
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION_ERROR,
        "Assisted constructor parameters must be unique. Found duplicates: $dupeConstructorKeys",
        context,
      )
      return
    }

    //    for (parameters in listOf(factoryKeys, constructorKeys)) {
    //      // no qualifiers on assisted params
    //      // TODO error + test. Or just just ignore them?
    //    }

    // check non-matching keys
    if (factoryKeys != constructorKeys) {
      val missingFromFactory = factoryKeys.subtract(constructorKeys).joinToString()
      val missingFromConstructor = constructorKeys.subtract(factoryKeys).joinToString()
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION_ERROR,
        buildString {
          appendLine(
            "Parameter mismatch. Assisted factory and assisted inject constructor parameters must match but found differences:"
          )
          if (missingFromFactory.isNotEmpty()) {
            appendLine("  Missing from factory: $missingFromFactory")
          }
          if (missingFromConstructor.isNotEmpty()) {
            appendLine("  Missing from factory: $missingFromConstructor")
          }
        },
        context,
      )
      return
    }
  }

  // @Assisted parameters are equal, if the type and the identifier match. This subclass makes
  // diffing the parameters easier.
  data class FirAssistedParameterKey(val typeKey: FirTypeKey, val assistedIdentifier: String) {
    private val cachedToString by unsafeLazy {
      buildString {
        append(typeKey)
        if (assistedIdentifier.isNotEmpty()) {
          append(" (")
          append(assistedIdentifier)
          append(")")
        }
      }
    }

    override fun toString() = cachedToString

    companion object {
      fun FirValueParameterSymbol.toAssistedParameterKey(
        session: FirSession,
        typeKey: FirTypeKey,
      ): FirAssistedParameterKey {
        return FirAssistedParameterKey(
          typeKey,
          annotations
            .annotationsIn(session, session.classIds.assistedAnnotations)
            .singleOrNull()
            ?.getStringArgument(Symbols.Names.value, session)
            .orEmpty(),
        )
      }
    }
  }
}
