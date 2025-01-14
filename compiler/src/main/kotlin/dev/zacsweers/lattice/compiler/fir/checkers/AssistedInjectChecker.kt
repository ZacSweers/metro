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
package dev.zacsweers.lattice.compiler.fir.checkers

import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.fir.FirLatticeErrors.ASSISTED_INJECTION_ERROR
import dev.zacsweers.lattice.compiler.fir.FirTypeKey
import dev.zacsweers.lattice.compiler.fir.annotationsIn
import dev.zacsweers.lattice.compiler.fir.checkers.AssistedInjectChecker.FirAssistedParameterKey.Companion.toAssistedParameterKey
import dev.zacsweers.lattice.compiler.fir.findInjectConstructor
import dev.zacsweers.lattice.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.singleAbstractFunction
import dev.zacsweers.lattice.compiler.fir.validateApiDeclaration
import dev.zacsweers.lattice.compiler.mapToSetWithDupes
import dev.zacsweers.lattice.compiler.unsafeLazy
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
    val latticeClassIds = session.latticeClassIds

    // Check if this is an assisted factory
    val isAssistedFactory =
      declaration.isAnnotatedWithAny(session, latticeClassIds.assistedFactoryAnnotations)

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
      targetType.symbol.findInjectConstructor(session, context, reporter) {
        return
      }
    if (injectConstructor == null) {
      reporter.reportOn(
        targetType.source,
        ASSISTED_INJECTION_ERROR,
        "`@AssistedFactory` targets must have a single `@Inject`-annotated constructor.",
        context,
      )
      return
    }

    // check for scopes? Scopes not allowed, dagger ignores them
    // TODO error + test

    val functionParams = function.valueParameterSymbols
    val constructorAssistedParams =
      injectConstructor.valueParameterSymbols.filter {
        it.isAnnotatedWithAny(session, latticeClassIds.assistedAnnotations)
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
            .annotationsIn(session, session.latticeClassIds.assistedAnnotations)
            .singleOrNull()
            ?.getStringArgument(LatticeSymbols.Names.value, session)
            .orEmpty(),
        )
      }
    }
  }
}
