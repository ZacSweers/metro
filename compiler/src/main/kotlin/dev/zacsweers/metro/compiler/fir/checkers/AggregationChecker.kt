// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.checkers

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.fir.FirTypeKey
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.fir.MetroFirAnnotation
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.diagnosticString
import dev.zacsweers.metro.compiler.fir.findInjectLikeConstructors
import dev.zacsweers.metro.compiler.fir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.fir.isBindingContainer
import dev.zacsweers.metro.compiler.fir.isIde
import dev.zacsweers.metro.compiler.fir.isKiaIntoMultibinding
import dev.zacsweers.metro.compiler.fir.isOrImplements
import dev.zacsweers.metro.compiler.fir.isResolved
import dev.zacsweers.metro.compiler.fir.mapKeyAnnotation
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.resolveDefaultBindingType
import dev.zacsweers.metro.compiler.fir.resolvedBindingArgument
import dev.zacsweers.metro.compiler.fir.resolvedScopeClassId
import dev.zacsweers.metro.compiler.fir.scopeArgument
import dev.zacsweers.metro.compiler.fir.toClassSymbolCompat
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.tracing.trace
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
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
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.nameOrSpecialName
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.UnexpandedTypeCheck
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.isAny
import org.jetbrains.kotlin.fir.types.isNothing
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds

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
    if (
      !declaration.isAnnotatedWithAny(
        session,
        session.classIds.allContributesAnnotationsWithContainers,
      )
    ) {
      return
    }
    session.trace(name = { "AggregationChecker(${declaration.classId})" }) {
      checkImpl(declaration)
    }
  }

  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun checkImpl(declaration: FirClass) {
    val session = context.session
    val classIds = session.classIds
    // TODO
    //  validate map key with intomap (class or bound type)

    val contributesToAnnotations = mutableSetOf<Contribution.ContributesTo>()
    val contributesBindingAnnotations = mutableSetOf<Contribution.ContributesBinding>()
    val contributesIntoSetAnnotations = mutableSetOf<Contribution.ContributesIntoSet>()
    val contributesIntoMapAnnotations = mutableSetOf<Contribution.ContributesIntoMap>()

    val classQualifier = declaration.annotations.qualifierAnnotation(session)

    // Returns true if checkImpl should bail early after the walk.
    fun walkAnnotations(): Boolean {
      for (annotation in declaration.annotations) {
        if (!annotation.isResolved) continue
        val classId = annotation.toAnnotationClassId(session) ?: continue
        if (classId in classIds.allContributesAnnotations) {
          val scope = annotation.resolvedScopeClassId(session) ?: continue

          // Check if the target looks suspicious
          // - If it's a `@Scope` annotation class that's prolly not right
          // - If it's a graph class
          val scopeClass = scope.toLookupTag().toClassSymbolCompat(session) ?: continue
          if (scopeClass.classKind == ClassKind.ANNOTATION_CLASS) {
            scopeClass.annotationsIn(session, classIds.scopeAnnotations).firstOrNull()?.let {
              reporter.reportOn(
                annotation.scopeArgument(session)?.source ?: annotation.source,
                MetroDiagnostics.SUSPICIOUS_AGGREGATION_SCOPE,
                "Suspicious aggregation scope '${scope.diagnosticString}' is a concrete `@Scope` annotation type, and probably not what you meant. Aggregation scopes are usually simple abstract classes like 'dev.zacsweers.metro.AppScope'.",
              )
            }
          } else {
            for (graphLikeAnno in
              scopeClass.annotationsIn(session, classIds.graphLikeAnnotations)) {
              if (graphLikeAnno !is FirAnnotationCall) continue
              reporter.reportOn(
                annotation.scopeArgument(session)?.source ?: annotation.source,
                MetroDiagnostics.SUSPICIOUS_AGGREGATION_SCOPE,
                "Suspicious aggregation scope '${scope.diagnosticString}' appears to be a dependency graph or graph extension and probably not what you meant. Aggregation scopes are usually simple abstract classes like 'dev.zacsweers.metro.AppScope'.",
              )
            }
          }

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
              val contribution =
                Contribution.ContributesTo(declaration, annotation, scope, replaces)
              addContributionAndCheckForDuplicate(
                session,
                contribution,
                ContributionKind.CONTRIBUTES_TO,
                contributesToAnnotations,
                annotation,
                scope,
              ) {
                return true
              }
            }

            in classIds.contributesBindingAnnotations -> {
              if (annotation.isKiaIntoMultibinding(session)) {
                if (!checkIntoSet) {
                  return true
                }
              } else {
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
                  return true
                }
              }
            }

            in classIds.contributesIntoSetAnnotations -> {
              if (!checkIntoSet) {
                return true
              }
            }

            in classIds.contributesIntoMapAnnotations -> {
              if (!checkIntoMap) {
                return true
              }
            }

            in classIds.customContributesIntoSetAnnotations -> {
              val isMapBinding = declaration.annotations.mapKeyAnnotation(session) != null
              val valid = if (isMapBinding) checkIntoMap else checkIntoSet
              if (!valid) {
                return true
              }
            }
          }
        }
      }
      return false
    }

    val bailFromAnnotationWalk = session.trace({ "Walk class annotations" }) { walkAnnotations() }
    if (bailFromAnnotationWalk) return

    // Warn if @ExposeImplBinding is used but generateContributionProviders is not enabled
    if (!session.metroFirBuiltIns.options.generateContributionProviders) {
      declaration
        .annotationsIn(session, setOf(classIds.exposeImplBindingAnnotation))
        .firstOrNull()
        ?.let { exposeAnnotation ->
          reporter.reportOn(
            exposeAnnotation.source,
            MetroDiagnostics.EXPOSE_IMPL_TYPE_WITHOUT_CONTRIBUTION_PROVIDERS,
            "`@ExposeImplBinding` has no effect when `generateContributionProviders` is not enabled.",
          )
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
          if (refClassId == StandardClassIds.Any) {
            // If they are binding explicitly to Any, then it's ok
          } else {
            reporter.reportOn(
              annotation.source,
              MetroDiagnostics.AGGREGATION_ERROR,
              "`@$kind`-annotated class ${declaration.symbol.classId.asSingleFqName()} has no supertypes to bind to.",
            )
            return false
          }
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
        }

        // Check @DefaultBinding first — it takes priority over implicit single-supertype
        // resolution (e.g., @DefaultBinding<Factory<*>> on Factory<T> binds as Factory<*>).
        when (val result = resolveDefaultBindingFromSupertypes(session, supertypesExcludingAny)) {
          is DefaultBindingResult.Ambiguous -> {
            reporter.reportOn(
              annotation.source,
              MetroDiagnostics.AGGREGATION_ERROR,
              "`@$kind`-annotated class @${classId.asSingleFqName()} doesn't declare an explicit `binding` type but ambiguously has multiple supertypes that declare a `@DefaultBinding` (${result.types.joinToString { it.classId!!.diagnosticString }}). You must define an explicit bound type in this scenario.",
            )
            return false
          }
          is DefaultBindingResult.Found -> FirTypeKey(result.type, classQualifier)
          DefaultBindingResult.None -> {
            if (supertypesExcludingAny.size == 1) {
              FirTypeKey(supertypesExcludingAny[0].coneType, classQualifier)
            } else {
              reporter.reportOn(
                annotation.source,
                MetroDiagnostics.AGGREGATION_ERROR,
                "`@$kind`-annotated class @${classId.asSingleFqName()} doesn't declare an explicit `binding` type but has multiple supertypes. You must define an explicit bound type in this scenario.",
              )
              return false
            }
          }
        }
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

        // Check implicit class key usage
        checkImplicitClassKeyUsage(
          session,
          resolvedKey,
          implicitType = declaration.symbol.classId,
          source = declaration.source,
        )

        resolvedKey
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
    // Check for non-public contributions
    checkContributionVisibility(session, contribution.declaration, annotation, kind, scope)
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

  /**
   * Checks if a contribution has valid visibility. Private is never allowed.
   *
   * Checks if a contributed class (or any containing class) has non-public effective visibility. If
   * it does, and the scope is not also non-public, reports a diagnostic based on the configured
   * severity.
   *
   * Note: We treat `protected` as non-public for contributions, even though it's technically part
   * of the public API from an inheritance perspective. A protected class can't be accessed from
   * outside its class hierarchy, making it unsuitable for contributions to public scopes.
   */
  context(context: CheckerContext, reporter: DiagnosticReporter)
  private fun checkContributionVisibility(
    session: FirSession,
    declaration: FirClass,
    annotation: FirAnnotation,
    kind: ContributionKind,
    scope: ClassId,
  ) {
    val effectiveVisibility = declaration.effectiveVisibility

    if (effectiveVisibility.privateApi) {
      reporter.reportOn(
        declaration.source,
        MetroDiagnostics.PRIVATE_CONTRIBUTION_ERROR,
        "@Contributes*-annotated classes cannot be private.",
      )
      return
    }

    val options = session.metroFirBuiltIns.options
    val severity = options.nonPublicContributionSeverity.resolve(session.isIde())
    if (severity == MetroOptions.DiagnosticSeverity.NONE) return

    // Treat protected as non-public for contributions - a protected class can't be accessed
    // from outside its class hierarchy, making it unsuitable for contributions to public scopes
    val isProtected = effectiveVisibility.toVisibility() == Visibilities.Protected
    if (effectiveVisibility.publicApi && !isProtected) return

    // Check if the scope class is also non-public - if so, don't report since it's intentionally
    // non-public. Note: protected scopes are treated as public here since hint functions only use
    // the scope for naming, not by directly referencing it.
    val scopeSymbol = session.symbolProvider.getClassLikeSymbolByClassId(scope)
    if (scopeSymbol != null) {
      if (!scopeSymbol.effectiveVisibility.publicApi) {
        return
      }
    }

    val visibilityName = effectiveVisibility.name

    val message =
      "`@${annotation.toAnnotationClassId(session)?.shortClassName ?: kind.readableName}`-annotated class ${declaration.classId.asSingleFqName()} is $visibilityName but contributes to a public scope `${scope.shortClassName}`. " +
        "Consider making the class public or using a non-public scope."

    val diagnosticFactory =
      when (severity) {
        ERROR -> MetroDiagnostics.NON_PUBLIC_CONTRIBUTION_ERROR
        WARN -> MetroDiagnostics.NON_PUBLIC_CONTRIBUTION_WARNING
        else -> return
      }
    reporter.reportOn(declaration.source, diagnosticFactory, message)
  }

  sealed interface DefaultBindingResult {
    data class Found(val type: ConeKotlinType) : DefaultBindingResult

    data class Ambiguous(val types: List<ConeKotlinType>) : DefaultBindingResult

    data object None : DefaultBindingResult
  }

  /**
   * Resolves the default binding type from supertypes annotated with `@DefaultBinding`.
   *
   * Returns the [ConeKotlinType] if exactly one supertype has a default binding, or null if none or
   * ambiguous (multiple supertypes with `@DefaultBinding`).
   */
  private fun resolveDefaultBindingFromSupertypes(
    session: FirSession,
    supertypes: List<FirTypeRef>,
  ): DefaultBindingResult {
    val defaultBindingTypes = mutableListOf<ConeKotlinType>()

    for (supertype in supertypes) {
      val supertypeClassId = supertype.coneTypeOrNull?.fullyExpandedClassId(session) ?: continue
      val supertypeSymbol =
        session.symbolProvider.getClassLikeSymbolByClassId(supertypeClassId) as? FirClassSymbol<*>
          ?: continue

      val resolvedType = supertypeSymbol.resolveDefaultBindingType(session) ?: continue
      defaultBindingTypes += resolvedType
    }

    return when (defaultBindingTypes.size) {
      0 -> DefaultBindingResult.None
      1 -> DefaultBindingResult.Found(defaultBindingTypes[0])
      else -> DefaultBindingResult.Ambiguous(defaultBindingTypes)
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
