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
package dev.zacsweers.lattice.fir.checkers

import dev.zacsweers.lattice.LatticeClassIds
import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.fir.FirLatticeErrors.ASSISTED_INJECTION
import dev.zacsweers.lattice.fir.FirTypeKey
import dev.zacsweers.lattice.fir.annotationsIn
import dev.zacsweers.lattice.fir.checkers.AssistedInjectChecker.FirAssistedParameterKey.Companion.toAssistedParameterKey
import dev.zacsweers.lattice.fir.findInjectConstructor
import dev.zacsweers.lattice.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.fir.latticeClassIds
import dev.zacsweers.lattice.fir.singleAbstractFunction
import dev.zacsweers.lattice.fir.validateFactoryClass
import dev.zacsweers.lattice.mapToSetWithDupes
import dev.zacsweers.lattice.unsafeLazy
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
    val source = declaration.source ?: return
    val session = context.session
    val latticeClassIds = session.latticeClassIds

    // Check if this is an assisted factory
    val isAssistedFactory =
      declaration.isAnnotatedWithAny(session, latticeClassIds.assistedFactoryAnnotations)

    if (!isAssistedFactory) return

    declaration.validateFactoryClass(context, reporter, "Assisted factory") {
      return
    }

    // Get single abstract function
    val function =
      declaration.singleAbstractFunction(session, context, reporter, "@AssistedFactory") {
        return
      }

    // Ensure target type has an assistedinject constructor
    val targetType = function.returnTypeRef.firClassLike(session) as? FirClass? ?: return
    val injectConstructor =
      targetType.findInjectConstructor(session, latticeClassIds, context, reporter) {
        return
      }
    if (
      injectConstructor == null ||
        !injectConstructor.annotations.isAnnotatedWithAny(
          session,
          latticeClassIds.assistedInjectAnnotations,
        )
    ) {
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION,
        "`@AssistedFactory` targets must have a single `@AssistedInject`-annotated constructor.",
        context,
      )
      return
    }

    // check for scopes? Scopes not allowed, dagger ignores them
    // TODO error + test

    val functionParams = function.valueParameters
    val constructorAssistedParams =
      injectConstructor.valueParameterSymbols.filter {
        it.annotations.isAnnotatedWithAny(session, latticeClassIds.assistedAnnotations)
      }

    // ensure assisted params match
    if (functionParams.size != constructorAssistedParams.size) {
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION,
        "Assisted parameter mismatch. Expected ${functionParams.size} assisted parameters but found ${constructorAssistedParams.size}.",
        context,
      )
      return
    }

    val (factoryKeys, dupeFactoryKeys) =
      functionParams.mapToSetWithDupes {
        it.symbol.toAssistedParameterKey(
          session,
          latticeClassIds,
          FirTypeKey.from(session, latticeClassIds, it),
        )
      }

    if (dupeFactoryKeys.isNotEmpty()) {
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION,
        "Assisted factory parameters must be unique. Found duplicates: ${dupeFactoryKeys.joinToString(", ")}",
        context,
      )
      return
    }

    val (constructorKeys, dupeConstructorKeys) =
      constructorAssistedParams.mapToSetWithDupes {
        it.toAssistedParameterKey(
          session,
          latticeClassIds,
          FirTypeKey.from(session, latticeClassIds, it),
        )
      }

    if (dupeConstructorKeys.isNotEmpty()) {
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION,
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
        ASSISTED_INJECTION,
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
        latticeClassIds: LatticeClassIds,
        typeKey: FirTypeKey,
      ): FirAssistedParameterKey {
        return FirAssistedParameterKey(
          typeKey,
          annotations
            .annotationsIn(session, latticeClassIds.assistedAnnotations)
            .singleOrNull()
            ?.getStringArgument(LatticeSymbols.Names.Value, session)
            .orEmpty(),
        )
      }
    }
  }
}
