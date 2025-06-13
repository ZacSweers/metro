// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir.generators

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.Keys
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.anvilIgnoreQualifier
import dev.zacsweers.metro.compiler.fir.anvilKClassBoundTypeArgument
import dev.zacsweers.metro.compiler.fir.argumentAsOrNull
import dev.zacsweers.metro.compiler.fir.buildSimpleAnnotation
import dev.zacsweers.metro.compiler.fir.classIds
import dev.zacsweers.metro.compiler.fir.hasOrigin
import dev.zacsweers.metro.compiler.fir.mapKeyAnnotation
import dev.zacsweers.metro.compiler.fir.markAsDeprecatedHidden
import dev.zacsweers.metro.compiler.fir.metroFirBuiltIns
import dev.zacsweers.metro.compiler.fir.predicates
import dev.zacsweers.metro.compiler.fir.qualifierAnnotation
import dev.zacsweers.metro.compiler.fir.replaceAnnotationsSafe
import dev.zacsweers.metro.compiler.fir.resolvedClassId
import dev.zacsweers.metro.compiler.fir.scopeArgument
import dev.zacsweers.metro.compiler.joinSimpleNames
import dev.zacsweers.metro.compiler.joinSimpleNamesAndTruncate
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.getContainingClassSymbol
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassIdSafe
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirGetClassCall
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.toReference
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.references.impl.FirSimpleNamedReference
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isResolved
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal class ContributionsFirGenerator(session: FirSession) :
  FirDeclarationGenerationExtension(session) {

  // For each contributing class, track its nested contribution classes and their scope arguments
  private val contributingClassToScopedContributions:
    FirCache<FirClassSymbol<*>, Map<Name, FirGetClassCall?>, Unit> =
    session.firCachesFactory.createCache { contributingClassSymbol, _ ->
      val contributionAnnotations =
        contributingClassSymbol.annotations
          .annotationsIn(session, session.classIds.allContributesAnnotations)
          .toList()

      val contributionNamesToScopeArgs = mutableMapOf<Name, FirGetClassCall?>()

      if (contributionAnnotations.isNotEmpty()) {
        // We create a contribution class for each scope being contributed to. E.g. if there are
        // contributions for AppScope and LibScope we'll create $$MetroContributionToLibScope and
        // $$MetroContributionToAppScope
        // It'll try to use the fully name if possible, but because we really just need these to be
        // disambiguated we can just safely fall back to the short name in the worst case
        contributionAnnotations
          .mapNotNull { it.scopeArgument() }
          .distinctBy { it.scopeName(session) }
          .forEach { scopeArgument ->
            val suffix =
              scopeArgument.resolvedClassId()?.let { scopeClass ->
                scopeClass
                  .joinSimpleNamesAndTruncate(separator = "", camelCase = true)
                  .asSingleFqName()
                  .pathSegments()
                  .joinToString(separator = "") { it.identifier.decapitalizeUS() }
              }
                ?: scopeArgument.scopeName(session)
                ?: error("Could not get scope name for ${scopeArgument.render()}")
            val nestedContributionName =
              (Symbols.StringNames.METRO_CONTRIBUTION_NAME_PREFIX + "To" + suffix.capitalizeUS())
                .asName()

            contributionNamesToScopeArgs.put(nestedContributionName, scopeArgument)
          }
      }
      contributionNamesToScopeArgs
    }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(session.predicates.contributesAnnotationPredicate)
  }

  sealed interface Contribution {
    val origin: ClassId

    sealed interface BindingContribution : Contribution {
      // TODO make formatted name instead
      val callableName: String
      val annotatedType: FirClassSymbol<*>
      val annotation: FirAnnotation
      val buildAnnotations: () -> List<FirAnnotation>
    }

    data class ContributesTo(override val origin: ClassId) : Contribution

    data class ContributesBinding(
      override val annotatedType: FirClassSymbol<*>,
      override val annotation: FirAnnotation,
      override val buildAnnotations: () -> List<FirAnnotation>,
    ) : Contribution, BindingContribution {
      override val origin: ClassId = annotatedType.classId
      override val callableName: String = "bind"
    }

    data class ContributesIntoSetBinding(
      override val annotatedType: FirClassSymbol<*>,
      override val annotation: FirAnnotation,
      override val buildAnnotations: () -> List<FirAnnotation>,
    ) : Contribution, BindingContribution {
      override val origin: ClassId = annotatedType.classId
      override val callableName: String = "bindIntoSet"
    }

    data class ContributesIntoMapBinding(
      override val annotatedType: FirClassSymbol<*>,
      override val annotation: FirAnnotation,
      override val buildAnnotations: () -> List<FirAnnotation>,
    ) : Contribution, BindingContribution {
      override val origin: ClassId = annotatedType.classId
      override val callableName: String = "bindIntoMap"
    }
  }

  private fun findContributions(contributingSymbol: FirClassSymbol<*>): Set<Contribution>? {
    val contributesToAnnotations = session.classIds.contributesToLikeAnnotations
    val contributesBindingAnnotations = session.classIds.contributesBindingAnnotations
    val contributesIntoSetAnnotations = session.classIds.contributesIntoSetAnnotations
    val contributesIntoMapAnnotations = session.classIds.contributesIntoMapAnnotations
    val contributions = mutableSetOf<Contribution>()
    for (annotation in contributingSymbol.annotations.filter { it.isResolved }) {
      val annotationClassId = annotation.toAnnotationClassIdSafe(session) ?: continue
      when (annotationClassId) {
        in contributesToAnnotations -> {
          contributions += Contribution.ContributesTo(contributingSymbol.classId)
        }
        in contributesBindingAnnotations -> {
          contributions +=
            Contribution.ContributesBinding(contributingSymbol, annotation) {
              listOf(buildBindsAnnotation())
            }
        }
        in contributesIntoSetAnnotations -> {
          contributions +=
            Contribution.ContributesIntoSetBinding(contributingSymbol, annotation) {
              listOf(buildIntoSetAnnotation(), buildBindsAnnotation())
            }
        }
        in contributesIntoMapAnnotations -> {
          contributions +=
            Contribution.ContributesIntoMapBinding(contributingSymbol, annotation) {
              listOf(buildIntoMapAnnotation(), buildBindsAnnotation())
            }
        }
        in session.classIds.customContributesIntoSetAnnotations -> {
          contributions +=
            if (contributingSymbol.mapKeyAnnotation(session) != null) {
              Contribution.ContributesIntoMapBinding(contributingSymbol, annotation) {
                listOf(buildIntoMapAnnotation(), buildBindsAnnotation())
              }
            } else {
              Contribution.ContributesIntoSetBinding(contributingSymbol, annotation) {
                listOf(buildIntoSetAnnotation(), buildBindsAnnotation())
              }
            }
        }
      }
    }

    return if (contributions.isEmpty()) {
      null
    } else {
      contributions
    }
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> {
    return contributingClassToScopedContributions.getValue(classSymbol, Unit).keys
  }

  /**
   * Assumes we are calling this on an annotation's 'scope' argument value -- used in contexts where
   * we can't resolve the scope argument to get the full classId
   */
  private fun FirGetClassCall?.scopeName(session: FirSession): String? {
    return this?.argument
      ?.toReference(session)
      ?.expectAsOrNull<FirSimpleNamedReference>()
      ?.name
      ?.identifier
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    if (!name.identifier.startsWith(Symbols.StringNames.METRO_CONTRIBUTION_NAME_PREFIX)) return null
    val contributions = findContributions(owner) ?: return null
    return createNestedClass(
        owner,
        name = name,
        key = Keys.MetroContributionClassDeclaration,
        classKind = ClassKind.INTERFACE,
      ) {
        // annoyingly not implicit from the class kind
        modality = Modality.ABSTRACT
        for (contribution in contributions) {
          if (contribution is Contribution.ContributesTo) {
            superType(contribution.origin.defaultType(emptyList()))
          }
        }
      }
      .apply {
        markAsDeprecatedHidden(session)
        val metroContributionAnnotation =
          buildMetroContributionAnnotation().apply {
            replaceArgumentMapping(
              buildAnnotationArgumentMapping {
                val originalScopeArg =
                  contributingClassToScopedContributions.getValueIfComputed(owner)?.get(name)
                    ?: error("Could not find a contribution scope for ${owner.classId}.$name")
                this.mapping.put(Symbols.Names.scope, originalScopeArg)
              }
            )
          }
        replaceAnnotations(annotations + listOf(metroContributionAnnotation))
      }
      .symbol
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    if (!classSymbol.hasOrigin(Keys.MetroContributionClassDeclaration)) return emptySet()
    val origin = classSymbol.getContainingClassSymbol() as? FirClassSymbol<*> ?: return emptySet()
    val contributions = findContributions(origin) ?: return emptySet()
    val scopeArg =
      classSymbol.annotations
        .single { it.toAnnotationClassId(session) == Symbols.ClassIds.metroContribution }
        .scopeArgument()
    // Note the names we supply here are not final, we just need to know if we're going to generate
    // _any_ names for this type. We will return n >= 1 properties in generateProperties later.
    return contributions
      .filterIsInstance<Contribution.BindingContribution>()
      .filter { it.annotation.scopeArgument().scopeName(session) == scopeArg.scopeName(session) }
      .groupBy { it.callableName.asName() }
      .keys
  }

  // Also check ignoreQualifier for interop after entering interop block to prevent unnecessary
  // checks for non-interop
  private fun FirAnnotation.bindingTypeOrNull(): Pair<FirTypeRef?, Boolean> {
    // Return a binding defined using Metro's API
    argumentAsOrNull<FirFunctionCall>(Symbols.Names.binding, 1)?.let { bindingType ->
      return bindingType.typeArguments
        .getOrNull(0)
        ?.expectAsOrNull<FirTypeProjectionWithVariance>()
        ?.typeRef
        ?.takeUnless { it == session.builtinTypes.nothingType } to false
    }
    // Return a boundType defined using anvil KClass
    return anvilKClassBoundTypeArgument(session) to anvilIgnoreQualifier(session)
  }

  override fun generateProperties(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirPropertySymbol> {
    val owner = context?.owner ?: return emptyList()
    if (!owner.hasOrigin(Keys.MetroContributionClassDeclaration)) return emptyList()
    val origin = owner.getContainingClassSymbol() as? FirClassSymbol<*> ?: return emptyList()
    val contributions = findContributions(origin) ?: return emptyList()
    val scopeId =
      owner.annotations
        .single { it.toAnnotationClassId(session) == Symbols.ClassIds.metroContribution }
        .scopeArgument()
        ?.resolvedClassId() ?: error("Could not find a contribution scope for ${owner.classId}")

    return contributions
      .filterIsInstance<Contribution.BindingContribution>()
      .filter { it.callableName == callableId.callableName.identifier }
      .filter { it.annotation.scopeArgument()?.resolvedClassId() == scopeId }
      .map { contribution -> buildBindingProperty(owner, contribution) }
  }

  private fun buildBindingProperty(
    owner: FirClassSymbol<*>,
    contribution: Contribution.BindingContribution,
  ): FirPropertySymbol {
    val (bindingTypeRef, ignoreQualifier) = contribution.annotation.bindingTypeOrNull()
    // Standard annotation on the class itself, look for a single bound type
    val bindingType =
      (bindingTypeRef ?: contribution.annotatedType.resolvedSuperTypeRefs.single()).coneType

    val qualifier =
      if (!ignoreQualifier) {
        bindingTypeRef?.annotations?.qualifierAnnotation(session)
          ?: contribution.annotatedType.qualifierAnnotation(session)
      } else {
        null
      }

    val mapKey =
      if (bindingTypeRef == null) {
        contribution.annotatedType.mapKeyAnnotation(session)
      } else {
        // Call back to the class if the bound type doesn't have one
        bindingTypeRef.annotations.mapKeyAnnotation(session)
          ?: contribution.annotatedType.mapKeyAnnotation(session)
      }

    val suffix = buildString {
      qualifier?.hashString()?.let(::append)

      bindingType.classId
        ?.joinSimpleNames("", camelCase = true)
        ?.shortClassName
        ?.capitalizeUS()
        ?.let(::append)
    }

    return createMemberProperty(
        owner,
        Keys.MetroContributionCallableDeclaration,
        (contribution.callableName + "As" + suffix).asName(),
        returnType = bindingType,
        hasBackingField = false,
      ) {
        modality = Modality.ABSTRACT
        extensionReceiverType(contribution.origin.defaultType(emptyList()))
      }
      .also { property ->
        val newAnnotations = mutableListOf<FirAnnotation>()
        newAnnotations.addAll(contribution.buildAnnotations())
        qualifier?.fir?.let(newAnnotations::add)
        mapKey?.fir?.let(newAnnotations::add)
        property.replaceAnnotationsSafe(newAnnotations)
      }
      .symbol
  }

  private fun buildBindsAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.bindsClassSymbol }
  }

  private fun buildIntoSetAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.intoSetClassSymbol }
  }

  private fun buildIntoMapAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.intoMapClassSymbol }
  }

  private fun buildMetroContributionAnnotation(): FirAnnotation {
    return buildSimpleAnnotation { session.metroFirBuiltIns.metroContributionClassSymbol }
  }
}
