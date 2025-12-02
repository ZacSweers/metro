// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.MetroFirAnnotation
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.findInjectLikeConstructors
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isBindingContainer
import dev.zacsweers.metro.compiler.fir.isOrImplements
import dev.zacsweers.metro.compiler.fir.isResolved
import dev.zacsweers.metro.compiler.fir.mapKeyAnnotation
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.resolvedBindingArgument
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.isObject
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.fullyExpandedClassId
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.nameOrSpecialName
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.types.UnexpandedTypeCheck
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isAny
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.name.ClassId

internal object AggregationChecker : FirClassChecker(MppCheckerKind.Common) {
  enum class ContributionKind(val readableName: String) {
    CONTRIBUTES_TO("ContributesTo"),
    CONTRIBUTES_BINDING("ContributesBinding"),
    CONTRIBUTES_INTO_SET("ContributesIntoSet"),
    CONTRIBUTES_INTO_MAP("ContributesIntoMap");

    override fun toString(): String = readableName
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    declaration.source ?: return
    val session = context.session
    val classIds = session.classIds
    // TODO
    //  validate map key with intomap (class or bound type)

    val contributesToAnnotations = mutableSetOf<Contribution.ContributesTo>()
    val contributesBindingAnnotations = mutableSetOf<Contribution.ContributesBinding>()
    val contributesIntoSetAnnotations = mutableSetOf<Contribution.ContributesIntoSet>()
    val contributesIntoMapAnnotations = mutableSetOf<Contribution.ContributesIntoMap>()

    val classQualifier = declaration.annotations.qualifierAnnotation(session)

    for (annotation in declaration.annotations.filter { it.isResolved }) {
      val classId = annotation.toAnnotationClassId(session) ?: continue
      if (classId in classIds.allContributesAnnotations) {
        val scope = annotation.resolvedScopeClassId() ?: continue
        val replaces = emptySet<ClassId>() // TODO implement
        val checkIntoSet by memoize {
          checkBindingContribution(
            session,
            ContributionKind.CONTRIBUTES_INTO_SET,
            declaration,
            classQualifier,
            annotation,
            scope,
            classId,
            contributesIntoSetAnnotations,
            isMapBinding = false,
          ) { bindingType, _ ->
            Contribution.ContributesIntoSet(declaration, annotation, scope, replaces, bindingType)
          }
        }
        val checkIntoMap by memoize {
          checkBindingContribution(
            session,
            ContributionKind.CONTRIBUTES_INTO_MAP,
            declaration,
            classQualifier,
            annotation,
            scope,
            classId,
            contributesIntoMapAnnotations,
            isMapBinding = true,
          ) { bindingType, mapKey ->
            Contribution.ContributesIntoMap(
              declaration,
              annotation,
              scope,
              replaces,
              bindingType,
              mapKey!!,
            )
          }
        }
        when (classId) {
          in classIds.contributesToAnnotations -> {
            val contribution = Contribution.ContributesTo(declaration, annotation, scope, replaces)
            addContributionAndCheckForDuplicate(
              session,
              contribution,
              ContributionKind.CONTRIBUTES_TO,
              contributesToAnnotations,
              annotation,
              scope,
            ) {
              return
            }
          }
          in classIds.contributesBindingAnnotations -> {
            val valid =
              checkBindingContribution(
                session,
                ContributionKind.CONTRIBUTES_BINDING,
                declaration,
                classQualifier,
                annotation,
                scope,
                classId,
                contributesBindingAnnotations,
                isMapBinding = false,
              ) { bindingType, _ ->
                Contribution.ContributesBinding(
                  declaration,
                  annotation,
                  scope,
                  replaces,
                  bindingType,
                )
              }
            if (!valid) {
              return
            }
          }
          in classIds.contributesIntoSetAnnotations -> {
            if (!checkIntoSet) {
              return
            }
          }
          in classIds.contributesIntoMapAnnotations -> {
            if (!checkIntoMap) {
              return
            }
          }
          in classIds.customContributesIntoSetAnnotations -> {
            val isMapBinding = declaration.annotations.mapKeyAnnotation(session) != null
            val valid = if (isMapBinding) checkIntoMap else checkIntoSet
            if (!valid) {
              return
            }
          }
        }
      }
    }
  }

  @OptIn(UnexpandedTypeCheck::class)
  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun <T : Contribution> checkBindingContribution(
    session: FirSession,
    kind: ContributionKind,
    declaration: FirClass,
    classQualifier: MetroFirAnnotation?,
    annotation: FirAnnotation,
    scope: ClassId,
    classId: ClassId,
    collection: MutableSet<T>,
    isMapBinding: Boolean,
    createBinding: (FirTypeKey, mapKey: MetroFirAnnotation?) -> T,
  ): Boolean {
    val isAssistedFactory =
      declaration.symbol.isAnnotatedWithAny(session, session.classIds.assistedFactoryAnnotations)
    // Ensure the class is injected or an object. Objects are ok IFF they are not @ContributesTo
    val isNotInjectedOrFactory =
      !isAssistedFactory &&
        declaration.symbol.findInjectLikeConstructors(session).singleOrNull() == null
    val isValidObject = declaration.classKind.isObject && kind != ContributionKind.CONTRIBUTES_TO
    if (isNotInjectedOrFactory && !isValidObject) {
      reporter.reportOn(
        annotation.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "`@$kind` is only applicable to constructor-injected classes, assisted factories, or objects. Ensure ${declaration.symbol.classId.asSingleFqName()} is injectable or a bindable object.",
      )
      return false
    }

    val supertypesExcludingAny = declaration.superTypeRefs.filterNot { it.coneType.isAny }
    val hasSupertypes = supertypesExcludingAny.isNotEmpty()

    val explicitBindingType = annotation.resolvedBindingArgument(session)

    val typeKey =
      if (explicitBindingType != null) {
        // No need to check for nullable Nothing because it's enforced with the <T : Any>
        // bound
        if (explicitBindingType.isNothing) {
          reporter.reportOn(
            explicitBindingType.source ?: annotation.source,
            MetroDiagnostics.AGGREGATION_ERROR,
            "Explicit bound types should not be `Nothing` or `Nothing?`.",
          )
          return false
        }

        val coneType = explicitBindingType.coneTypeOrNull ?: return true
        val refClassId = coneType.fullyExpandedClassId(session) ?: return true

        if (refClassId == declaration.symbol.classId) {
          reporter.reportOn(
            explicitBindingType.source ?: annotation.source,
            MetroDiagnostics.AGGREGATION_ERROR,
            "Redundant explicit bound type ${refClassId.asSingleFqName()} is the same as the annotated class ${refClassId.asSingleFqName()}.",
          )
          return false
        }

        if (!hasSupertypes) {
          reporter.reportOn(
            annotation.source,
            MetroDiagnostics.AGGREGATION_ERROR,
            "`@$kind`-annotated class ${declaration.symbol.classId.asSingleFqName()} has no supertypes to bind to.",
          )
          return false
        }

        val implementsBindingType = declaration.isOrImplements(refClassId, session)

        if (!implementsBindingType) {
          reporter.reportOn(
            explicitBindingType.source,
            MetroDiagnostics.AGGREGATION_ERROR,
            "Class ${declaration.classId.asSingleFqName()} does not implement explicit bound type ${refClassId.asSingleFqName()}",
          )
          return false
        }

        FirTypeKey(coneType, (explicitBindingType.annotations.qualifierAnnotation(session)))
      } else {
        if (!hasSupertypes) {
          reporter.reportOn(
            annotation.source,
            MetroDiagnostics.AGGREGATION_ERROR,
            "`@$kind`-annotated class ${declaration.symbol.classId.asSingleFqName()} has no supertypes to bind to.",
          )
          return false
        } else if (supertypesExcludingAny.size != 1) {
          reporter.reportOn(
            annotation.source,
            MetroDiagnostics.AGGREGATION_ERROR,
            "`@$kind`-annotated class @${classId.asSingleFqName()} doesn't declare an explicit `bindingType` but has multiple supertypes. You must define an explicit bound type in this scenario.",
          )
          return false
        }
        val implicitBindingType = supertypesExcludingAny[0]
        FirTypeKey(implicitBindingType.coneType, classQualifier)
      }

    val mapKey =
      if (isMapBinding) {
        val classMapKey = declaration.annotations.mapKeyAnnotation(session)
        val resolvedKey =
          if (explicitBindingType == null) {
            classMapKey.also {
              if (it == null) {
                reporter.reportOn(
                  annotation.source,
                  MetroDiagnostics.AGGREGATION_ERROR,
                  "`@$kind`-annotated class ${declaration.classId.asSingleFqName()} must declare a map key on the class or an explicit bound type but doesn't.",
                )
              }
            }
          } else {
            (explicitBindingType.annotations.mapKeyAnnotation(session) ?: classMapKey).also {
              if (it == null) {
                reporter.reportOn(
                  explicitBindingType.source,
                  MetroDiagnostics.AGGREGATION_ERROR,
                  "`@$kind`-annotated class @${declaration.symbol.classId.asSingleFqName()} must declare a map key but doesn't. Add one on the explicit bound type or the class.",
                )
              }
            }
          }
        resolvedKey ?: return false
      } else {
        null
      }

    val contribution = createBinding(typeKey, mapKey)
    addContributionAndCheckForDuplicate(
      session,
      contribution,
      kind,
      collection,
      annotation,
      scope,
    ) {
      return false
    }
    return true
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private inline fun <T : Contribution> addContributionAndCheckForDuplicate(
    session: FirSession,
    contribution: T,
    kind: ContributionKind,
    collection: MutableSet<T>,
    annotation: FirAnnotation,
    scope: ClassId,
    onError: () -> Nothing,
  ) {
    checkContributionKind(session, kind, annotation, contribution) { onError() }
    val added = collection.add(contribution)
    if (!added) {
      reporter.reportOn(
        annotation.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "Duplicate `@${kind}` annotations contributing to scope `${scope.shortClassName}`.",
      )

      val existing = collection.first { it == contribution }
      reporter.reportOn(
        existing.annotation.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "Duplicate `@${kind}` annotations contributing to scope `${scope.shortClassName}`.",
      )

      onError()
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private inline fun checkContributionKind(
    session: FirSession,
    kind: ContributionKind,
    annotation: FirAnnotation,
    contribution: Contribution,
    onError: () -> Nothing,
  ) {
    if (kind != ContributionKind.CONTRIBUTES_TO) return
    val declaration = (contribution as Contribution.ContributesTo).declaration
    if (declaration.symbol.isBindingContainer(session)) {
      return
    }
    if (declaration.classKind != ClassKind.INTERFACE) {
      // Special-case: if this is a contributed graph extension factory, don't report here because
      // it has its own (more specific) error.
      if (
        declaration.isAnnotatedWithAny(session, session.classIds.graphExtensionFactoryAnnotations)
      ) {
        return
      }
      reporter.reportOn(
        annotation.source,
        MetroDiagnostics.AGGREGATION_ERROR,
        "`@${kind}` annotations only permitted on interfaces or binding containers. However ${declaration.nameOrSpecialName} is a ${declaration.classKind}.",
      )
      onError()
    }
  }

  sealed interface Contribution {
    val declaration: FirClass
    val annotation: FirAnnotation
    val scope: ClassId
    val replaces: Set<ClassId>

    sealed interface BindingContribution : Contribution {
      val bindingType: FirTypeKey
    }

    @Poko
    class ContributesTo(
      override val declaration: FirClass,
      @Poko.Skip override val annotation: FirAnnotation,
      override val scope: ClassId,
      override val replaces: Set<ClassId>,
    ) : Contribution

    @Poko
    class ContributesBinding(
      override val declaration: FirClass,
      @Poko.Skip override val annotation: FirAnnotation,
      override val scope: ClassId,
      override val replaces: Set<ClassId>,
      override val bindingType: FirTypeKey,
    ) : Contribution, BindingContribution

    @Poko
    class ContributesIntoSet(
      override val declaration: FirClass,
      @Poko.Skip override val annotation: FirAnnotation,
      override val scope: ClassId,
      override val replaces: Set<ClassId>,
      override val bindingType: FirTypeKey,
    ) : Contribution, BindingContribution

    @Poko
    class ContributesIntoMap(
      override val declaration: FirClass,
      @Poko.Skip override val annotation: FirAnnotation,
      override val scope: ClassId,
      override val replaces: Set<ClassId>,
      override val bindingType: FirTypeKey,
      val mapKey: MetroFirAnnotation,
    ) : Contribution, BindingContribution
  }
}
