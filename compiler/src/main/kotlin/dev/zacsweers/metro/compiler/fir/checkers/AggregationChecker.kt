// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.fir.FirMetroErrors
import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.MetroFirAnnotation
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.findInjectConstructors
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isOrImplements
import dev.zacsweers.metro.compiler.fir.mapKeyAnnotation
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.rankArgument
import dev.zacsweers.metro.compiler.fir.resolvedBindingArgument
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import dev.zacsweers.metro.compiler.unsafeLazy
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
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.name.ClassId

internal object AggregationChecker : FirClassChecker(MppCheckerKind.Common) {
  enum class ContributionKind(val readableName: String) {
    CONTRIBUTES_TO("ContributesTo"),
    CONTRIBUTES_BINDING("ContributesBinding"),
    CONTRIBUTES_INTO_SET("ContributesIntoSet"),
    CONTRIBUTES_INTO_MAP("ContributesIntoMap");

    override fun toString(): String = readableName
  }

  override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
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
        val checkIntoSet by unsafeLazy {
          checkBindingContribution(
            session,
            ContributionKind.CONTRIBUTES_INTO_SET,
            declaration,
            classQualifier,
            annotation,
            scope,
            classId,
            context,
            reporter,
            contributesIntoSetAnnotations,
            isMapBinding = false,
          ) { bindingType, _ ->
            Contribution.ContributesIntoSet(declaration, annotation, scope, replaces, bindingType)
          }
        }
        val checkIntoMap by unsafeLazy {
          checkBindingContribution(
            session,
            ContributionKind.CONTRIBUTES_INTO_MAP,
            declaration,
            classQualifier,
            annotation,
            scope,
            classId,
            context,
            reporter,
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
              contribution,
              ContributionKind.CONTRIBUTES_TO,
              contributesToAnnotations,
              annotation,
              scope,
              reporter,
              context,
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
                context,
                reporter,
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
  private fun <T : Contribution> checkBindingContribution(
    session: FirSession,
    kind: ContributionKind,
    declaration: FirClass,
    classQualifier: MetroFirAnnotation?,
    annotation: FirAnnotation,
    scope: ClassId,
    classId: ClassId,
    context: CheckerContext,
    reporter: DiagnosticReporter,
    collection: MutableSet<T>,
    isMapBinding: Boolean,
    createBinding: (FirTypeKey, mapKey: MetroFirAnnotation?) -> T,
  ): Boolean {
    val injectConstructor = declaration.symbol.findInjectConstructors(session).singleOrNull()
    val isAssistedFactory =
      declaration.symbol.isAnnotatedWithAny(session, session.classIds.assistedFactoryAnnotations)
    // Ensure the class is injected or an object. Objects are ok IFF they are not @ContributesTo
    val isNotInjectedOrFactory = !isAssistedFactory && injectConstructor == null
    val isValidObject = declaration.classKind.isObject && kind != ContributionKind.CONTRIBUTES_TO
    if (isNotInjectedOrFactory && !isValidObject) {
      reporter.reportOn(
        annotation.source,
        FirMetroErrors.AGGREGATION_ERROR,
        "`@$kind` is only applicable to constructor-injected classes, assisted factories, or objects. Ensure ${declaration.symbol.classId.asSingleFqName()} is injectable or a bindable object.",
        context,
      )
      return false
    }

    val isAssistedInject =
      injectConstructor != null &&
        injectConstructor.valueParameterSymbols.any {
          it.isAnnotatedWithAny(session, session.classIds.assistedAnnotations)
        }
    if (isAssistedInject) {
      reporter.reportOn(
        annotation.source,
        FirMetroErrors.AGGREGATION_ERROR,
        "`@$kind` doesn't make sense on assisted-injected class ${declaration.symbol.classId.asSingleFqName()}. Did you mean to apply this to its assisted factory?",
        context,
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
            explicitBindingType.source,
            FirMetroErrors.AGGREGATION_ERROR,
            "Explicit bound types should not be `Nothing` or `Nothing?`.",
            context,
          )
          return false
        }

        val coneType = explicitBindingType.coneTypeOrNull ?: return true
        val refClassId = coneType.fullyExpandedClassId(session) ?: return true

        if (refClassId == declaration.symbol.classId) {
          reporter.reportOn(
            explicitBindingType.source,
            FirMetroErrors.AGGREGATION_ERROR,
            "Redundant explicit bound type ${refClassId.asSingleFqName()} is the same as the annotated class ${refClassId.asSingleFqName()}.",
            context,
          )
          return false
        }

        if (!hasSupertypes) {
          reporter.reportOn(
            annotation.source,
            FirMetroErrors.AGGREGATION_ERROR,
            "`@$kind`-annotated class ${declaration.symbol.classId.asSingleFqName()} has no supertypes to bind to.",
            context,
          )
          return false
        }

        val implementsBindingType = declaration.isOrImplements(refClassId, session)

        if (!implementsBindingType) {
          reporter.reportOn(
            explicitBindingType.source,
            FirMetroErrors.AGGREGATION_ERROR,
            "Class ${declaration.classId.asSingleFqName()} does not implement explicit bound type ${refClassId.asSingleFqName()}",
            context,
          )
          return false
        }

        FirTypeKey(coneType, (explicitBindingType.annotations.qualifierAnnotation(session)))
      } else {
        if (!hasSupertypes) {
          reporter.reportOn(
            annotation.source,
            FirMetroErrors.AGGREGATION_ERROR,
            "`@$kind`-annotated class ${declaration.symbol.classId.asSingleFqName()} has no supertypes to bind to.",
            context,
          )
          return false
        } else if (supertypesExcludingAny.size != 1) {
          reporter.reportOn(
            annotation.source,
            FirMetroErrors.AGGREGATION_ERROR,
            "`@$kind`-annotated class @${classId.asSingleFqName()} doesn't declare an explicit `bindingType` but has multiple supertypes. You must define an explicit bound type in this scenario.",
            context,
          )
          return false
        } else if (
          session.metroFirBuiltIns.options.enableDaggerAnvilInterop &&
            annotation.rankArgument() != null
        ) {
          val errorMessage =
            """
              `@$kind`-annotated class ${declaration.symbol.classId.asSingleFqName()} sets a rank but doesn't declare its binding type.
              Bindings with non-default ranks must declare explicit binding types. This is because
              we're not able to resolve supertypes to get the implicit binding type when
              processing ranked contributions.
            """
              .trimIndent()
          reporter.reportOn(
            annotation.source,
            FirMetroErrors.AGGREGATION_ERROR,
            errorMessage,
            context,
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
                  FirMetroErrors.AGGREGATION_ERROR,
                  "`@$kind`-annotated class ${declaration.classId.asSingleFqName()} must declare a map key on the class or an explicit bound type but doesn't.",
                  context,
                )
              }
            }
          } else {
            (explicitBindingType.annotations.mapKeyAnnotation(session) ?: classMapKey).also {
              if (it == null) {
                reporter.reportOn(
                  explicitBindingType.source,
                  FirMetroErrors.AGGREGATION_ERROR,
                  "`@$kind`-annotated class @${declaration.symbol.classId.asSingleFqName()} must declare a map key but doesn't. Add one on the explicit bound type or the class.",
                  context,
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
      contribution,
      kind,
      collection,
      annotation,
      scope,
      reporter,
      context,
    ) {
      return false
    }
    return true
  }

  private inline fun <T : Contribution> addContributionAndCheckForDuplicate(
    contribution: T,
    kind: ContributionKind,
    collection: MutableSet<T>,
    annotation: FirAnnotation,
    scope: ClassId,
    reporter: DiagnosticReporter,
    context: CheckerContext,
    onError: () -> Nothing,
  ) {
    checkContributionKind(kind, annotation, contribution, reporter, context) { onError() }
    val added = collection.add(contribution)
    if (!added) {
      reporter.reportOn(
        annotation.source,
        FirMetroErrors.AGGREGATION_ERROR,
        "Duplicate `@${kind}` annotations contributing to scope `${scope.shortClassName}`.",
        context,
      )

      val existing = collection.first { it == contribution }
      reporter.reportOn(
        existing.annotation.source,
        FirMetroErrors.AGGREGATION_ERROR,
        "Duplicate `@${kind}` annotations contributing to scope `${scope.shortClassName}`.",
        context,
      )

      onError()
    }
  }

  private inline fun checkContributionKind(
    kind: ContributionKind,
    annotation: FirAnnotation,
    contribution: Contribution,
    reporter: DiagnosticReporter,
    context: CheckerContext,
    onError: () -> Nothing,
  ) {
    if (kind != ContributionKind.CONTRIBUTES_TO) return
    val declaration = (contribution as Contribution.ContributesTo).declaration
    if (declaration.classKind != ClassKind.INTERFACE) {
      reporter.reportOn(
        annotation.source,
        FirMetroErrors.AGGREGATION_ERROR,
        "`@${kind}` annotations only permitted on interfaces. However ${declaration.nameOrSpecialName} is a ${declaration.classKind}.",
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
