/*
 * Copyright (C) 2025 Zac Sweers
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

import dev.drewhamilton.poko.Poko
import dev.zacsweers.lattice.compiler.fir.FirLatticeErrors
import dev.zacsweers.lattice.compiler.fir.FirTypeKey
import dev.zacsweers.lattice.compiler.fir.findInjectConstructors
import dev.zacsweers.lattice.compiler.fir.latticeClassIds
import dev.zacsweers.lattice.compiler.fir.qualifierAnnotation
import dev.zacsweers.lattice.compiler.fir.resolvedBoundType
import dev.zacsweers.lattice.compiler.fir.resolvedScopeClassId
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.fullyExpandedClassId
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.types.UnexpandedTypeCheck
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isAny
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.name.ClassId

internal object AggregationChecker : FirClassChecker(MppCheckerKind.Common) {
  @OptIn(UnexpandedTypeCheck::class)
  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
    declaration.source ?: return
    val session = context.session
    val latticeClassIds = session.latticeClassIds
    // TODO
    //  validate map key with intomap (class or bound type)

    val contributesToAnnotations = mutableSetOf<Contribution.ContributesTo>()
    val contributesBindingAnnotations = mutableSetOf<Contribution.ContributesBinding>()
    val contributesIntoSetAnnotations = mutableSetOf<FirAnnotation>()
    val contributesIntoMapAnnotations = mutableSetOf<FirAnnotation>()

    val classQualifier = declaration.annotations.qualifierAnnotation(session)

    for (annotation in declaration.annotations.filter { it.isResolved }) {
      val classId = annotation.toAnnotationClassId(session) ?: continue
      if (classId in latticeClassIds.allContributesAnnotations) {
        val scope = annotation.resolvedScopeClassId() ?: continue
        val replaces = emptySet<ClassId>() // TODO implement

        when (classId) {
          in latticeClassIds.contributesToAnnotations -> {
            val contribution = Contribution.ContributesTo(declaration, annotation, scope, replaces)
            addContributionAndCheckForDuplicate(
              contribution,
              contributesToAnnotations,
              annotation,
              scope,
              reporter,
              context,
            ) {
              return
            }
          }
          in latticeClassIds.contributesBindingAnnotations -> {
            // Ensure the class is injected
            val injectConstructor =
              declaration.symbol.findInjectConstructors(session).singleOrNull()
            if (injectConstructor == null) {
              reporter.reportOn(
                annotation.source,
                FirLatticeErrors.AGGREGATION_ERROR,
                "`@ContributesBinding` is only applicable to constructor-injected classes. Did you forget to inject ${declaration.symbol.classId.asSingleFqName()}?",
                context,
              )
              return
            }

            val hasSupertypes = declaration.superTypeRefs.isNotEmpty()

            val explicitBoundType = annotation.resolvedBoundType()

            val typeKey =
              if (explicitBoundType != null) {
                // No need to check for nullable Nothing because it's enforced with the <T : Any>
                // bound
                if (explicitBoundType.isNothing) {
                  reporter.reportOn(
                    explicitBoundType.source,
                    FirLatticeErrors.AGGREGATION_ERROR,
                    "Explicit bound types should not be `Nothing` or `Nothing?`.",
                    context,
                  )
                  return
                }

                if (!hasSupertypes && !explicitBoundType.isAny) {
                  reporter.reportOn(
                    annotation.source,
                    FirLatticeErrors.AGGREGATION_ERROR,
                    "`@ContributesBinding`-annotated class ${declaration.symbol.classId.asSingleFqName()} has no supertypes to bind to.",
                    context,
                  )
                  return
                }

                val coneType = explicitBoundType.coneTypeOrNull ?: continue
                val refClassId = coneType.fullyExpandedClassId(session) ?: continue

                if (refClassId == declaration.symbol.classId) {
                  reporter.reportOn(
                    explicitBoundType.source,
                    FirLatticeErrors.AGGREGATION_ERROR,
                    "Redundant explicit bound type ${refClassId.asSingleFqName()} is the same as the annotated class ${refClassId.asSingleFqName()}.",
                    context,
                  )
                  return
                }

                val implementsBoundType =
                  lookupSuperTypes(klass = declaration, true, true, session, true).any {
                    it.classId?.let { it == refClassId } == true
                  }
                if (!implementsBoundType) {
                  reporter.reportOn(
                    explicitBoundType.source,
                    FirLatticeErrors.AGGREGATION_ERROR,
                    "Class ${classId.asSingleFqName()} does not implement explicit bound type ${refClassId.asSingleFqName()}",
                    context,
                  )
                  return
                }

                FirTypeKey(
                  coneType,
                  (explicitBoundType.annotations.qualifierAnnotation(session) ?: classQualifier),
                )
              } else {
                if (!hasSupertypes) {
                  reporter.reportOn(
                    annotation.source,
                    FirLatticeErrors.AGGREGATION_ERROR,
                    "`@ContributesBinding`-annotated class ${declaration.symbol.classId.asSingleFqName()} has no supertypes to bind to.",
                    context,
                  )
                  return
                } else if (declaration.superTypeRefs.size != 1) {
                  reporter.reportOn(
                    annotation.source,
                    FirLatticeErrors.AGGREGATION_ERROR,
                    "`@ContributesBinding`-annotated class @${classId.asSingleFqName()} doesn't declare an explicit `boundType` but has multiple supertypes. You must define an explicit bound type in this scenario.",
                    context,
                  )
                  return
                }
                val implicitBoundType = declaration.superTypeRefs[0]
                FirTypeKey(implicitBoundType.coneType, classQualifier)
              }

            val contribution =
              Contribution.ContributesBinding(declaration, annotation, scope, replaces, typeKey)
            addContributionAndCheckForDuplicate(
              contribution,
              contributesBindingAnnotations,
              annotation,
              scope,
              reporter,
              context,
            ) {
              return
            }
          }
        }
      }
    }
  }

  private inline fun <T : Contribution> addContributionAndCheckForDuplicate(
    contribution: T,
    collection: MutableSet<T>,
    annotation: FirAnnotation,
    scope: ClassId,
    reporter: DiagnosticReporter,
    context: CheckerContext,
    onError: () -> Nothing,
  ) {
    val added = collection.add(contribution)
    if (!added) {
      reporter.reportOn(
        annotation.source,
        FirLatticeErrors.AGGREGATION_ERROR,
        "Duplicate `@${contribution.name}` annotations contributing to scope `${scope.shortClassName}`.",
        context,
      )

      val existing = collection.first { it == contribution }
      reporter.reportOn(
        existing.annotation.source,
        FirLatticeErrors.AGGREGATION_ERROR,
        "Duplicate `@${contribution.name}` annotations contributing to scope `${scope.shortClassName}`.",
        context,
      )

      onError()
    }
  }

  sealed interface Contribution {
    val declaration: FirClass
    val annotation: FirAnnotation
    val scope: ClassId
    val replaces: Set<ClassId>
    val name: String

    sealed interface BindingContribution : Contribution {
      val boundType: FirTypeKey
    }

    @Poko
    class ContributesTo(
      override val declaration: FirClass,
      @Poko.Skip override val annotation: FirAnnotation,
      override val scope: ClassId,
      override val replaces: Set<ClassId>,
    ) : Contribution {
      override val name: String = "ContributesTo"
    }

    @Poko
    class ContributesBinding(
      override val declaration: FirClass,
      @Poko.Skip override val annotation: FirAnnotation,
      override val scope: ClassId,
      override val replaces: Set<ClassId>,
      override val boundType: FirTypeKey,
    ) : Contribution, BindingContribution {
      override val name: String = "ContributesBinding"
    }

    //    data class ContributesIntoSetBinding(
    //      override val annotatedType: FirClassSymbol<*>,
    //      override val annotation: FirAnnotation,
    //    ) : Contribution, BindingContribution {
    //      override val origin: ClassId = annotatedType.classId
    //      override val callableName: String = "bindIntoSet"
    //    }
    //
    //    data class ContributesIntoMapBinding(
    //      override val annotatedType: FirClassSymbol<*>,
    //      override val annotation: FirAnnotation,
    //    ) : Contribution, BindingContribution {
    //      override val origin: ClassId = annotatedType.classId
    //      override val callableName: String = "bindIntoMap"
    //    }
  }
}
