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
import dev.zacsweers.lattice.fir.FirLatticeErrors
import dev.zacsweers.lattice.fir.FirTypeKey
import dev.zacsweers.lattice.fir.LatticeFirAnnotation
import dev.zacsweers.lattice.fir.allFunctions
import dev.zacsweers.lattice.fir.annotationsIn
import dev.zacsweers.lattice.fir.checkVisibility
import dev.zacsweers.lattice.fir.isAnnotatedWithAny
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.resolve.firClassLike
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.scopes.jvm.computeJvmDescriptor
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.resolvedType

internal class ComponentCreatorChecker(
  private val session: FirSession,
  private val latticeClassIds: LatticeClassIds,
) : FirClassChecker(MppCheckerKind.Common) {
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    val source = declaration.source ?: return
    val componentFactoryAnnotation =
      declaration.annotationsIn(session, latticeClassIds.componentFactoryAnnotations).toList()

    if (componentFactoryAnnotation.isEmpty()) return

    if (declaration.isLocal) {
      reporter.reportOn(source, FirLatticeErrors.COMPONENT_CREATORS_CANNOT_BE_LOCAL, context)
      return
    }

    when (declaration.classKind) {
      ClassKind.INTERFACE -> {
        // This is fine
        when (declaration.modality) {
          Modality.SEALED -> {
            reporter.reportOn(
              source,
              FirLatticeErrors.COMPONENT_CREATORS_SHOULD_BE_INTERFACE_OR_ABSTRACT,
              context,
            )
            return
          }
          else -> {
            // This is fine
          }
        }
      }
      ClassKind.CLASS -> {
        when (declaration.modality) {
          Modality.ABSTRACT -> {
            // This is fine
          }
          else -> {
            // final/open/sealed
            reporter.reportOn(
              source,
              FirLatticeErrors.COMPONENT_CREATORS_SHOULD_BE_INTERFACE_OR_ABSTRACT,
              context,
            )
            return
          }
        }
      }
      else -> {
        reporter.reportOn(
          source,
          FirLatticeErrors.COMPONENT_CREATORS_SHOULD_BE_INTERFACE_OR_ABSTRACT,
          context,
        )
        return
      }
    }

    declaration.checkVisibility { source ->
      reporter.reportOn(source, FirLatticeErrors.COMPONENT_CREATORS_MUST_BE_VISIBLE, context)
      return
    }

    val abstractFunctions =
      declaration
        .allFunctions(session)
        // Merge inherited functions with matching signatures
        .groupBy {
          // Don't include the return type because overrides may have different ones
          it.computeJvmDescriptor(includeReturnType = false)
        }
        .mapValues { (_, functions) ->
          val (abstract, implemented) =
            functions.partition { it.modality == Modality.ABSTRACT && it.body == null }
          if (abstract.isEmpty()) {
            // All implemented, nothing to do
            null
          } else if (implemented.isNotEmpty()) {
            // If there's one implemented one, it's not abstract anymore in our materialized type
            null
          } else {
            // Only need one for the rest of this
            abstract[0]
          }
        }
        .values
        .filterNotNull()

    if (abstractFunctions.size != 1) {
      if (abstractFunctions.isEmpty()) {
        reporter.reportOn(
          source,
          FirLatticeErrors.COMPONENT_CREATORS_FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION,
          context,
        )
      } else {
        // Report each function
        for (abstractFunction in abstractFunctions) {
          reporter.reportOn(
            abstractFunction.source,
            FirLatticeErrors.COMPONENT_CREATORS_FACTORY_MUST_HAVE_ONE_ABSTRACT_FUNCTION,
            context,
          )
        }
      }
      return
    }

    val createFunction = abstractFunctions.single()

    createFunction.checkVisibility { source ->
      reporter.reportOn(
        source,
        FirLatticeErrors.COMPONENT_CREATORS_FACTORY_FUNCTION_MUST_BE_VISIBLE,
        context,
      )
      return
    }

    val paramTypes = mutableSetOf<FirTypeKey>()

    for (param in createFunction.valueParameters) {
      val clazz = param.returnTypeRef.firClassLike(session)!!
      val isValid =
        param.isAnnotatedWithAny(session, latticeClassIds.bindsInstanceAnnotations) ||
          clazz.isAnnotatedWithAny(session, latticeClassIds.componentAnnotations)
      if (!isValid) {
        reporter.reportOn(
          param.source,
          FirLatticeErrors.COMPONENT_CREATORS_FACTORY_PARAMS_MUST_BE_BINDSINSTANCE_OR_COMPONENTS,
          context,
        )
        return
      }
      // Check duplicate params
      val qualifier =
        param.annotations
          .filterIsInstance<FirAnnotationCall>()
          .singleOrNull { annotationCall ->
            val annotationType =
              annotationCall.resolvedType as? ConeClassLikeType ?: return@singleOrNull false
            val annotationClass = annotationType.toClassSymbol(session) ?: return@singleOrNull false
            annotationClass.annotations.isAnnotatedWithAny(
              session,
              latticeClassIds.qualifierAnnotations,
            )
          }
          ?.let { LatticeFirAnnotation(it) }
      val typeKey = FirTypeKey(param.returnTypeRef, qualifier)
      if (!paramTypes.add(typeKey)) {
        reporter.reportOn(
          param.source,
          FirLatticeErrors.COMPONENT_CREATORS_FACTORY_PARAMS_MUST_BE_UNIQUE,
          context,
        )
        return
      }
    }
  }
}
